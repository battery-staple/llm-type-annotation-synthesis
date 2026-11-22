package pipeline.stages

import java.io.File
import mu.KotlinLogging
import pipeline.cas.CacheKeys

/**
 * Automated commit-crawler triage (deferred).
 *
 * Design plan §7.4 reserves a future crawler mode that scans repository
 * history with a single-shot LLM classifier ([pipeline.ports.SingleShotLlmClient])
 * to discover bugfix commits and extract regression tests automatically.
 * The MVP runs manifest triage only ([ManifestTriageStage]), so this stage is
 * a clearly documented stub: it is never wired into the MVP orchestrator,
 * its [execute] always throws, and [restoreCached] always misses.
 */
class AutomatedTriageStage : PipelineStage<BenchmarkEntry, VerifiedSample> {
    override val stageName: StageName = StageName.TRIAGE

    /** LLM classifier output is sample-dependent once this stage is implemented. */
    override val isDeterministic: Boolean = false

    private val log = KotlinLogging.logger {}

    override fun computeCacheKey(ctx: RunContext, input: BenchmarkEntry): CasKey {
        val stageInput = mapOf(
            "benchmark_id" to input.benchmarkId.canonical,
            "commit" to input.commitHash.hash,
            "parent" to input.parentHash.hash,
            "test_class" to input.regressionTest.testClass.value,
            "test_method" to input.regressionTest.testMethod,
            "mode" to "crawler-deferred"
        )
        val configSlice = mapOf(
            "triage_mode" to ctx.config.triage.mode,
            "triage_model" to ctx.config.model.triageModel
        )
        return CacheKeys.compute(
            ctx = ctx,
            stage = stageName,
            stageInput = stageInput,
            upstreamKeys = emptyList(),
            configSlice = configSlice,
            promptTemplate = null,
            includeSampleIdentity = !isDeterministic
        )
    }

    override suspend fun execute(ctx: RunContext, input: BenchmarkEntry): StageResult<VerifiedSample> {
        log.warn { "Automated crawler triage is deferred (design plan §7.4); refusing benchmark ${input.benchmarkId}" }
        throw UnsupportedOperationException("Automated crawler triage is deferred (design plan §7.4)")
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): VerifiedSample? = null
}
