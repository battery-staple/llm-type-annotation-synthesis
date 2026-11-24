package pipeline.stages

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import pipeline.cas.CacheKeys
import pipeline.db.CommitTriageUpdate
import pipeline.ports.BuildRunner
import pipeline.ports.SingleShotLlmClient
import pipeline.ports.SingleShotLlmException
import pipeline.util.Git

/**
 * Automated commit-crawler triage stage (design plan §7.4).
 *
 * Classifies a single commit via [SingleShotLlmClient] using `prompts/triage.md`
 * and the full `git diff C-1..C`, then delegates to [TriageVerifier] to enforce
 * the deterministic C-1/C regression-test invariant before emitting [VerifiedSample].
 */
class AutomatedTriageStage(
    private val llmClient: SingleShotLlmClient,
    private val buildRunner: BuildRunner
) : PipelineStage<AutomatedTriageInput, VerifiedSample> {
    override val stageName: StageName = StageName.TRIAGE

    /** LLM classifier output is sample-dependent (temperature/seed/model). */
    override val isDeterministic: Boolean = false

    private val log = KotlinLogging.logger {}

    override fun computeCacheKey(ctx: RunContext, input: AutomatedTriageInput): CasKey {
        val template = PromptTemplates.load("triage.md")
        val stageInput = mapOf(
            "benchmark_id" to input.benchmarkId.canonical,
            "commit" to input.worktree.commitHash.hash,
            "parent" to input.worktree.parentHash.hash,
            "mode" to TriageMode.CRAWLER.name.lowercase()
        )
        val configSlice = mapOf(
            "triage_mode" to ctx.config.triage.mode.name.lowercase(),
            "triage_model" to ctx.config.model.triageModel,
            "bugfix_threshold" to ctx.config.triage.bugfixThreshold.toString(),
            "verification_test_task" to ctx.build.testTask,
            "verification_scope" to ctx.config.verification.scope.name.lowercase(),
            "triage_classifier_profile" to "no-tools-v1",
            "production_roots" to ctx.productionRoots.sorted(),
            "test_roots" to ctx.testRoots.sorted()
        ) + buildTimeoutConfig(ctx) + buildStageRetryConfig(ctx)
        return CacheKeys.compute(
            ctx = ctx,
            stage = stageName,
            stageInput = stageInput,
            upstreamKeys = emptyList(),
            configSlice = configSlice,
            promptTemplate = template,
            includeSampleIdentity = !isDeterministic
        )
    }

    override suspend fun execute(ctx: RunContext, input: AutomatedTriageInput): StageResult<VerifiedSample> {
        val key = computeCacheKey(ctx, input)
        val commitHash = input.worktree.commitHash
        val parentHash = input.worktree.parentHash

        if (input.commitMetadata.parents.size != 1) {
            return TriageVerifier.discard(
                ctx = ctx,
                benchmarkId = input.benchmarkId,
                commitHash = commitHash,
                parentHash = parentHash,
                cacheKey = key,
                reason = DiscardReason.MERGE_COMMIT,
                details = "Commit ${commitHash.abbreviated} has ${input.commitMetadata.parents.size} parents (not a single-parent commit)",
                sourceProvenance = TriageMode.CRAWLER
            )
        }

        val fullDiff = try {
            Git.diffBetween(input.worktree.repoDir, parentHash.hash, commitHash.hash)
        } catch (t: Throwable) {
            return StageResult.TransientFailure(
                error = RuntimeException("Failed to compute diff ${parentHash.abbreviated}..${commitHash.abbreviated}: ${t.message}", t),
                stageTurnsTaken = 0,
                transcript = emptyList()
            )
        }

        val diffLines = fullDiff.lineSequence().count()
        if (diffLines > MAX_DIFF_LINES) {
            return TriageVerifier.discard(
                ctx = ctx,
                benchmarkId = input.benchmarkId,
                commitHash = commitHash,
                parentHash = parentHash,
                cacheKey = key,
                reason = DiscardReason.DIFF_TOO_LARGE,
                details = "Commit diff is $diffLines lines (exceeds $MAX_DIFF_LINES line limit)",
                sourceProvenance = TriageMode.CRAWLER
            )
        }

        val template = PromptTemplates.load("triage.md")
        val prompt = buildString {
            append(template)
            append("\n\n## Commit\n")
            append("Commit: ${commitHash.hash}\n")
            append("Parent: ${parentHash.hash}\n\n")
            append("### Commit Message\n")
            append(input.commitMetadata.message)
            append("\n\n### Commit Diff\n```diff\n")
            append(fullDiff)
            append("\n```\n")
        }

        log.info { "Triage ${input.benchmarkId}: classifying ${commitHash.abbreviated} with ${ctx.config.model.triageModel}" }
        val response = try {
            llmClient.classifyCommit(
                prompt = prompt,
                model = ctx.config.model.triageModel,
                temperature = ctx.sampleTemperature
            )
        } catch (e: SingleShotLlmException) {
            return StageResult.TransientFailure(
                error = SingleShotLlmException(
                    "LLM triage classification failed for ${commitHash.abbreviated}: ${e.message}",
                    e.cause,
                    e.transcript,
                    e.reason
                ),
                stageTurnsTaken = e.transcript.count { it.eventType == AgentEventType.STEP_START },
                transcript = e.transcript
            )
        } catch (t: Throwable) {
            return StageResult.TransientFailure(
                error = RuntimeException("LLM triage classification failed for ${commitHash.abbreviated}: ${t.message}", t),
                stageTurnsTaken = 1,
                transcript = emptyList()
            )
        }

        val classification = response.value
        val transcript = response.transcript
        ctx.database.updateCommit(
            CommitTriageUpdate(
                commitHash = commitHash.hash,
                classification = classification.category,
                bugfixScore = classification.bugfixScore
            )
        )

        val threshold = ctx.config.triage.bugfixThreshold
        val acceptedAsBugfix = classification.isBugfix && classification.bugfixScore >= threshold
        val extraMeta = mapOf(
            "bugfix_score" to JsonPrimitive(classification.bugfixScore),
            "llm_classification" to JsonPrimitive(classification.category.name.lowercase()),
            "llm_rationale" to JsonPrimitive(classification.rationale.take(1000))
        )

        if (!acceptedAsBugfix) {
            return TriageVerifier.discard(
                ctx = ctx,
                benchmarkId = input.benchmarkId,
                commitHash = commitHash,
                parentHash = parentHash,
                cacheKey = key,
                reason = DiscardReason.NOT_BUGFIX,
                details = "Classified as non-bugfix (isBugfix=${classification.isBugfix}, score=${classification.bugfixScore}, threshold=$threshold): ${classification.rationale}",
                sourceProvenance = TriageMode.CRAWLER,
                extraMetadata = extraMeta,
                transcript = transcript
            )
        }

        val regressionTest = classification.regressionTest
            ?: return TriageVerifier.discard(
                ctx = ctx,
                benchmarkId = input.benchmarkId,
                commitHash = commitHash,
                parentHash = parentHash,
                cacheKey = key,
                reason = DiscardReason.NO_TEST_FOUND,
                details = "LLM classified commit as bugfix (score=${classification.bugfixScore}) but identified no regressionTest: ${classification.rationale}",
                sourceProvenance = TriageMode.CRAWLER,
                extraMetadata = extraMeta,
                transcript = transcript
            )

        return TriageVerifier.verify(
            ctx = ctx,
            buildRunner = buildRunner,
            req = TriageVerifier.VerificationRequest(
                benchmarkId = input.benchmarkId,
                worktree = input.worktree,
                regressionTest = regressionTest,
                testPatchFile = null,
                cacheKey = key,
                sourceProvenance = TriageMode.CRAWLER,
                sampleIndex = ctx.sampleIndex,
                sampleTemperature = ctx.sampleTemperature,
                extraMetadata = extraMeta,
                transcript = transcript
            )
        )
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): VerifiedSample? =
        TriageVerifier.restoreCached(bundleDir, manifest)

    companion object {
        const val MAX_DIFF_LINES: Int = 20_000
    }
}
