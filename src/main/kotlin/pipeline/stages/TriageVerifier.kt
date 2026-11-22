package pipeline.stages

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import pipeline.cas.ArtifactNames
import pipeline.cas.CasEngine
import pipeline.cas.StageArtifactWriter
import pipeline.db.CommitTriageUpdate
import pipeline.db.TelemetryProjection
import pipeline.db.TestExecutionRecord
import pipeline.db.TestRunRecord
import pipeline.ports.BuildRunner
import pipeline.ports.TestRunResult
import pipeline.ports.TestStatus
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.PipelineJson

/**
 * Shared deterministic C-1 / C verification and CAS artifact writer for both
 * [ManifestTriageStage] and [AutomatedTriageStage].
 */
internal object TriageVerifier {
    private val log = KotlinLogging.logger {}

    data class VerificationRequest(
        val benchmarkId: BenchmarkId,
        val worktree: IngestionWorktree,
        val regressionTest: TestCoordinate,
        val testPatchFile: File? = null,
        val cacheKey: CasKey,
        val sourceProvenance: TriageMode,
        val sampleIndex: Int,
        val sampleTemperature: Double,
        val extraMetadata: Map<String, JsonPrimitive> = emptyMap()
    )

    suspend fun verify(
        ctx: RunContext,
        buildRunner: BuildRunner,
        req: VerificationRequest
    ): StageResult<VerifiedSample> {
        val repoAnchor = req.worktree.repoDir
        val triageRoot = ctx.worktreeDir.parentFile.resolve("triage_${ctx.runId}")
        val c1Sandbox = triageRoot.resolve("c1")
        val cSandbox = triageRoot.resolve("c")

        FileOps.deleteRecursively(c1Sandbox)
        FileOps.deleteRecursively(cSandbox)
        log.info { "Triage ${req.benchmarkId}: materializing C-1 sandbox for ${req.regressionTest}" }
        try {
            Git.addWorktree(repoAnchor, c1Sandbox, req.worktree.parentHash.hash)
        } catch (t: Throwable) {
            return discardFromVerification(
                ctx, req, null, DiscardReason.FIXED_COMMIT_UNAVAILABLE,
                "Cannot materialize C-1 sandbox: ${t.message}"
            )
        }

        try {
            val patchBytes: ByteArray = if (req.testPatchFile != null) {
                req.testPatchFile.readBytes()
            } else {
                val generated = runCatching {
                    val specs = pipeline.validators.SourceRoots.gitPathspecs(ctx.testRoots)
                    Git.diffBetween(
                        repoAnchor,
                        req.worktree.parentHash.hash,
                        req.worktree.commitHash.hash,
                        listOf("--") + specs
                    )
                }.getOrElse {
                    return discardFromVerification(
                        ctx, req, null, DiscardReason.NO_TEST_FOUND,
                        "Cannot derive test patch from ${req.worktree.parentHash}..${req.worktree.commitHash}: ${it.message}"
                    )
                }
                generated.toByteArray(Charsets.UTF_8)
            }
            if (patchBytes.isEmpty() || String(patchBytes, Charsets.UTF_8).isBlank()) {
                return discardFromVerification(
                    ctx, req, null, DiscardReason.NO_TEST_FOUND,
                    "Test patch is empty; no regression test found"
                )
            }
            val patchText = String(patchBytes, Charsets.UTF_8)

            val audit = ManifestTriageStage.auditTestPatch(patchText, ctx.testRoots)
            if (!audit.valid) {
                log.warn { "Triage ${req.benchmarkId}: invalid test patch: ${audit.reason}" }
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.INVALID_TEST_PATCH,
                    audit.reason, pathAudit = "rejected: ${audit.reason}"
                )
            }

            log.info { "Triage ${req.benchmarkId}: compiling unpatched C-1 sandbox" }
            val c1Build = buildRunner.compile(c1Sandbox)
            if (!c1Build.success) {
                ctx.database.updateCommit(
                    CommitTriageUpdate(
                        commitHash = req.worktree.commitHash.hash,
                        triageStatus = "discarded_build_broken",
                        discardReason = DiscardReason.BUILD_BROKEN_UNPATCHED.wireName,
                        compilerErrorLog = c1Build.output.take(8000)
                    )
                )
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.BUILD_BROKEN_UNPATCHED,
                    "Unpatched C-1 does not compile: ${c1Build.output.take(2000)}",
                    c1BuildStatus = "build_broken_unpatched"
                )
            }

            val patchFile = c1Sandbox.resolve("triage_test.patch").also { it.writeBytes(patchBytes) }
            val applied = Git.applyPatch(c1Sandbox, patchFile)
            patchFile.delete()
            if (applied.exitCode != 0) {
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.INVALID_TEST_PATCH,
                    "Test patch does not apply to C-1: ${applied.output.take(2000)}"
                )
            }

            log.info { "Triage ${req.benchmarkId}: running T_reg on C-1 (expecting runtime failure)" }
            val c1Result = buildRunner.runTests(c1Sandbox, testFilter = req.regressionTest)
            recordTriageTestTelemetry(ctx, req, c1Result, suiteKind = "regression_test", xmlPath = null)
            if (c1Result.compileFailed) {
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.TEST_COMPILATION_FAILURE,
                    "Test patch does not compile on C-1: ${c1Result.output.take(2000)}",
                    c1BuildStatus = "ok", c1TestStatus = "test_compilation_failure"
                )
            }
            if (c1Result.testsFound == 0) {
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.NO_TEST_FOUND,
                    "No tests found for ${req.regressionTest} on C-1",
                    c1BuildStatus = "ok", c1TestStatus = "no_test_found"
                )
            }
            val c1Failed = c1Result.outcomes.any { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            val c1Passed = c1Result.outcomes.any { it.status == TestStatus.PASSED }
            if (!c1Failed) {
                val status = if (c1Passed) "test_passes_unpatched" else "skipped_only"
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.TEST_PASSES_UNPATCHED,
                    "T_reg does not fail on C-1 (status=$status)",
                    c1BuildStatus = "ok", c1TestStatus = status
                )
            }

            log.info { "Triage ${req.benchmarkId}: materializing C sandbox; T_reg must pass" }
            try {
                Git.addWorktree(repoAnchor, cSandbox, req.worktree.commitHash.hash)
            } catch (t: Throwable) {
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.FIXED_COMMIT_UNAVAILABLE,
                    "Cannot materialize C sandbox: ${t.message}",
                    c1BuildStatus = "ok", c1TestStatus = "failed_as_expected"
                )
            }
            val cResult = buildRunner.runTests(cSandbox, testFilter = req.regressionTest)
            recordTriageTestTelemetry(ctx, req, cResult, suiteKind = "regression_test", xmlPath = null)
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
                return discardFromVerification(
                    ctx, req, patchBytes, DiscardReason.REGRESSION_DOES_NOT_PASS_ON_FIXED_COMMIT,
                    "T_reg does not pass on C (status=$cStatus)",
                    c1BuildStatus = "ok", c1TestStatus = "failed_as_expected", cTestStatus = cStatus
                )
            }

            val cas = CasEngine(ctx.casDir)
            val stagingDir = cas.createStagingDir(StageName.TRIAGE)
            val writer = StageArtifactWriter(
                stageName = StageName.TRIAGE,
                bundleDir = stagingDir,
                upstreamKeys = emptyList(),
                sampleIndex = req.sampleIndex,
                sampleTemperature = req.sampleTemperature
            )
            writer.writeBytes(ArtifactNames.TEST_PATCH, patchBytes)
            val patchFileRef = stagingDir.resolve(ArtifactNames.TEST_PATCH)
            writer.writeJson(
                ArtifactNames.TRIAGE_METADATA,
                buildJsonObject {
                    put("benchmark_id", JsonPrimitive(req.benchmarkId.canonical))
                    put("c1_commit", JsonPrimitive(req.worktree.parentHash.hash))
                    put("c_commit", JsonPrimitive(req.worktree.commitHash.hash))
                    put("test_patch_path", JsonPrimitive(req.testPatchFile?.name ?: "generated"))
                    put("path_audit_result", JsonPrimitive("accepted"))
                    put("c1_build_status", JsonPrimitive("ok"))
                    put("c1_test_status", JsonPrimitive("failed_as_expected"))
                    put("c_test_status", JsonPrimitive("passed"))
                    put("regression_test_class", JsonPrimitive(req.regressionTest.testClass.value))
                    put("regression_test_method", JsonPrimitive(req.regressionTest.testMethod))
                    put("source_provenance", JsonPrimitive(req.sourceProvenance.name.lowercase()))
                    req.extraMetadata.forEach { (k, v) -> put(k, v) }
                }
            )
            val manifest = writer.buildManifest(req.cacheKey)
            ctx.database.updateCommit(
                CommitTriageUpdate(
                    commitHash = req.worktree.commitHash.hash,
                    triageStatus = "verified",
                    regressionTestsFound = 1,
                    testMethodsExtracted = 1,
                    regressionTestClass = req.regressionTest.testClass.value,
                    regressionTestMethod = req.regressionTest.testMethod,
                    testPatch = patchFileRef.absolutePath
                )
            )
            log.info { "Triage ${req.benchmarkId}: verified (T_reg fails on C-1, passes on C)" }
            return StageResult.Success(
                data = VerifiedSample(
                    benchmarkId = req.benchmarkId,
                    commitHash = req.worktree.commitHash,
                    parentHash = req.worktree.parentHash,
                    regressionTest = req.regressionTest,
                    testPatchFile = patchFileRef
                ),
                casKey = req.cacheKey,
                artifactsManifest = manifest,
                bundleDir = stagingDir
            )
        } finally {
            runCatching { Git.removeWorktree(repoAnchor, c1Sandbox) }
            runCatching { Git.removeWorktree(repoAnchor, cSandbox) }
            log.info { "Triage ${req.benchmarkId}: sandboxes cleaned" }
        }
    }

    private suspend fun discardFromVerification(
        ctx: RunContext,
        req: VerificationRequest,
        patchBytes: ByteArray?,
        reason: DiscardReason,
        details: String,
        pathAudit: String = "accepted",
        c1BuildStatus: String? = null,
        c1TestStatus: String? = null,
        cTestStatus: String? = null
    ): StageResult.Discard = discard(
        ctx = ctx,
        benchmarkId = req.benchmarkId,
        commitHash = req.worktree.commitHash,
        parentHash = req.worktree.parentHash,
        cacheKey = req.cacheKey,
        reason = reason,
        details = details,
        sourceProvenance = req.sourceProvenance,
        sampleIndex = req.sampleIndex,
        sampleTemperature = req.sampleTemperature,
        patchBytes = patchBytes,
        extraMetadata = buildMap {
            put("test_patch_path", JsonPrimitive(req.testPatchFile?.name ?: "generated"))
            put("path_audit_result", JsonPrimitive(pathAudit))
            if (c1BuildStatus != null) put("c1_build_status", JsonPrimitive(c1BuildStatus))
            if (c1TestStatus != null) put("c1_test_status", JsonPrimitive(c1TestStatus))
            if (cTestStatus != null) put("c_test_status", JsonPrimitive(cTestStatus))
            put("regression_test_class", JsonPrimitive(req.regressionTest.testClass.value))
            put("regression_test_method", JsonPrimitive(req.regressionTest.testMethod))
            putAll(req.extraMetadata)
        }
    )

    suspend fun discard(
        ctx: RunContext,
        benchmarkId: BenchmarkId,
        commitHash: CommitHash,
        parentHash: CommitHash,
        cacheKey: CasKey,
        reason: DiscardReason,
        details: String,
        sourceProvenance: TriageMode,
        sampleIndex: Int = ctx.sampleIndex,
        sampleTemperature: Double = ctx.sampleTemperature,
        patchBytes: ByteArray? = null,
        extraMetadata: Map<String, JsonPrimitive> = emptyMap()
    ): StageResult.Discard {
        if (reason.dbTriageStatus != null) {
            ctx.database.updateCommit(
                CommitTriageUpdate(
                    commitHash = commitHash.hash,
                    triageStatus = reason.dbTriageStatus,
                    discardReason = reason.wireName
                )
            )
        }
        val stagingDir = CasEngine(ctx.casDir).createStagingDir(StageName.TRIAGE)
        val writer = StageArtifactWriter(
            stageName = StageName.TRIAGE,
            bundleDir = stagingDir,
            upstreamKeys = emptyList(),
            sampleIndex = sampleIndex,
            sampleTemperature = sampleTemperature
        )
        if (patchBytes != null) {
            writer.writeBytes(ArtifactNames.TEST_PATCH, patchBytes)
        }
        writer.writeJson(
            ArtifactNames.TRIAGE_METADATA,
            buildJsonObject {
                put("benchmark_id", JsonPrimitive(benchmarkId.canonical))
                put("c1_commit", JsonPrimitive(parentHash.hash))
                put("c_commit", JsonPrimitive(commitHash.hash))
                put("source_provenance", JsonPrimitive(sourceProvenance.name.lowercase()))
                put("discard_reason", JsonPrimitive(reason.wireName))
                put("details", JsonPrimitive(details.take(2000)))
                extraMetadata.forEach { (k, v) -> put(k, v) }
            }
        )
        val manifest = writer.buildManifest(cacheKey, success = false)
        log.info { "Triage $benchmarkId: discard $reason: $details" }
        return StageResult.Discard(
            reason = reason,
            details = details,
            artifactsManifest = manifest,
            bundleDir = stagingDir
        )
    }

    fun restoreCached(bundleDir: File, @Suppress("UNUSED_PARAMETER") manifest: ArtifactsManifest): VerifiedSample? {
        return runCatching {
            val patchFile = bundleDir.resolve(ArtifactNames.TEST_PATCH)
            require(patchFile.isFile) { "missing ${ArtifactNames.TEST_PATCH}" }
            val metaFile = bundleDir.resolve(ArtifactNames.TRIAGE_METADATA)
            require(metaFile.isFile) { "missing ${ArtifactNames.TRIAGE_METADATA}" }
            val meta = PipelineJson.json.decodeFromString(JsonObject.serializer(), metaFile.readText())
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

    private suspend fun recordTriageTestTelemetry(
        ctx: RunContext,
        req: VerificationRequest,
        result: TestRunResult,
        suiteKind: String,
        xmlPath: String?
    ) {
        val (passed, failed, skipped) = ManifestTriageStage.countOutcomes(result.outcomes)
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
            xmlPath = xmlPath,
            timedOut = result.timedOut,
            reportedFailedCount = failed
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
                isRegressionTest = outcome.testClass == req.regressionTest.testClass &&
                    outcome.testMethod == req.regressionTest.testMethod,
                status = outcome.status,
                durationMs = outcome.durationMs,
                failureMessage = outcome.failureMessage
            )
        }
        ctx.database.recordStageTelemetry(TelemetryProjection(testRuns = listOf(testRun), testExecutions = executions))
    }
}
