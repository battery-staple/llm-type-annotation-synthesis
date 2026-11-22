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

    override suspend fun execute(ctx: RunContext, input: ManifestTriageInput): StageResult<VerifiedSample> {
        val repoAnchor = input.worktree.repoDir
        val triageRoot = ctx.worktreeDir.parentFile.resolve("triage_${ctx.runId}")
        val c1Sandbox = triageRoot.resolve("c1")
        val cSandbox = triageRoot.resolve("c")

        // a. Allocate disposable sandboxes (previous contents deleted first).
        FileOps.deleteRecursively(c1Sandbox)
        FileOps.deleteRecursively(cSandbox)
        log.info { "Triage ${input.benchmarkId}: materializing C-1 sandbox for ${input.regressionTest}" }
        try {
            Git.addWorktree(repoAnchor, c1Sandbox, input.worktree.parentHash.hash)
        } catch (t: Throwable) {
            return discardWithMetadata(ctx, input, null, DiscardReason.FIXED_COMMIT_UNAVAILABLE, "discarded_fixed_commit_unavailable", "fixed_commit_unavailable", "Cannot materialize C-1 sandbox: ${t.message}")
        }

        try {
            // b. Determine the test patch bytes.
            val patchBytes: ByteArray = if (input.testPatchFile != null) {
                input.testPatchFile.readBytes()
            } else {
                val generated = runCatching {
                    // Mirror SourceRoots path matching: a bare test root (CMSX
                    // `test`) is repository-root anchored, while multi-segment
                    // roots may appear under any module. A broad
                    // `**/<root>/**` glob would also match production
                    // directories named `test` (for example CMSX
                    // `src/cms/www/action/actions/api/test/`).
                    val specs = pipeline.validators.SourceRoots.gitPathspecs(ctx.testRoots)
                    Git.diffBetween(
                        repoAnchor,
                        input.worktree.parentHash.hash,
                        input.worktree.commitHash.hash,
                        listOf("--") + specs
                    )
                }.getOrElse {
                    return discardWithMetadata(ctx, input, null, DiscardReason.NO_TEST_FOUND, "discarded_no_test", "no_test_found", "Cannot derive test patch from ${input.worktree.parentHash}..${input.worktree.commitHash}: ${it.message}")
                }
                generated.toByteArray(Charsets.UTF_8)
            }
            if (patchBytes.isEmpty() || String(patchBytes, Charsets.UTF_8).isBlank()) {
                return discardWithMetadata(ctx, input, null, DiscardReason.NO_TEST_FOUND, "discarded_no_test", "no_test_found", "Test patch is empty; no regression test found")
            }
            val patchText = String(patchBytes, Charsets.UTF_8)

            // c. Audit every patched path: only test-root paths allowed.
            val audit = auditTestPatch(patchText, ctx.testRoots)
            if (!audit.valid) {
                log.warn { "Triage ${input.benchmarkId}: invalid test patch: ${audit.reason}" }
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.INVALID_TEST_PATCH, "discarded_invalid_test_patch", "invalid_test_patch", audit.reason, pathAudit = "rejected: ${audit.reason}")
            }

            // d. Compile the unpatched C-1 sandbox first.
            log.info { "Triage ${input.benchmarkId}: compiling unpatched C-1 sandbox" }
            val c1Build = buildRunner.compile(c1Sandbox)
            if (!c1Build.success) {
                ctx.database.updateCommit(
                    CommitTriageUpdate(
                        commitHash = input.worktree.commitHash.hash,
                        triageStatus = "discarded_build_broken",
                        discardReason = DiscardReason.BUILD_BROKEN_UNPATCHED.name.lowercase(),
                        compilerErrorLog = c1Build.output.take(8000)
                    )
                )
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.BUILD_BROKEN_UNPATCHED, null, null, "Unpatched C-1 does not compile: ${c1Build.output.take(2000)}", c1BuildStatus = "build_broken_unpatched")
            }

            // e. Apply the test patch.
            val patchFile = c1Sandbox.resolve("triage_test.patch").also { it.writeBytes(patchBytes) }
            val applied = Git.applyPatch(c1Sandbox, patchFile)
            patchFile.delete()
            if (applied.exitCode != 0) {
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.INVALID_TEST_PATCH, "discarded_invalid_test_patch", "invalid_test_patch", "Test patch does not apply to C-1: ${applied.output.take(2000)}")
            }

            // f. Run the regression test on C-1; it must fail at runtime.
            log.info { "Triage ${input.benchmarkId}: running T_reg on C-1 (expecting runtime failure)" }
            val c1Result = buildRunner.runTests(c1Sandbox, testFilter = input.regressionTest)
            recordTriageTestTelemetry(ctx, input, c1Result, suiteKind = "regression_test", xmlPath = null)
            if (c1Result.compileFailed) {
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.TEST_COMPILATION_FAILURE, "discarded_non_compiling", "test_compilation_failure", "Test patch does not compile on C-1: ${c1Result.output.take(2000)}", c1BuildStatus = "ok", c1TestStatus = "test_compilation_failure")
            }
            if (c1Result.testsFound == 0) {
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.NO_TEST_FOUND, "discarded_no_test", "no_test_found", "No tests found for ${input.regressionTest} on C-1", c1BuildStatus = "ok", c1TestStatus = "no_test_found")
            }
            val c1Failed = c1Result.outcomes.any { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            val c1Passed = c1Result.outcomes.any { it.status == TestStatus.PASSED }
            if (!c1Failed) {
                val status = if (c1Passed) "test_passes_unpatched" else "skipped_only"
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.TEST_PASSES_UNPATCHED, "discarded_passes_unpatched", "test_passes_unpatched", "T_reg does not fail on C-1 (status=$status)", c1BuildStatus = "ok", c1TestStatus = status)
            }

            // g. Materialize C and require T_reg to pass there.
            log.info { "Triage ${input.benchmarkId}: materializing C sandbox; T_reg must pass" }
            try {
                Git.addWorktree(repoAnchor, cSandbox, input.worktree.commitHash.hash)
            } catch (t: Throwable) {
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.FIXED_COMMIT_UNAVAILABLE, "discarded_fixed_commit_unavailable", "fixed_commit_unavailable", "Cannot materialize C sandbox: ${t.message}", c1BuildStatus = "ok", c1TestStatus = "failed_as_expected")
            }
            val cResult = buildRunner.runTests(cSandbox, testFilter = input.regressionTest)
            recordTriageTestTelemetry(ctx, input, cResult, suiteKind = "regression_test", xmlPath = null)
            val cPassed = cResult.outcomes.any { it.status == TestStatus.PASSED }
            val cFailed = cResult.outcomes.any { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            val cStatus = when {
                cResult.compileFailed -> "compile_failed"
                cResult.testsFound == 0 -> "no_test_found"
                cResult.exitCode != 0 -> "gradle_failed"
                cPassed && !cFailed -> "passed"
                else -> "failed"
            }
            if (!(cPassed && !cFailed && cResult.exitCode == 0)) {
                return discardWithMetadata(ctx, input, patchBytes, DiscardReason.REGRESSION_DOES_NOT_PASS_ON_FIXED_COMMIT, "discarded_regression_not_passing_on_fixed_commit", "regression_does_not_pass_on_fixed_commit", "T_reg does not pass on C (status=$cStatus)", c1BuildStatus = "ok", c1TestStatus = "failed_as_expected", cTestStatus = cStatus)
            }

            // i. Publish artifacts and mark the commit verified.
            val cas = CasEngine(ctx.casDir)
            val stagingDir = cas.createStagingDir(stageName)
            val writer = StageArtifactWriter(
                stageName = stageName,
                bundleDir = stagingDir,
                upstreamKeys = emptyList(),
                // Deterministic bundle: not owned by any one sample, so it must
                // not claim a sample's identity when reused across seeds.
                sampleIndex = 0,
                sampleTemperature = 0.0
            )
            writer.writeBytes(ArtifactNames.TEST_PATCH, patchBytes)
            val patchFileRef = stagingDir.resolve(ArtifactNames.TEST_PATCH)
            writer.writeJson(
                ArtifactNames.TRIAGE_METADATA,
                buildJsonObject {
                    put("benchmark_id", JsonPrimitive(input.benchmarkId.canonical))
                    put("c1_commit", JsonPrimitive(input.worktree.parentHash.hash))
                    put("c_commit", JsonPrimitive(input.worktree.commitHash.hash))
                    put("test_patch_path", JsonPrimitive(input.testPatchFile?.name ?: "generated"))
                    put("path_audit_result", JsonPrimitive("accepted"))
                    put("c1_build_status", JsonPrimitive("ok"))
                    put("c1_test_status", JsonPrimitive("failed_as_expected"))
                    put("c_test_status", JsonPrimitive("passed"))
                    put("regression_test_class", JsonPrimitive(input.regressionTest.testClass.value))
                    put("regression_test_method", JsonPrimitive(input.regressionTest.testMethod))
                    put("source_provenance", JsonPrimitive("manifest"))
                }
            )
            val key = computeCacheKey(ctx, input)
            val manifest = writer.buildManifest(key)
            ctx.database.updateCommit(
                CommitTriageUpdate(
                    commitHash = input.worktree.commitHash.hash,
                    triageStatus = "verified",
                    regressionTestsFound = 1,
                    testMethodsExtracted = 1,
                    regressionTestClass = input.regressionTest.testClass.value,
                    regressionTestMethod = input.regressionTest.testMethod,
                    testPatch = patchFileRef.absolutePath
                )
            )
            log.info { "Triage ${input.benchmarkId}: verified (T_reg fails on C-1, passes on C)" }
            return StageResult.Success(
                data = VerifiedSample(
                    benchmarkId = input.benchmarkId,
                    commitHash = input.worktree.commitHash,
                    parentHash = input.worktree.parentHash,
                    regressionTest = input.regressionTest,
                    testPatchFile = patchFileRef
                ),
                casKey = key,
                artifactsManifest = manifest,
                bundleDir = stagingDir
            )
        } finally {
            // h. Always remove both disposable sandboxes.
            runCatching { Git.removeWorktree(repoAnchor, c1Sandbox) }
            runCatching { Git.removeWorktree(repoAnchor, cSandbox) }
            log.info { "Triage ${input.benchmarkId}: sandboxes cleaned" }
        }
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): VerifiedSample? {
        return runCatching {
            val patchFile = bundleDir.resolve(ArtifactNames.TEST_PATCH)
            require(patchFile.isFile) { "missing ${ArtifactNames.TEST_PATCH}" }
            val metaFile = bundleDir.resolve(ArtifactNames.TRIAGE_METADATA)
            require(metaFile.isFile) { "missing ${ArtifactNames.TRIAGE_METADATA}" }
            val meta = PipelineJson.json.decodeFromString(
                kotlinx.serialization.json.JsonObject.serializer(),
                metaFile.readText()
            )
            fun field(name: String): String = meta.getValue(name).toString().trim('"')
            VerifiedSample(
                benchmarkId = BenchmarkId.parse(field("benchmark_id")),
                commitHash = CommitHash(field("c_commit")),
                parentHash = CommitHash(field("c1_commit")),
                regressionTest = TestCoordinate(
                    testClass = Fqcn(field("regression_test_class")),
                    testMethod = field("regression_test_method")
                ),
                testPatchFile = patchFile
            )
        }.getOrElse {
            log.warn { "Triage: restoreCached failed: ${it.message}" }
            null
        }
    }

    // --- Helpers -----------------------------------------------------------

    private suspend fun discardWithMetadata(
        ctx: RunContext,
        input: ManifestTriageInput,
        patchBytes: ByteArray?,
        reason: DiscardReason,
        triageStatus: String?,
        discardReason: String?,
        details: String,
        pathAudit: String = "accepted",
        c1BuildStatus: String? = null,
        c1TestStatus: String? = null,
        cTestStatus: String? = null
    ): StageResult.Discard {
        if (triageStatus != null) {
            ctx.database.updateCommit(
                CommitTriageUpdate(
                    commitHash = input.worktree.commitHash.hash,
                    triageStatus = triageStatus,
                    discardReason = discardReason
                )
            )
        }
        // Deterministic discards are cached too: the orchestrator publishes this
        // bundle with success=false and later reuses it as a cache hit.
        val stagingDir = CasEngine(ctx.casDir).createStagingDir(stageName)
        val writer = StageArtifactWriter(
            stageName = stageName,
            bundleDir = stagingDir,
            upstreamKeys = emptyList(),
            // Deterministic bundle: not owned by any one sample, so it must not
            // claim a sample's identity when reused across seeds.
            sampleIndex = 0,
            sampleTemperature = 0.0
        )
        if (patchBytes != null) runCatching { writer.writeBytes(ArtifactNames.TEST_PATCH, patchBytes) }
        writer.writeJson(
            ArtifactNames.TRIAGE_METADATA,
            buildJsonObject {
                put("benchmark_id", JsonPrimitive(input.benchmarkId.canonical))
                put("c1_commit", JsonPrimitive(input.worktree.parentHash.hash))
                put("c_commit", JsonPrimitive(input.worktree.commitHash.hash))
                put("test_patch_path", JsonPrimitive(input.testPatchFile?.name ?: "generated"))
                put("path_audit_result", JsonPrimitive(pathAudit))
                if (c1BuildStatus != null) put("c1_build_status", JsonPrimitive(c1BuildStatus))
                if (c1TestStatus != null) put("c1_test_status", JsonPrimitive(c1TestStatus))
                if (cTestStatus != null) put("c_test_status", JsonPrimitive(cTestStatus))
                put("regression_test_class", JsonPrimitive(input.regressionTest.testClass.value))
                put("regression_test_method", JsonPrimitive(input.regressionTest.testMethod))
                put("source_provenance", JsonPrimitive("manifest"))
                put("discard_reason", JsonPrimitive(reason.name.lowercase()))
            }
        )
        val key = computeCacheKey(ctx, input)
        val manifest = writer.buildManifest(key, success = false)
        log.info { "Triage ${input.benchmarkId}: discard $reason: $details" }
        return StageResult.Discard(
            reason = reason,
            details = details,
            artifactsManifest = manifest,
            bundleDir = stagingDir
        )
    }

    private suspend fun recordTriageTestTelemetry(
        ctx: RunContext,
        input: ManifestTriageInput,
        result: TestRunResult,
        suiteKind: String,
        xmlPath: String?
    ) {
        val (passed, failed, skipped) = countOutcomes(result.outcomes)
        val testRunId = TelemetryIds.id()
        val testRun = TestRunRecord(
            testRunId = testRunId,
            runId = ctx.runId,
            stepId = ctx.stepId,
            stage = "triage",
            suiteKind = suiteKind,
            attemptNumber = ctx.attemptNumber,
            scope = VerificationScope.REGRESSION_ONLY,
            exitCode = result.exitCode,
            compileFailed = result.compileFailed,
            testsFound = result.testsFound,
            passedCount = passed,
            failedCount = failed,
            skippedCount = skipped,
            success = result.success,
            xmlPath = xmlPath
        )
        val executions = result.outcomes.map { outcome ->
            TestExecutionRecord(
                executionId = TelemetryIds.id(),
                runId = ctx.runId,
                stepId = ctx.stepId,
                testRunId = testRunId,
                stage = "triage",
                turnIndex = null,
                testSuite = outcome.testClass.packageName.ifBlank { outcome.testClass.value },
                testClass = outcome.testClass.value,
                testMethod = outcome.testMethod,
                isRegressionTest = outcome.testClass == input.regressionTest.testClass &&
                    outcome.testMethod == input.regressionTest.testMethod,
                status = outcome.status,
                durationMs = outcome.durationMs,
                failureMessage = outcome.failureMessage
            )
        }
        ctx.database.recordStageTelemetry(TelemetryProjection(testRuns = listOf(testRun), testExecutions = executions))
    }

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
