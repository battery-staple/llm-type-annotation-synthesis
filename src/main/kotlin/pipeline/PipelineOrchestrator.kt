package pipeline

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import pipeline.cas.CasEngine
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.ports.DatabasePort
import pipeline.stages.*
import pipeline.util.CommitMetadata
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson

private val logger = KotlinLogging.logger {}

data class PipelineRunOutcome(
    val runId: RunId,
    val result: VerificationResult,
    val discarded: Boolean = false,
    val haltMessage: String? = null
)

/**
 * Sequential MVP orchestrator. One sample at a time; every stage attempt gets its
 * own step_id, and successful bundles are published atomically to the Merkle CAS.
 */
class PipelineOrchestrator(
    private val ingestion: IngestionStage,
    private val triage: ManifestTriageStage,
    private val synthesis: SynthesisStage,
    private val application: ApplicationStage,
    private val repair: RepairStage,
    private val verification: VerificationStage,
    private val cas: CasEngine,
    private val database: DatabasePort,
    private val config: ProjectConfig,
    private val maxTransientAttempts: Int = 3
) {
    suspend fun runSingleSample(entry: BenchmarkEntry, sampleSeed: Int): PipelineRunOutcome {
        val runId = RunId(UUID.randomUUID().toString())
        val startedAt = Instant.now().toString()
        val worktreeBase = File(".worktrees/run_$runId")
        // Agent workspaces MUST live outside the project repository. OpenCode
        // resolves tool cwd to the enclosing Git root, so a workspace under
        // .scratch/ would let agents write into the project root and break
        // both blindness isolation and per-stage validation.
        val scratchRoot = System.getenv("PIPELINE_SCRATCH_DIR")?.let { File(it) }
            ?: File(System.getProperty("java.io.tmpdir"), "pipeline-scratch")
        scratchRoot.mkdirs()
        val scratchBase = scratchRoot.resolve("run_$runId").apply { mkdirs() }
        val repoDir = File(entry.repoUrl)
        val metadata = runCatching { Git.metadata(resolveMetadataRepo(entry), entry.commitHash.hash) }
            .getOrElse { CommitMetadata("", emptyList(), "", "") }

        database.recordRunStart(
            EvaluationRunRecord(
                runId = runId,
                benchmarkId = entry.benchmarkId.canonical,
                commitHash = entry.commitHash.hash,
                repoId = repoId(entry.repoIdentity),
                repoName = entry.benchmarkId.canonical,
                gitUrl = entry.repoIdentity,
                defaultBranch = entry.defaultBranch,
                configYaml = readConfigText(),
                parentCommit = entry.parentHash.hash,
                authorDate = metadata.authorDate,
                commitMsg = metadata.message,
                sampleIndex = sampleSeed,
                sampleTemperature = config.sample.temperature,
                configSnapshot = PipelineJson.compact.encodeToString(ProjectConfig.serializer(), config),
                guardrailsMode = config.guardrails.mode,
                verificationScope = config.verification.scope,
                startedAt = startedAt
            )
        )

        val baseCtx = RunContext(
            config = config,
            build = entry.build,
            sourceCompatibility = entry.sourceCompatibility,
            targetCompatibility = entry.targetCompatibility,
            runId = runId,
            sampleIndex = sampleSeed,
            sampleTemperature = config.sample.temperature,
            repoDir = repoDir,
            casDir = File(config.cache.stagesDir),
            worktreeDir = worktreeBase.resolve("ingestion"),
            agentWorkspaceDir = scratchBase,
            database = database
        )

        try {
            val worktree = stageOrDiscard(baseCtx, 1, ingestion, IngestionInput(entry.benchmarkId, entry.repoUrl, entry.commitHash, entry.parentHash))
                ?: return finishDiscarded(runId, "triage preconditions failed during ingestion")
            val sample = stageOrDiscard(
                baseCtx, 2, triage,
                ManifestTriageInput(entry.benchmarkId, worktree, entry.regressionTest, entry.testPatchFile)
            ) ?: return finishDiscarded(runId, "triage discarded the sample")

            val productionDiff = productionDiff(entry)
            val lattice = requireStage(
                baseCtx, 3, synthesis,
                SynthesisInput(entry.toBugContext(commitDiff = productionDiff))
            )

            val applicationWorktree = worktreeBase.resolve("application")
            materializeC1(entry, applicationWorktree)
            val annotated = requireStage(
                baseCtx, 4, application,
                ApplicationInput(ApplicationTarget(sample.parentHash, applicationWorktree), lattice)
            )

            val repairWorktree = worktreeBase.resolve("repair")
            materializeC1(entry, repairWorktree)
            val patchCheck = Git.checkPatch(repairWorktree, annotated.annotationsPatch)
            if (patchCheck.exitCode != 0) {
                throw StageHaltException("annotations.patch does not apply cleanly to clean C-1: ${patchCheck.output.take(2000)}")
            }
            val apply = Git.applyPatch(repairWorktree, annotated.annotationsPatch)
            if (apply.exitCode != 0) {
                throw StageHaltException("failed to apply annotations.patch to repair baseline: ${apply.output.take(2000)}")
            }
            val repaired = requireStage(
                baseCtx, 5, repair,
                RepairInput(
                    BlindRepairTarget(sample.parentHash, repairWorktree),
                    lattice.toCheckerConfig(),
                    annotated.initialCheckerErrorsLog,
                    annotated.annotationsPatch
                )
            )

            val verificationWorktree = worktreeBase.resolve("verification")
            materializeC1(entry, verificationWorktree)
            val result = requireStage(
                baseCtx, 6, verification,
                VerificationInput(
                    sample,
                    repaired,
                    lattice,
                    annotated.annotationsPatch,
                    verificationWorktree,
                    config.verification.scope
                )
            )

            database.finishRun(runId, "success")
            database.recordVerdict(runId, result.verdict)
            logger.info { "Run $runId completed with verdict ${result.verdict}" }
            return PipelineRunOutcome(runId, result)
        } catch (halt: StageHaltException) {
            logger.error(halt) { "Run $runId halted" }
            database.finishRun(runId, "failed")
            return PipelineRunOutcome(runId, incompleteResult(runId, halt.message ?: "stage halted"), haltMessage = halt.message)
        } catch (t: Throwable) {
            logger.error(t) { "Run $runId failed" }
            runCatching { database.finishRun(runId, "failed") }
            throw t
        }
    }

    private suspend fun finishDiscarded(runId: RunId, message: String): PipelineRunOutcome {
        database.finishRun(runId, "failed")
        return PipelineRunOutcome(runId, incompleteResult(runId, message), discarded = true, haltMessage = message)
    }

    private suspend fun <I, O> requireStage(
        baseCtx: RunContext,
        stepNumber: Int,
        stage: PipelineStage<I, O>,
        input: I
    ): O = stageOrDiscard(baseCtx, stepNumber, stage, input)
        ?: throw StageHaltException("Stage ${stage.stageName.identifier} discarded unexpectedly")

    private suspend fun <I, O> stageOrDiscard(
        baseCtx: RunContext,
        stepNumber: Int,
        stage: PipelineStage<I, O>,
        input: I
    ): O? {
        val cacheKey = stage.computeCacheKey(
            baseCtx.copy(stepNumber = stepNumber, stageName = stage.stageName),
            input
        )

        if (config.cache.enabled) {
            val cached = cas.lookup(stage.stageName, cacheKey, includeDiscards = true)
            if (cached != null && !cached.manifest.success) {
                val stepId = UUID.randomUUID().toString()
                database.recordStep(
                    StepTrajectoryRecord(
                        stepId = stepId, runId = baseCtx.runId, stepNumber = stepNumber, attemptNumber = 1,
                        stepName = stage.stageName.identifier, casKey = cacheKey.toString(), cacheHit = true,
                        success = false,
                        artifactsManifest = PipelineJson.compact.encodeToString(
                            ArtifactsManifest.serializer(), cached.manifest
                        ),
                        startedAt = Instant.now().toString(), completedAt = Instant.now().toString()
                    )
                )
                logger.info { "CAS HIT (cached deterministic discard) for ${stage.stageName.identifier} key=$cacheKey" }
                return null
            }
            if (cached != null) {
                val stepId = UUID.randomUUID().toString()
                val data = stage.restoreCached(cached.dir, cached.manifest)
                if (data != null) {
                    val restored: O = if (stage.stageName == StageName.INGESTION && input is IngestionInput) {
                        @Suppress("UNCHECKED_CAST")
                        restoreIngestionWorktree(input, baseCtx.worktreeDir) as O
                    } else {
                        data
                    }
                    val ctx = baseCtx.copy(
                        stepId = stepId, stepNumber = stepNumber, attemptNumber = 1, stageName = stage.stageName
                    )
                    database.recordStep(
                        StepTrajectoryRecord(
                            stepId = stepId,
                            runId = ctx.runId,
                            stepNumber = stepNumber,
                            attemptNumber = 1,
                            stepName = stage.stageName.identifier,
                            casKey = cacheKey.toString(),
                            cacheHit = true,
                            success = true,
                            artifactsManifest = PipelineJson.compact.encodeToString(
                                ArtifactsManifest.serializer(), cached.manifest
                            ),
                            startedAt = Instant.now().toString(),
                            completedAt = Instant.now().toString()
                        )
                    )
                    logger.info { "CAS HIT for ${stage.stageName.identifier} key=$cacheKey" }
                    return restored
                }
                logger.warn { "CAS bundle ${cached.dir} could not be restored for ${stage.stageName.identifier}; re-executing" }
            }
        }

        var attempt = 1
        while (true) {
            val stepId = UUID.randomUUID().toString()
            val started = Instant.now().toString()
            val ctx = baseCtx.copy(
                stepId = stepId,
                stepNumber = stepNumber,
                attemptNumber = attempt,
                stageName = stage.stageName
            )
            database.recordStep(
                StepTrajectoryRecord(
                    stepId = stepId, runId = ctx.runId, stepNumber = stepNumber, attemptNumber = attempt,
                    stepName = stage.stageName.identifier, casKey = cacheKey.toString(), cacheHit = false,
                    success = false, startedAt = started
                )
            )
            val outcome = withContext(
                MDCContext(
                    mapOf(
                        "run_id" to ctx.runId.value,
                        "benchmark_id" to "",
                        "sample_index" to ctx.sampleIndex.toString(),
                        "stage_name" to stage.stageName.identifier,
                        "turn_index" to "-",
                        "cas_key" to cacheKey.toString()
                    )
                )
            ) {
                stage.execute(ctx, input)
            }

            when (outcome) {
                is StageResult.Success -> {
                    val bundle = outcome.bundleDir
                        ?: cas.stageDir(stage.stageName).resolve(outcome.casKey.toString())
                    val publishedDir = if (outcome.bundleDir != null) {
                        cas.publish(stage.stageName, outcome.casKey, bundle, outcome.artifactsManifest)
                    } else {
                        bundle
                    }
                    database.recordStep(
                        StepTrajectoryRecord(
                            stepId = stepId, runId = ctx.runId, stepNumber = stepNumber, attemptNumber = attempt,
                            stepName = stage.stageName.identifier, casKey = outcome.casKey.toString(), cacheHit = false,
                            success = true,
                            artifactsManifest = PipelineJson.compact.encodeToString(
                                ArtifactsManifest.serializer(), outcome.artifactsManifest
                            ),
                            startedAt = started, completedAt = Instant.now().toString()
                        )
                    )
                    logger.info { "Stage ${stage.stageName.identifier} succeeded (attempt $attempt) key=${outcome.casKey}" }
                    return relocateArtifacts(outcome.data, publishedDir)
                }
                is StageResult.Discard -> {
                    if (outcome.artifactsManifest != null && outcome.bundleDir != null) {
                        runCatching {
                            cas.publish(stage.stageName, cacheKey, outcome.bundleDir, outcome.artifactsManifest)
                        }.onFailure { logger.warn(it) { "Failed to publish discard bundle for ${stage.stageName.identifier}" } }
                    }
                    database.recordStep(
                        StepTrajectoryRecord(
                            stepId = stepId, runId = ctx.runId, stepNumber = stepNumber, attemptNumber = attempt,
                            stepName = stage.stageName.identifier, casKey = cacheKey.toString(), cacheHit = false,
                            success = false,
                            artifactsManifest = outcome.artifactsManifest?.let {
                                PipelineJson.compact.encodeToString(ArtifactsManifest.serializer(), it)
                            },
                            compilerDiagnostics = outcome.details,
                            startedAt = started, completedAt = Instant.now().toString()
                        )
                    )
                    logger.warn { "Stage ${stage.stageName.identifier} discarded: ${outcome.reason} - ${outcome.details}" }
                    return null
                }
                is StageResult.TransientFailure -> {
                    database.recordStep(
                        StepTrajectoryRecord(
                            stepId = stepId, runId = ctx.runId, stepNumber = stepNumber, attemptNumber = attempt,
                            stepName = stage.stageName.identifier, casKey = cacheKey.toString(), cacheHit = false,
                            success = false,
                            compilerDiagnostics = outcome.error.message,
                            agentTranscript = outcome.transcript,
                            startedAt = started, completedAt = Instant.now().toString()
                        )
                    )
                    logger.warn(outcome.error) {
                        "Transient failure in ${stage.stageName.identifier} (attempt $attempt/$maxTransientAttempts)"
                    }
                    if (attempt >= maxTransientAttempts) throw outcome.error
                    delay((1L shl attempt) * 1000L)
                    attempt++
                }
            }
        }
    }

    /**
     * Stage outputs point at their staging bundle while executing. After the
     * atomic CAS publish that directory is renamed, so artifact paths must be
     * rebased onto the published bundle before downstream stages use them.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> relocateArtifacts(data: T, dir: File): T = when (data) {
        is VerifiedSample -> data.copy(testPatchFile = dir.resolve(pipeline.cas.ArtifactNames.TEST_PATCH)) as T
        is SynthesizedLattice -> data.copy(
            customQualsJar = dir.resolve(pipeline.cas.ArtifactNames.CUSTOM_QUALS_JAR),
            placementGuideFile = dir.resolve(pipeline.cas.ArtifactNames.PLACEMENT_GUIDE),
            customQualsSrcDir = dir.resolve(pipeline.cas.ArtifactNames.CUSTOM_QUALS_SRC_DIR)
        ) as T
        is AnnotatedRepo -> data.copy(
            annotationsPatch = dir.resolve(pipeline.cas.ArtifactNames.ANNOTATIONS_PATCH),
            astAuditFile = dir.resolve(pipeline.cas.ArtifactNames.AST_AUDIT),
            initialCheckerErrorsLog = dir.resolve(pipeline.cas.ArtifactNames.INITIAL_CHECKER_ERRORS)
        ) as T
        is RepairResult -> data.copy(
            repairPatch = dir.resolve(pipeline.cas.ArtifactNames.REPAIR_PATCH),
            finalCheckerErrorsLog = dir.resolve(pipeline.cas.ArtifactNames.FINAL_CHECKER_ERRORS),
            antiCheatAuditFile = dir.resolve(pipeline.cas.ArtifactNames.ANTI_CHEAT_AUDIT),
            guardrailEventsFile = dir.resolve(pipeline.cas.ArtifactNames.GUARDRAIL_EVENTS)
        ) as T
        is VerificationResult -> data.copy(
            testResultsXml = data.testResultsXml?.let { dir.resolve(pipeline.cas.ArtifactNames.TEST_RESULTS_XML) },
            suiteResultsXml = data.suiteResultsXml?.let { dir.resolve(pipeline.cas.ArtifactNames.SUITE_RESULTS_XML) },
            verificationSummaryFile = dir.resolve(pipeline.cas.ArtifactNames.VERIFICATION_SUMMARY)
        ) as T
        else -> data
    }

    private fun restoreIngestionWorktree(input: IngestionInput, destination: File): IngestionWorktree {
        val evalRepos = config.paths.evalRepos()
        val repoName = File(input.repoUrl).name.removeSuffix(".git")
        val mirror = Git.ensureMirror(input.repoUrl, evalRepos, repoName)
        Git.addWorktree(mirror, destination, input.parentHash.hash)
        return IngestionWorktree(input.commitHash, input.parentHash, destination, mirror)
    }

    private fun materializeC1(entry: BenchmarkEntry, destination: File) {
        val evalRepos = config.paths.evalRepos()
        val repoName = File(entry.repoUrl).name.removeSuffix(".git")
        val mirror = Git.ensureMirror(entry.repoUrl, evalRepos, repoName)
        Git.addWorktree(mirror, destination, entry.parentHash.hash)
    }

    private fun productionDiff(entry: BenchmarkEntry): String {
        val repo = resolveMetadataRepo(entry)
        val diff = Git.diffBetween(repo, entry.parentHash.hash, entry.commitHash.hash)
        return diff.split(Regex("(?=diff --git )"))
            .filter { block -> block.isNotBlank() && !block.lineSequence().first().contains("/src/test/") }
            .joinToString("\n")
    }

    private fun resolveMetadataRepo(entry: BenchmarkEntry): File {
        val local = File(entry.repoUrl)
        if (local.exists()) return local
        val evalRepos = config.paths.evalRepos()
        val mirror = Git.mirrorPath(evalRepos, File(entry.repoUrl).name.removeSuffix(".git"))
        return if (mirror.exists()) mirror else local
    }

    private fun repoId(url: String): String = "repo-" + Hashing.sha256(url).take(12)

    private fun readConfigText(): String =
        runCatching { config.sourceFile.readText() }.getOrDefault("")

    private fun incompleteResult(runId: RunId, message: String): VerificationResult {
        val summary = File(".worktrees/run_$runId/incomplete_summary.json")
        val json = buildJsonObject {
            put("run_id", JsonPrimitive(runId.value))
            put("verdict", JsonPrimitive(EvalVerdict.VERIFICATION_MISSING.name.lowercase()))
            put("message", JsonPrimitive(message))
            put("completed_at", JsonPrimitive(Instant.now().toString()))
        }
        summary.parentFile.mkdirs()
        summary.writeText(PipelineJson.json.encodeToString(JsonElement.serializer(), json))
        return VerificationResult(
            verdict = EvalVerdict.VERIFICATION_MISSING,
            regressionTestPassed = false,
            originalTestsPassed = null,
            remainingCheckerErrors = null,
            testResultsXml = null,
            suiteResultsXml = null,
            verificationSummaryFile = summary
        )
    }
}
