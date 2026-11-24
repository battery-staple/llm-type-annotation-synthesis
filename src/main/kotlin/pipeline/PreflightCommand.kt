package pipeline

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import pipeline.cas.CasEngine
import pipeline.config.PreflightTarget
import pipeline.config.PreflightTargets
import pipeline.config.ProjectConfig
import pipeline.db.PreflightResultRecord
import pipeline.db.SQLiteDatabaseManager
import pipeline.runners.JavacRunner
import pipeline.stages.CommitHash
import pipeline.stages.Fqcn
import pipeline.stages.IngestionWorktree
import pipeline.stages.PreflightInput
import pipeline.stages.PreflightReport
import pipeline.stages.PreflightStage
import pipeline.stages.RunContext
import pipeline.stages.RunId
import pipeline.stages.StageName
import pipeline.stages.StageResult
import pipeline.stages.BenchmarkId
import pipeline.util.Env
import pipeline.util.Git
import pipeline.util.RuntimeCleanup

/**
 * `pipeline preflight`: the build-integration preflight (checks P1-P7) without
 * the rest of the pipeline, for the eval repositories and the corpus
 * measurement (Dockerization plan, sections 7.3 and 7.4). Results go to
 * `preflight_results` with a null run id and reuse the preflight CAS bundles.
 */
class PreflightCommand : CliktCommand(
    name = "preflight",
    help = "Check whether repositories fit the supported build envelope (no LLM calls)"
) {
    private val configPath by option("--config", help = "Path to project_config.yaml").required()
    private val manifestPath by option(
        "--manifest",
        help = "benchmarks.yaml (checks each parent commit) or repos.yaml (checks preflight_commit or the branch head)"
    ).required()
    private val ids by option("--id", help = "Only these entry ids (repeatable)").multiple()
    private val skipSuite by option("--skip-suite", help = "Skip check P7, the full test suite").flag()
    private val noCache by option("--no-cache", help = "Ignore cached preflight results").flag()
    private val backendOption by option("--backend", help = "Override execution.backend (local | docker)")

    override fun run() = runBlocking {
        val file = File(configPath)
        Env.loadFor(file)
        val loaded = ProjectConfig.load(file)
        val (config, backend) = withBackend(
            loaded.copy(preflight = loaded.preflight.copy(suiteCheck = loaded.preflight.suiteCheck && !skipSuite)),
            backendOption
        )
        val targets = PreflightTargets.load(File(manifestPath), config)
            .filter { ids.isEmpty() || it.id in ids }
        require(targets.isNotEmpty()) { "no manifest entries match ${ids.ifEmpty { listOf("<all>") }}" }
        val cas = CasEngine(File(config.cache.stagesDir), config.cache.enabled && !noCache)
        val rows = mutableListOf<Pair<PreflightTarget, PreflightReport?>>()
        SQLiteDatabaseManager(config.paths.databaseFile()).use { db ->
            targets.forEach { target ->
                echo("preflight ${target.id} ...")
                val report = runCatching { preflight(config, backend, cas, db, target) }
                    .onFailure { echo("  error: ${it.message}", err = true) }
                    .getOrNull()
                report?.let { echo("  ${describe(it)}") }
                rows += target to report
            }
        }
        echo("")
        echo(summary(rows))
    }

    private suspend fun preflight(
        config: ProjectConfig,
        backend: pipeline.ports.ExecutionBackend,
        cas: CasEngine,
        db: SQLiteDatabaseManager,
        target: PreflightTarget,
    ): PreflightReport {
        val repoName = File(target.repoUrl).name.removeSuffix(".git")
        val mirror = Git.ensureMirror(target.repoUrl, config.paths.evalRepos(), repoName)
        val commit = target.commit ?: Git.revParse(mirror, target.defaultBranch)
        val runId = RunId("preflight-${UUID.randomUUID()}")
        val base = File(".worktrees/${runId.value}")
        val ingestionDir = base.resolve("ingestion")
        val preflightDir = base.resolve("preflight")
        try {
            Git.addWorktree(mirror, ingestionDir, commit)
            val runners = Runners(config, target.build, target.sourceCompatibility, target.targetCompatibility, backend)
            val stage = PreflightStage(runners.preflight, JavacRunner())
            val ctx = RunContext(
                config = config,
                build = target.build,
                sourceCompatibility = target.sourceCompatibility,
                targetCompatibility = target.targetCompatibility,
                runId = runId,
                sampleIndex = 0,
                sampleTemperature = 0.0,
                repoDir = File(target.repoUrl),
                casDir = File(config.cache.stagesDir),
                worktreeDir = ingestionDir,
                agentWorkspaceDir = base.resolve("scratch"),
                database = db,
                productionRoots = target.productionRoots,
                testRoots = target.testRoots,
                stepId = runId.value,
                stepNumber = 2,
                stageName = StageName.PREFLIGHT,
            )
            val commitHash = CommitHash(commit)
            val input = PreflightInput(
                benchmarkId = BenchmarkId.parse(target.id),
                ingestion = IngestionWorktree(commitHash, commitHash, ingestionDir),
                mirror = mirror,
                worktreeDir = preflightDir,
                configuredSourceLevel = target.sourceCompatibility,
                testClasses = target.testClasses.map { Fqcn(it) },
            )
            val key = stage.computeCacheKey(ctx, input)
            val cached = cas.lookup(StageName.PREFLIGHT, key)?.let { stage.restoreCached(it.dir, it.manifest) }
            val report = cached ?: execute(stage, ctx, input, cas)
            db.recordPreflight(
                PreflightResultRecord(
                    preflightId = runId.value,
                    runId = null,
                    benchmarkId = target.id,
                    commitHash = target.fixCommit ?: commit,
                    parentCommit = commit,
                    report = report,
                    cacheHit = cached != null,
                    logPath = cas.stageDir(StageName.PREFLIGHT).resolve(key.toString())
                        .resolve(PreflightStage.LOGS_DIR).path,
                )
            )
            return report
        } finally {
            listOf(preflightDir, ingestionDir).forEach { dir -> runCatching { Git.removeWorktree(mirror, dir) } }
            RuntimeCleanup.deleteTree(base)
        }
    }

    private suspend fun execute(
        stage: PreflightStage,
        ctx: RunContext,
        input: PreflightInput,
        cas: CasEngine,
    ): PreflightReport {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) {
            when (val result = stage.execute(ctx, input)) {
                is StageResult.Success -> {
                    cas.publish(StageName.PREFLIGHT, result.casKey, requireNotNull(result.bundleDir), result.artifactsManifest)
                    return result.data
                }
                is StageResult.TransientFailure -> lastError = result.error
                is StageResult.Discard -> error("preflight never discards: ${result.details}")
            }
        }
        throw IllegalStateException("preflight failed $MAX_ATTEMPTS times: ${lastError?.message}", lastError)
    }

    private fun describe(r: PreflightReport): String = buildString {
        append(r.status)
        r.reasonCode?.let { append(" ${r.failedCheck} $it") }
        append(" | gradle=${r.gradleVersion} runtime=${r.runtimeJdk} toolchain=${r.compileToolchainJdk}")
        append(" level declared=${r.sourceLevelDeclared} observed=${r.sourceLevelObserved} configured=${r.sourceLevelConfigured}")
        r.suiteDurationMs?.let { append(" | suite ${r.suiteTests} tests, ${r.suiteFailures} failing, ${it / 1000} s") }
        if (r.reasonCode != null) append("\n    ${r.message.lineSequence().first().take(300)}")
    }

    private fun summary(rows: List<Pair<PreflightTarget, PreflightReport?>>): String = buildString {
        appendLine(String.format("%-36s %-12s %-6s %-28s %-28s %s", "id", "status", "check", "reason", "expected", "match"))
        rows.forEach { (target, report) ->
            val outcome = when {
                report == null -> "error"
                report.passed -> "pass"
                else -> report.reasonCode ?: "unknown"
            }
            val match = when {
                target.expected == null || report == null -> "-"
                target.expected == outcome -> "yes"
                else -> "NO"
            }
            appendLine(String.format(
                "%-36s %-12s %-6s %-28s %-28s %s",
                target.id, report?.status ?: "error", report?.failedCheck ?: "", if (report?.passed == false) outcome else "",
                target.expected?.let { "$it (${target.expectedBasis ?: "?"})" } ?: "", match,
            ))
        }
        val predicted = rows.filter { it.first.expected != null && it.second != null }
        val hits = predicted.count { (t, r) -> t.expected == (if (r!!.passed) "pass" else r.reasonCode) }
        append("predictions matched: $hits of ${predicted.size}")
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
