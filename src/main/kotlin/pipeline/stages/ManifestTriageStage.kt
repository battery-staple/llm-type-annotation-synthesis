package pipeline.stages

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import pipeline.cas.ArtifactNames
import pipeline.cas.CacheKeys
import pipeline.cas.CasEngine
import pipeline.cas.StageArtifactWriter
import pipeline.db.CommitTriageUpdate
import pipeline.db.TelemetryProjection
import pipeline.db.TestExecutionRecord
import pipeline.db.TestRunRecord
import pipeline.ports.BuildRunner
import pipeline.ports.TestOutcome
import pipeline.ports.TestRunResult
import pipeline.ports.TestStatus
import pipeline.stages.IngestionStage.Companion.contentHashOrMissing
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson

/**
 * Curated benchmark verification and triage (manifest mode, MVP).
 *
 * Materializes two disposable sandboxes (clean C-1 and fixed C), audits the
 * test patch so it only touches the benchmark's configured test-root paths,
 * requires the regression test to fail at runtime on C-1 and pass on C, then
 * emits a [VerifiedSample] plus `triage_metadata.json`.
 *
 * Patch auditing reuses the shared [pipeline.validators.SourceRoots] rules and
 * mirrors implementation-plan §4.2: absolute paths, `..` traversal, and any
 * path outside the configured test roots are rejected. The orchestrator's
 * `FilesystemGuard.auditPatchPaths` performs the same path extraction for
 * staged agent diffs.
 *
 * Both sandboxes are removed in a `finally` block so no T_reg classes, JUnit
 * XML, or Gradle reports can leak into synthesis, application, or repair.
 */
class ManifestTriageStage(private val buildRunner: BuildRunner) : PipelineStage<ManifestTriageInput, VerifiedSample> {
    override val stageName: StageName = StageName.TRIAGE

    /** Patch audit and C-1/C test replay are deterministic; no agent or sampling. */
    override val isDeterministic: Boolean = true

    private val log = KotlinLogging.logger {}

    override fun computeCacheKey(ctx: RunContext, input: ManifestTriageInput): CasKey {
        val patchHash = if (input.testPatchFile != null) contentHashOrMissing(input.testPatchFile) else "generated"
        val stageInput = mapOf(
            "benchmark_id" to input.benchmarkId.canonical,
            "commit" to input.worktree.commitHash.hash,
            "parent" to input.worktree.parentHash.hash,
            "test_class" to input.regressionTest.testClass.value,
            "test_method" to input.regressionTest.testMethod,
            "test_patch" to patchHash
        )
        val configSlice = mapOf(
            "verification_test_task" to ctx.build.testTask,
            "verification_scope" to ctx.config.verification.scope.name.lowercase(),
            "production_roots" to ctx.productionRoots.sorted(),
            "test_roots" to ctx.testRoots.sorted()
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

    override suspend fun execute(ctx: RunContext, input: ManifestTriageInput): StageResult<VerifiedSample> =
        TriageVerifier.verify(
            ctx = ctx,
            buildRunner = buildRunner,
            req = TriageVerifier.VerificationRequest(
                benchmarkId = input.benchmarkId,
                worktree = input.worktree,
                regressionTest = input.regressionTest,
                testPatchFile = input.testPatchFile,
                cacheKey = computeCacheKey(ctx, input),
                sourceProvenance = TriageMode.MANIFEST,
                sampleIndex = 0,
                sampleTemperature = 0.0
            )
        )

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): VerifiedSample? =
        TriageVerifier.restoreCached(bundleDir, manifest)

    data class PatchAudit(val valid: Boolean, val reason: String)

    companion object {
        /**
         * Legacy entry point: audits against the default `src/test` root,
         * exactly like a benchmark that omits the roots configuration.
         */
        fun auditTestPatch(patchText: String): PatchAudit =
            auditTestPatch(patchText, pipeline.validators.SourceRoots.DEFAULT_TEST_ROOTS)

        /**
         * Every touched path must live under a configured test root; absolute
         * paths, `..` traversal, and non-test paths are rejected. Test roots
         * are repository-root anchored for single-segment roots (CMSX `test`),
         * so production directories named `test` are not accepted.
         */
        fun auditTestPatch(
            patchText: String,
            testRoots: List<String>
        ): PatchAudit {
            val paths = extractPatchedPaths(patchText)
            if (paths.isEmpty()) return PatchAudit(false, "test patch touches no files")
            paths.forEach { path ->
                if (path.startsWith("/")) return PatchAudit(false, "absolute path rejected: $path")
                if (path.split('/').any { it == ".." }) return PatchAudit(false, "path traversal rejected: $path")
                if (!pipeline.validators.SourceRoots.isTestDir(path, testRoots)) {
                    return PatchAudit(
                        false,
                        "non-test path rejected (must match test roots ${pipeline.validators.SourceRoots.display(testRoots)}): $path"
                    )
                }
            }
            return PatchAudit(true, "accepted (${paths.size} test path(s))")
        }

        /** Extracts candidate touched paths from unified-diff headers. */
        fun extractPatchedPaths(patchText: String): List<String> {
            val paths = mutableListOf<String>()
            patchText.lineSequence().forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.startsWith("diff --git ") -> {
                        val parts = line.removePrefix("diff --git ").split(' ').filter { it.isNotEmpty() }
                        if (parts.size >= 2) {
                            val a = parts[parts.size - 2].removePrefix("a/")
                            val b = parts[parts.size - 1].removePrefix("b/")
                            paths += a
                            if (b != a) paths += b
                        }
                    }
                    line.startsWith("--- ") -> {
                        val p = line.removePrefix("--- ").split(Regex("\\s")).firstOrNull().orEmpty()
                        if (p.isNotEmpty() && p != "/dev/null") paths += p.removePrefix("a/")
                    }
                    line.startsWith("+++ ") -> {
                        val p = line.removePrefix("+++ ").split(Regex("\\s")).firstOrNull().orEmpty()
                        if (p.isNotEmpty() && p != "/dev/null") paths += p.removePrefix("b/")
                    }
                    line.startsWith("rename from ") -> paths += line.removePrefix("rename from ").trim()
                    line.startsWith("rename to ") -> paths += line.removePrefix("rename to ").trim()
                }
            }
            return paths.filter { it.isNotEmpty() && it != "/dev/null" }.distinct()
        }

        fun countOutcomes(outcomes: List<TestOutcome>): Triple<Int, Int, Int> {
            var passed = 0
            var failed = 0
            var skipped = 0
            outcomes.forEach {
                when (it.status) {
                    TestStatus.PASSED -> passed++
                    TestStatus.FAILED, TestStatus.ERROR -> failed++
                    TestStatus.SKIPPED -> skipped++
                }
            }
            return Triple(passed, failed, skipped)
        }

        /** Content hash helper (paths must never enter cache keys). */
        fun patchHash(bytes: ByteArray): String = Hashing.sha256Bytes(bytes)
    }
}
