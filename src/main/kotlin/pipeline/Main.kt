package pipeline

import com.github.ajalt.clikt.core.CliktCommand
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
import pipeline.stages.ApplicationStage
import pipeline.stages.BenchmarkEntry
import pipeline.stages.IngestionStage
import pipeline.stages.ManifestTriageStage
import pipeline.stages.RepairStage
import pipeline.stages.SynthesisStage
import pipeline.stages.VerificationStage
import pipeline.util.FileOps
import pipeline.util.Git

private val logger = KotlinLogging.logger {}

private fun loadConfig(path: String): ProjectConfig = ProjectConfig.load(File(path))

private fun database(config: ProjectConfig): SQLiteDatabaseManager =
    SQLiteDatabaseManager(config.paths.databaseFile())

private fun buildOrchestrator(
    config: ProjectConfig,
    entry: BenchmarkEntry,
    db: SQLiteDatabaseManager,
    cas: CasEngine
): PipelineOrchestrator {
    val buildRunner = GradleBuildRunner(config, entry.build, entry.sourceCompatibility, entry.targetCompatibility)
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
        config = config
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

class RunCommand : CliktCommand(name = "run", help = "Run the end-to-end pipeline for one curated benchmark") {
    private val configPath by option("--config", help = "Path to project_config.yaml").required()
    private val benchmark by option("--benchmark", help = "Benchmark id from benchmarks.yaml").required()
    private val seed by option("--seed", help = "Deterministic sample seed / sample index").int().default(0)
    private val model by option("--model", help = "Override the configured model for every stage")

    override fun run() = runBlocking {
        val loaded = loadConfig(configPath)
        val config = loaded.let { c ->
            model?.let { chosen ->
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
        }
        require(config.triage.mode == "manifest") {
            "triage.mode='${config.triage.mode}' is not supported; the automated crawler is deferred (design plan §7.4)"
        }
        val manifestFile = File(config.triage.manifestFile)
        val entries = BenchmarkManifestLoader.load(manifestFile, config)
        val entry = BenchmarkManifestLoader.resolve(entries, benchmark)
            ?: error("Benchmark '$benchmark' not found in ${manifestFile.path}; known ids: ${entries.joinToString { it.benchmarkId.canonical }}")
        val benchmarkRepo = File(entry.repoUrl)
        if (benchmarkRepo.isDirectory && Git.isRepository(benchmarkRepo)) {
            val dirty = Git.statusPorcelain(benchmarkRepo)
            require(dirty.isBlank()) {
                "Benchmark repository ${benchmarkRepo.path} is dirty; revert or commit changes before running so " +
                    "agent contamination is detectable:\n$dirty"
            }
        }
        val cas = CasEngine(File(config.cache.stagesDir), config.cache.enabled)
        database(config).use { db ->
            // Sequential MVP: a run still marked running is stale.
            db.markStaleRunningRuns()
            val orchestrator = buildOrchestrator(config, entry, db, cas)
            val outcome = orchestrator.runSingleSample(entry, seed)
            printOutcome(outcome)
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

private fun printOutcome(outcome: PipelineRunOutcome) {
    val r = outcome.result
    println("")
    println("run_id:                    ${outcome.runId}")
    println("verdict:                   ${r.verdict.name.lowercase()}")
    println("regression_test_passed:    ${r.regressionTestPassed}")
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
    PipelineCli()
        .subcommands(InitCommand(), RunCommand(), VerifyBenchmarkCommand())
        .main(args)
}
