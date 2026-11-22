package pipeline

import java.io.File
import java.time.Duration
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
import pipeline.stages.BenchmarkCheckoutGuard
import pipeline.util.CommitMetadata
import pipeline.util.Env
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson
import pipeline.util.TranscriptStreams

private val logger = KotlinLogging.logger {}

data class PipelineRunOutcome(
    val runId: RunId,
    val result: VerificationResult,
    val discarded: Boolean = false,
    val haltMessage: String? = null,
    /**
     * Stage after which `--stop-after` ended the run. The completed prefix is
     * a success, but no verdict was evaluated, so [result] is an
     * incomplete/verification-missing summary.
     */
    val stoppedAfter: StageName? = null,
    val limitSkipped: Boolean = false
)

/**
 * Sequential MVP orchestrator. One sample at a time; every stage attempt gets its
 * own step_id, and successful bundles are published atomically to the Merkle CAS.
 */
class PipelineOrchestrator(
    private val ingestion: PipelineStage<IngestionInput, IngestionWorktree>,
    private val triage: PipelineStage<ManifestTriageInput, VerifiedSample>,
    private val synthesis: PipelineStage<SynthesisInput, SynthesizedLattice>,
    private val application: PipelineStage<ApplicationInput, AnnotatedRepo>,
    private val repair: PipelineStage<RepairInput, RepairResult>,
    private val verification: PipelineStage<VerificationInput, VerificationResult>,
    private val cas: CasEngine,
    private val database: DatabasePort,
    private val config: ProjectConfig,
    private val maxTransientAttempts: Int = 3,
    private val automatedTriage: PipelineStage<AutomatedTriageInput, VerifiedSample>? = null,
    /**
     * When set, the run stops once this stage succeeds and every later stage
     * is skipped. `null` (or [StageName.VERIFICATION]) runs the full pipeline.
     * Discards and halts still take precedence over the stop point.
     */
    private val stopAfter: StageName? = null,
    /** Where live (in-progress) agent transcripts are streamed for the run viewer. */
    private val transcriptStreamRoot: File = TranscriptStreams.defaultRoot(),
    private val transcriptStreamMaxAge: Duration = Duration.ofDays(14)
) {
    init {
        TranscriptStreams.sweep(transcriptStreamRoot, transcriptStreamMaxAge)
    }

    suspend fun crawlAndRun(target: pipeline.config.CrawlerRepoTarget, sampleSeed: Int): List<PipelineRunOutcome> {
        val autoTriage = automatedTriage
            ?: throw IllegalStateException("AutomatedTriageStage is required for crawler mode")
        val evalRepos = config.paths.evalRepos()
        val repoName = File(target.repoUrl).name.removeSuffix(".git")
        val mirror = Git.ensureMirror(target.repoUrl, evalRepos, repoName)
        val startRef = if (runCatching { Git.revParse(mirror, target.defaultBranch) }.isSuccess) {
            target.defaultBranch
        } else {
            logger.warn { "Crawler: defaultBranch '${target.defaultBranch}' not found in mirror; falling back to HEAD" }
            "HEAD"
        }
        val commits = Git.listCommits(mirror, startRef, config.triage.depth)
        val batchId = UUID.randomUUID().toString()
        BenchmarkCheckoutGuard.reset(File(target.repoUrl), batchId)
        // Root commits have no C-1 and are excluded before scheduling.
        commits.filter { it.parents.isEmpty() }.forEach { meta ->
            logger.info { "Crawler: skipping root commit ${meta.hash.take(8)} (no parent)" }
        }
        val schedulable = crawlerTriageCandidates(commits)
        val gate = DownstreamGate.limited(config.triage.limit)
        val entries = schedulable.map { meta ->
            val firstParent = meta.parents.first()
            val commitHash = CommitHash(meta.hash)
            val parentHash = CommitHash(firstParent)
            BenchmarkEntry(
                benchmarkId = BenchmarkId.of(target.benchmarkId.canonical, commitHash),
                repoUrl = target.repoUrl,
                commitHash = commitHash,
                parentHash = parentHash,
                regressionTest = TestCoordinate(Fqcn("crawler.Pending"), "pending"),
                description = meta.message,
                repoIdentity = target.repoIdentity,
                defaultBranch = target.defaultBranch,
                build = target.build,
                sourceCompatibility = target.sourceCompatibility,
                targetCompatibility = target.targetCompatibility,
                productionRoots = target.productionRoots,
                testRoots = target.testRoots
            ) to meta
        }
        val results = BatchScheduler(config.triage.jobs).run(entries) { (entry, meta) ->
            runSingleSampleCrawler(entry, meta, sampleSeed, autoTriage, batchId, mirror, gate)
        }
        return results.map { item -> when (item) {
            is BatchItem.Completed -> item.value
            is BatchItem.Failed -> {
                logger.error(item.error) { "Crawler commit task failed" }
                val id = RunId(UUID.randomUUID().toString())
                PipelineRunOutcome(id, incompleteResult(id, item.error.message ?: "commit task failed"), haltMessage = item.error.message)
            }
        } }
    }

    suspend fun runSingleSample(entry: BenchmarkEntry, sampleSeed: Int): PipelineRunOutcome {
        val (runId, worktreeBase, baseCtx) = initRunContext(entry, sampleSeed)
        val outcome = withRunHandling(runId) {
            val worktree = stageOrDiscard(baseCtx, 1, ingestion, IngestionInput(entry.benchmarkId, entry.repoUrl, entry.commitHash, entry.parentHash))
                ?: return@withRunHandling finishDiscarded(runId, "triage preconditions failed during ingestion")
            stopIfDue(runId, StageName.INGESTION)?.let { return@withRunHandling it }
            val sample = stageOrDiscard(
                baseCtx, 2, triage,
                ManifestTriageInput(entry.benchmarkId, worktree, entry.regressionTest, entry.testPatchFile)
            ) ?: return@withRunHandling finishDiscarded(runId, "triage discarded the sample")

            runDownstreamStages(runId, entry, sample, baseCtx, worktreeBase)
        }
        cleanupCompletedRun(outcome, entry, worktreeBase)
        return outcome
    }

    private suspend fun runSingleSampleCrawler(
        entry: BenchmarkEntry,
        meta: CommitMetadata,
        sampleSeed: Int,
        autoTriage: PipelineStage<AutomatedTriageInput, VerifiedSample>,
        batchId: String,
        mirror: File,
        gate: DownstreamGate
    ): PipelineRunOutcome {
        val (runId, worktreeBase, baseCtx) = initRunContext(entry, sampleSeed, batchId)
        val outcome = withRunHandling(runId) {
            if (meta.parents.size != 1) {
                database.updateCommit(
                    CommitTriageUpdate(
                        commitHash = entry.commitHash.hash,
                        triageStatus = DiscardReason.MERGE_COMMIT.dbTriageStatus,
                        discardReason = DiscardReason.MERGE_COMMIT.wireName
                    )
                )
                return@withRunHandling finishDiscarded(runId, "merge commit (${meta.parents.size} parents) skipped")
            }
            val worktree = stageOrDiscard(baseCtx, 1, ingestion, IngestionInput(entry.benchmarkId, entry.repoUrl, entry.commitHash, entry.parentHash, mirror))
                ?: return@withRunHandling finishDiscarded(runId, "triage preconditions failed during ingestion")
            stopIfDue(runId, StageName.INGESTION)?.let { return@withRunHandling it }
            val sample = stageOrDiscard(
                baseCtx, 2, autoTriage,
                AutomatedTriageInput(entry.benchmarkId, worktree, meta)
            ) ?: return@withRunHandling finishDiscarded(runId, "automated triage discarded the sample")

            val verifiedEntry = entry.copy(regressionTest = sample.regressionTest)
            runDownstreamStages(runId, verifiedEntry, sample, baseCtx, worktreeBase, mirror, gate)
        }
        cleanupCompletedRun(outcome, entry, worktreeBase, mirror)
        return outcome
    }

    private fun cleanupCompletedRun(
        outcome: PipelineRunOutcome,
        entry: BenchmarkEntry,
        worktreeBase: File,
        resolvedMirror: File? = null
    ) {
        if (outcome.haltMessage != null || outcome.discarded || outcome.stoppedAfter != null) return
        if (!outcome.limitSkipped && outcome.result.verdict == EvalVerdict.VERIFICATION_MISSING) return
        runCatching {
            val mirror = resolvedMirror ?: run {
                val name = File(entry.repoUrl).name.removeSuffix(".git")
                Git.mirrorPath(config.paths.evalRepos(), name).takeIf { it.exists() }
            }
            var worktreesUnregistered = true
            listOf("ingestion", "application", "repair", "verification").forEach { name ->
                val worktree = worktreeBase.resolve(name)
                if (worktree.exists() && mirror == null) {
                    worktreesUnregistered = false
                    logger.warn { "Cannot unregister ${worktree.path}; no existing Git mirror is available" }
                } else if (worktree.exists()) {
                    runCatching { Git.removeWorktree(mirror!!, worktree) }
                        .onFailure {
                            worktreesUnregistered = false
                            logger.warn(it) { "Could not unregister run worktree ${worktree.path}" }
                        }
                }
            }
            if (worktreesUnregistered) RuntimeCleanup.deleteTree(worktreeBase)
            val scratchRoot = Env.get("PIPELINE_SCRATCH_DIR")?.let(::File)
                ?: File(System.getProperty("java.io.tmpdir"), "pipeline-scratch")
            RuntimeCleanup.deleteTree(scratchRoot.resolve("run_${outcome.runId}"))
        }.onFailure { logger.warn(it) { "Best-effort cleanup failed for run ${outcome.runId}" } }
    }

    private suspend fun withRunHandling(
        runId: RunId,
        block: suspend () -> PipelineRunOutcome
    ): PipelineRunOutcome = try {
        block()
    } catch (halt: StageHaltException) {
        logger.error(halt) { "Run $runId halted" }
        database.finishRun(runId, "failed")
        PipelineRunOutcome(runId, incompleteResult(runId, halt.message ?: "stage halted"), haltMessage = halt.message)
    } catch (t: Throwable) {
        logger.error(t) { "Run $runId failed" }
        runCatching { database.finishRun(runId, "failed") }
        throw t
    }

    private suspend fun initRunContext(
        entry: BenchmarkEntry,
        sampleSeed: Int,
        batchId: String? = null
    ): Triple<RunId, File, RunContext> {
        val runId = RunId(UUID.randomUUID().toString())
        val startedAt = Instant.now().toString()
        val worktreeBase = File(".worktrees/run_$runId")
        // Agent workspaces MUST live outside the project repository. OpenCode
        // resolves tool cwd to the enclosing Git root, so a workspace under
        // .scratch/ would let agents write into the project root and break
        // both blindness isolation and per-stage validation.
        val scratchRoot = Env.get("PIPELINE_SCRATCH_DIR")?.let { File(it) }
            ?: File(System.getProperty("java.io.tmpdir"), "pipeline-scratch")
        scratchRoot.mkdirs()
        val scratchBase = scratchRoot.resolve("run_$runId").apply { mkdirs() }
        val repoDir = File(entry.repoUrl)
        if (batchId == null) BenchmarkCheckoutGuard.reset(repoDir, runId.value)
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
                startedAt = startedAt,
                batchId = batchId
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
            database = database,
            productionRoots = entry.productionRoots,
            testRoots = entry.testRoots,
            contaminationScope = batchId ?: runId.value
        )
        return Triple(runId, worktreeBase, baseCtx)
    }

    private suspend fun runDownstreamStages(
        runId: RunId,
        entry: BenchmarkEntry,
        sample: VerifiedSample,
        baseCtx: RunContext,
        worktreeBase: File,
        resolvedMirror: File? = null,
        downstreamGate: DownstreamGate = DownstreamGate.AllowAll
    ): PipelineRunOutcome {
        // Triage is the first boundary: every downstream stage starts from a
        // verified sample, so a stop requested here must also skip the
        // production-diff work below.
        stopIfDue(runId, StageName.TRIAGE)?.let { return it }

        if (!downstreamGate.admit()) {
            database.finishRun(runId, "triage_only")
            logger.info { "Run $runId triage succeeded; downstream limit reached" }
            return PipelineRunOutcome(
                runId = runId,
                result = incompleteResult(runId, "verified sample retained after downstream limit"),
                limitSkipped = true
            )
        }

        val productionDiff = productionDiff(entry)
        val lattice = requireStage(
            baseCtx, 3, synthesis,
            SynthesisInput(entry.toBugContext(commitDiff = productionDiff))
        )
        stopIfDue(runId, StageName.SYNTHESIS)?.let { return it }

        val applicationWorktree = worktreeBase.resolve("application")
        materializeC1(entry, applicationWorktree, resolvedMirror)
        val annotated = requireStage(
            baseCtx, 4, application,
            ApplicationInput(ApplicationTarget(sample.parentHash, applicationWorktree), lattice)
        )
        stopIfDue(runId, StageName.APPLICATION)?.let { return it }

        val repairWorktree = worktreeBase.resolve("repair")
        materializeC1(entry, repairWorktree, resolvedMirror)
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
        stopIfDue(runId, StageName.REPAIR)?.let { return it }

        val verificationWorktree = worktreeBase.resolve("verification")
        materializeC1(entry, verificationWorktree, resolvedMirror)
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
    }

    private suspend fun finishDiscarded(runId: RunId, message: String): PipelineRunOutcome {
        database.finishRun(runId, "failed")
        return PipelineRunOutcome(runId, incompleteResult(runId, message), discarded = true, haltMessage = message)
    }

    /**
     * Ends the run when [completed] is the requested `--stop-after` stage.
     *
     * A stopped run is not a failure: the requested prefix succeeded, and the
     * `evaluation_runs` lifecycle status records where it stopped
     * (`stopped_after_triage`, ...). No verdict is recorded, because nothing
     * was evaluated; the incomplete summary carries the stop point instead.
     */
    private suspend fun stopIfDue(runId: RunId, completed: StageName): PipelineRunOutcome? {
        if (!stopsAfter(completed, stopAfter)) return null
        database.finishRun(runId, stoppedRunStatus(completed))
        logger.info { "Run $runId stopped after ${completed.identifier} (--stop-after)" }
        return PipelineRunOutcome(
            runId = runId,
            result = incompleteResult(runId, "stopped after ${completed.identifier}"),
            stoppedAfter = completed
        )
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
                    if (restored is VerifiedSample) database.updateCommit(CommitTriageUpdate(
                        commitHash = restored.commitHash.hash, testPatch = restored.testPatchFile.absolutePath
                    ))
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
            val liveTranscript = TranscriptStreams.forStep(transcriptStreamRoot, ctx.runId.value, stepId)
            val outcome = withContext(
                liveTranscript + MDCContext(
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
                    // Durable transcript is now in the published bundle; drop the live copy.
                    liveTranscript.delete()
                    logger.info { "Stage ${stage.stageName.identifier} succeeded (attempt $attempt) key=${outcome.casKey}" }
                    val relocated = relocateArtifacts(outcome.data, publishedDir)
                    if (relocated is VerifiedSample) database.updateCommit(CommitTriageUpdate(
                        commitHash = relocated.commitHash.hash, testPatch = relocated.testPatchFile.absolutePath
                    ))
                    return relocated
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
                    if (outcome.transcript.isNotEmpty()) liveTranscript.delete()
                    logger.warn(outcome.error) {
                        "Transient failure in ${stage.stageName.identifier} (attempt $attempt/$maxTransientAttempts)"
                    }
                    val willRetry = attempt < maxTransientAttempts
                    val delayMs = if (willRetry) (1L shl attempt) * 1000L else 0L
                    database.recordRetry(pipeline.db.RetryEvent(
                        retryId = UUID.randomUUID().toString(), runId = ctx.runId.value, stepId = stepId,
                        stage = stage.stageName.identifier, attemptNumber = attempt, maxAttempts = maxTransientAttempts,
                        willRetry = willRetry, delayMs = delayMs,
                        errorClass = outcome.error.javaClass.simpleName,
                        errorMessage = (outcome.error.message ?: outcome.error.toString()).take(1000)
                    ))
                    if (!willRetry) throw outcome.error
                    delay(delayMs)
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
        val mirror = input.mirror ?: Git.ensureMirror(input.repoUrl, evalRepos, repoName)
        Git.addWorktree(mirror, destination, input.parentHash.hash)
        return IngestionWorktree(input.commitHash, input.parentHash, destination, mirror)
    }

    private fun materializeC1(entry: BenchmarkEntry, destination: File, resolvedMirror: File? = null) {
        val evalRepos = config.paths.evalRepos()
        val repoName = File(entry.repoUrl).name.removeSuffix(".git")
        val mirror = resolvedMirror ?: Git.ensureMirror(entry.repoUrl, evalRepos, repoName)
        Git.addWorktree(mirror, destination, entry.parentHash.hash)
    }

    private fun productionDiff(entry: BenchmarkEntry): String {
        val repo = resolveMetadataRepo(entry)
        val diff = Git.diffBetween(repo, entry.parentHash.hash, entry.commitHash.hash)
        return filterProductionDiff(diff, entry.testRoots)
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
            put("limit_skipped", JsonPrimitive(message.contains("downstream limit")))
            put("triage_only", JsonPrimitive(message.contains("downstream limit")))
            put("completed_at", JsonPrimitive(Instant.now().toString()))
        }
        summary.parentFile.mkdirs()
        summary.writeText(PipelineJson.json.encodeToString(JsonElement.serializer(), json))
        return VerificationResult(
            verdict = EvalVerdict.VERIFICATION_MISSING,
            regressionTestPassed = null,
            originalTestsPassed = null,
            remainingCheckerErrors = null,
            testResultsXml = null,
            suiteResultsXml = null,
            verificationSummaryFile = summary
        )
    }

    companion object {
        /**
         * True when the run should end after [completed] finishes.
         *
         * `--stop-after verification` is the full pipeline, so it is never a
         * stop; this keeps the default value behavior-identical to no flag.
         */
        internal fun stopsAfter(completed: StageName, stopAfter: StageName?): Boolean =
            stopAfter != null && completed == stopAfter && completed != StageName.VERIFICATION

        /**
         * `evaluation_runs.status` for a run that stopped after [stage]. The
         * plan's status set has no "stopped" value, so the stop point is
         * encoded in the label itself.
         */
        internal fun stoppedRunStatus(stage: StageName): String = "stopped_after_${stage.identifier}"

        /**
         * Crawler budget accounting: only commits that reached and passed
         * triage consume `triage.limit`. Discards never do, and a run stopped
         * after ingestion never got that far. Runs stopped at triage or later
         * did pass triage.
         */
        internal fun countsAsVerified(outcome: PipelineRunOutcome): Boolean =
            !outcome.discarded && outcome.stoppedAfter != StageName.INGESTION

        /**
         * Keeps only the diff blocks that touch at least one production path.
         *
         * Test-blindness fails closed: a block whose paths cannot be parsed is
         * dropped, and every parsed path is checked with the full test signal
         * ([pipeline.validators.SourceRoots.isTestPath]), including the legacy
         * `*Test.java` filename fallback. Directory-only checks would let a
         * test file under a production root leak into the synthesis prompt.
         */
        internal fun filterProductionDiff(
            diff: String,
            testRoots: List<String>
        ): String =
            diff.split(Regex("(?=diff --git )"))
                .filter { block ->
                    if (block.isBlank()) return@filter false
                    val paths = pipeline.validators.FilesystemGuard.auditPatchPaths(block)
                    if (paths.isEmpty()) {
                        logger.warn { "productionDiff: dropping diff block with no parseable paths" }
                        return@filter false
                    }
                    paths.any { !pipeline.validators.SourceRoots.isTestPath(it, testRoots) }
                }
                .joinToString("\n")
    }
}
