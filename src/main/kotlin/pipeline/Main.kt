package pipeline

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import pipeline.cas.CasEngine
import pipeline.config.BenchmarkManifestLoader
import pipeline.config.ModelSection
import pipeline.config.ProjectConfig
import pipeline.db.SQLiteDatabaseManager
import pipeline.runners.CoroutineOpenCodeRunner
import pipeline.runners.GradleBuildRunner
import pipeline.runners.JavacRunner
import pipeline.runners.OpenCodeDb
import pipeline.runners.OpenCodeSingleShotLlmClient
import pipeline.stages.ApplicationStage
import pipeline.stages.AutomatedTriageStage
import pipeline.stages.BenchmarkEntry
import pipeline.stages.IngestionStage
import pipeline.stages.ManifestTriageStage
import pipeline.stages.RepairStage
import pipeline.stages.StageName
import pipeline.stages.SynthesisStage
import pipeline.stages.VerificationStage
import pipeline.util.Env
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.RuntimeCleanup

private val logger = KotlinLogging.logger {}

/** Canonical stage ids accepted by `--stop-after`, in pipeline order. */
internal val STAGE_IDS: List<String> = StageName.entries.map { it.identifier }

/**
 * Resolves a `--stop-after` value into a stage.
 *
 * Throws [IllegalArgumentException] for an unknown value so the CLI can report
 * the accepted stages through its normal usage error instead of a stack trace.
 */
internal fun parseStopAfter(raw: String): StageName =
    StageName.entries.firstOrNull { it.identifier == raw.trim().lowercase() }
        ?: throw IllegalArgumentException(
            "unknown --stop-after stage '${raw.trim()}'; expected one of: ${STAGE_IDS.joinToString(", ")}"
        )

private fun loadConfig(path: String): ProjectConfig {
    val file = File(path)
    Env.loadFor(file)
    return ProjectConfig.load(file)
}

private fun database(config: ProjectConfig): SQLiteDatabaseManager =
    SQLiteDatabaseManager(config.paths.databaseFile())

private fun checkRepoClean(benchmarkRepo: File) {
    if (benchmarkRepo.isDirectory && Git.isRepository(benchmarkRepo)) {
        val dirty = Git.statusPorcelain(benchmarkRepo)
        require(dirty.isBlank()) {
            "Benchmark repository ${benchmarkRepo.path} is dirty; revert or commit changes before running so " +
                "agent contamination is detectable:\n$dirty"
        }
    }
}

private fun buildOrchestrator(
    config: ProjectConfig,
    buildRunner: GradleBuildRunner,
    db: SQLiteDatabaseManager,
    cas: CasEngine,
    stopAfter: StageName? = null
): PipelineOrchestrator {
    val openCodeDb = OpenCodeDb.prepare()
    val agentRunner = CoroutineOpenCodeRunner(databasePath = openCodeDb?.absolutePath)
    val javac = JavacRunner()
    return PipelineOrchestrator(
        ingestion = IngestionStage(buildRunner),
        triage = ManifestTriageStage(buildRunner),
        synthesis = SynthesisStage(agentRunner, javac),
        application = ApplicationStage(agentRunner, buildRunner),
        repair = RepairStage(agentRunner, buildRunner),
        verification = VerificationStage(buildRunner),
        cas = cas,
        database = db,
        config = config,
        automatedTriage = AutomatedTriageStage(OpenCodeSingleShotLlmClient(agentRunner = agentRunner), buildRunner),
        stopAfter = stopAfter
    )
}

class InitCommand : CliktCommand(name = "init", help = "Initialize the SQLite database and pre-warm shared Git/Gradle caches") {
    private val configPath by option("--config", help = "Path to project_config.yaml").required()
    private val skipPrewarm by option("--skip-prewarm", help = "Only initialize the database").flag()

    override fun run() = runBlocking {
        val config = loadConfig(configPath)
        database(config).use { db ->
            echo("Initialized database ${config.paths.databaseFile().path}")
            if (!skipPrewarm) {
                val manifestFile = File(config.triage.manifestFile)
                if (!manifestFile.isFile) {
                    echo("Benchmark manifest ${manifestFile.path} not found; skipping pre-warm")
                    return@use
                }
                val entries = BenchmarkManifestLoader.load(manifestFile, config)
                val evalRepos = config.paths.evalRepos()
                entries.forEach { entry ->
                    val buildRunner = GradleBuildRunner(config, entry.build, entry.sourceCompatibility, entry.targetCompatibility)
                    val repoName = File(entry.repoUrl).name.removeSuffix(".git")
                    val mirror = Git.ensureMirror(entry.repoUrl, evalRepos, repoName)
                    val dir = File(".worktrees/init/${entry.benchmarkId.canonical}")
                    try {
                        Git.addWorktree(mirror, dir, entry.parentHash.hash)
                        val build = buildRunner.compile(dir)
                        echo("Pre-warm ${entry.benchmarkId.canonical}: ${if (build.success) "ok" else "compile failed"}")
                    } finally {
                        Git.removeWorktree(mirror, dir)
                    }
                }
            }
        }
    }
}

class RunCommand : CliktCommand(name = "run", help = "Run the end-to-end pipeline for one curated benchmark or crawl a repository") {
    private val configPath by option("--config", help = "Path to project_config.yaml").required()
    private val benchmark by option("--benchmark", help = "Benchmark id from benchmarks.yaml").required()
    private val seed by option("--seed", help = "Deterministic sample seed / sample index").int().default(0)
    private val model by option("--model", help = "Override the configured model for every stage")
    private val triageMode by option("--triage-mode", help = "Override triage.mode (manifest | crawler)")
    private val depth by option("--depth", help = "Override triage.depth (number of commits to crawl in crawler mode)").int()
    private val limit by option("--limit", help = "Override triage.limit (maximum verified samples sent to downstream stages)").int()
    private val jobs by option("--jobs", help = "Override triage.jobs (concurrent crawler commits; must be >= 1)").int()
    private val stopAfter by option(
        "--stop-after",
        help = "Stop once this stage succeeds and skip every later stage. " +
            "Completed stages stay cached, so a later run resumes from their bundles. " +
            "Values: ${STAGE_IDS.joinToString("|")} (default: run the whole pipeline)"
    )

    override fun run() = runBlocking {
        val stopAfterStage = stopAfter?.let { raw ->
            try {
                parseStopAfter(raw)
            } catch (invalid: IllegalArgumentException) {
                throw UsageError(invalid.message ?: "invalid --stop-after value '$raw'")
            }
        }
        val loaded = loadConfig(configPath)
        val config = loaded.let { c ->
            var updated = model?.let { chosen ->
                c.copy(
                    model = ModelSection(
                        default = chosen,
                        triage = chosen,
                        synthesis = chosen,
                        application = chosen,
                        repair = chosen
                    )
                )
            } ?: c
            if (jobs != null && jobs!! < 1) throw UsageError("--jobs must be at least 1")
            if (triageMode != null || depth != null || limit != null || jobs != null) {
                val resolvedMode = triageMode?.let { raw ->
                    when (raw.lowercase()) {
                        "manifest" -> pipeline.stages.TriageMode.MANIFEST
                        "crawler" -> pipeline.stages.TriageMode.CRAWLER
                        else -> throw UsageError("Invalid --triage-mode '$raw'; expected 'manifest' or 'crawler'")
                    }
                } ?: updated.triage.mode
                updated = updated.copy(
                    triage = updated.triage.copy(
                        mode = resolvedMode,
                        depth = depth ?: updated.triage.depth,
                        limit = limit ?: updated.triage.limit,
                        jobs = jobs ?: updated.triage.jobs
                    )
                )
                if (jobs != null && resolvedMode != pipeline.stages.TriageMode.CRAWLER) {
                    throw UsageError("--jobs is only available in crawler mode")
                }
            }
            updated
        }
        val cas = CasEngine(File(config.cache.stagesDir), config.cache.enabled)
        database(config).use { db ->
            // Sequential MVP: a run still marked running is stale.
            db.markStaleRunningRuns()
            if (config.triage.mode == pipeline.stages.TriageMode.CRAWLER) {
                val crawlerFile = File(config.triage.reposFile)
                val targets = pipeline.config.CrawlerManifestLoader.load(crawlerFile, config)
                val target = pipeline.config.CrawlerManifestLoader.resolve(targets, benchmark)
                    ?: error("Repo '$benchmark' not found in ${crawlerFile.path}; known ids: ${targets.joinToString { it.benchmarkId.canonical }}")
                checkRepoClean(File(target.repoUrl))
                val buildRunner = GradleBuildRunner(config, target.build, target.sourceCompatibility, target.targetCompatibility)
                val orchestrator = buildOrchestrator(config, buildRunner, db, cas, stopAfterStage)
                val outcomes = orchestrator.crawlAndRun(target, seed)
                outcomes.forEach { printOutcome(it) }
            } else {
                val manifestFile = File(config.triage.manifestFile)
                val entries = BenchmarkManifestLoader.load(manifestFile, config)
                val entry = BenchmarkManifestLoader.resolve(entries, benchmark)
                    ?: error("Benchmark '$benchmark' not found in ${manifestFile.path}; known ids: ${entries.joinToString { it.benchmarkId.canonical }}")
                checkRepoClean(File(entry.repoUrl))
                val buildRunner = GradleBuildRunner(config, entry.build, entry.sourceCompatibility, entry.targetCompatibility)
                val orchestrator = buildOrchestrator(config, buildRunner, db, cas, stopAfterStage)
                val outcome = orchestrator.runSingleSample(entry, seed)
                printOutcome(outcome)
            }
        }
    }
}

class VerifyBenchmarkCommand : CliktCommand(name = "verify-benchmark", help = "Verify a benchmark manifest entry end-to-end") {
    private val configPath by option("--config", help = "Path to project_config.yaml").required()
    private val benchmark by option("--benchmark", help = "Benchmark id from benchmarks.yaml").required()
    private val seed by option("--seed", help = "Deterministic sample seed / sample index").int().default(0)

    override fun run() {
        // Same execution path; the command exists for CLI parity with the plan.
        RunCommand().main(listOf("--config", configPath, "--benchmark", benchmark, "--seed", seed.toString()))
    }
}

internal fun lifecycleStatus(outcome: PipelineRunOutcome): String = when {
    outcome.limitSkipped -> "triage_only"
    outcome.stoppedAfter != null -> "stopped"
    outcome.discarded -> "discarded"
    outcome.haltMessage != null -> "failed"
    else -> "success"
}

private fun printOutcome(outcome: PipelineRunOutcome) {
    val r = outcome.result
    // Lifecycle status (orchestration completed) is distinct from the
    // evaluation verdict (what the repaired code actually achieved).
    val lifecycle = lifecycleStatus(outcome)
    println("")
    println("run_id:                    ${outcome.runId}")
    println("lifecycle_status:          $lifecycle")
    if (outcome.stoppedAfter != null) {
        println("stopped_after:             ${outcome.stoppedAfter.identifier}")
    }
    if (!outcome.limitSkipped) println("evaluation_verdict:        ${r.verdict.name.lowercase()}")
    println("regression_test_passed:    ${r.regressionTestPassed ?: "unknown"}")
    println("original_tests_passed:     ${r.originalTestsPassed ?: "unknown"}")
    println("remaining_checker_errors:  ${r.remainingCheckerErrors ?: "unknown"}")
    println("test_results_xml:          ${r.testResultsXml?.path ?: "-"}")
    println("suite_results_xml:         ${r.suiteResultsXml?.path ?: "-"}")
    println("summary:                   ${r.verificationSummaryFile.path}")
    if (outcome.haltMessage != null) println("note:                      ${outcome.haltMessage}")
}

class PipelineCli : CliktCommand(name = "pipeline") {
    override fun run() {
        println("Use one of: init, run, verify-benchmark")
    }
}

fun main(args: Array<String>) {
    RuntimeCleanup.startup()
    PipelineCli()
        .subcommands(InitCommand(), RunCommand(), VerifyBenchmarkCommand())
        .main(args)
}
