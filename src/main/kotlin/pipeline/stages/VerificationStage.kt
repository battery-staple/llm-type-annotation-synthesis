package pipeline.stages

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import pipeline.cas.ArtifactNames
import pipeline.cas.CacheKeys
import pipeline.cas.CasEngine
import pipeline.cas.StageArtifactWriter
import pipeline.db.CheckerDiagnosticRecord
import pipeline.db.CheckerRunRecord
import pipeline.db.TelemetryProjection
import pipeline.db.TestExecutionRecord
import pipeline.db.TestRunRecord
import pipeline.ports.BuildRunner
import pipeline.ports.CheckerResult
import pipeline.ports.TestOutcome
import pipeline.ports.TestRunResult
import pipeline.ports.TestStatus
import pipeline.stages.IngestionStage.Companion.contentHashOrMissing
import pipeline.util.Git
import pipeline.util.PipelineJson

/**
 * Ground-truth verification.
 *
 * Reconstructs the repaired workspace (clean C-1 + `annotations.patch` +
 * `repair.patch`, each verified with `git apply --check` first), runs the
 * final Checker Framework pass, executes the regression test, optionally runs
 * the pre-existing suite, and assigns a terminal [EvalVerdict] using the exact
 * precedence from implementation-plan §3 (verification stage, step 5).
 *
 * This stage never updates `evaluation_runs.status`; run finalization is
 * owned by the orchestrator.
 */
class VerificationStage(private val buildRunner: BuildRunner) : PipelineStage<VerificationInput, VerificationResult> {
    override val stageName: StageName = StageName.VERIFICATION

    /** Checker and test replay are deterministic given the applied patches and scope. */
    override val isDeterministic: Boolean = true

    private val log = KotlinLogging.logger {}

    override fun computeCacheKey(ctx: RunContext, input: VerificationInput): CasKey {
        val stageInput = mapOf(
            "annotations_patch" to contentHashOrMissing(input.annotationsPatch),
            "repair_patch" to contentHashOrMissing(input.repair.repairPatch),
            "repair_final_log" to contentHashOrMissing(input.repair.finalCheckerErrorsLog),
            "repair_remaining_errors" to input.repair.remainingTypeErrors,
            "processor" to input.lattice.processor,
            "quals" to input.lattice.qualifierClassNames.map { it.value }.sorted(),
            "checker_category" to input.lattice.checkerCategory.name.lowercase(),
            "sample_commit" to input.sample.commitHash.hash,
            "sample_parent" to input.sample.parentHash.hash,
            "test_class" to input.sample.regressionTest.testClass.value,
            "test_method" to input.sample.regressionTest.testMethod,
            "test_patch" to contentHashOrMissing(input.sample.testPatchFile),
            "verification_scope" to input.verificationScope.name.lowercase()
        )
        val configSlice = mapOf(
            "verification_scope" to ctx.config.verification.scope.name.lowercase(),
            "verification_baseline" to ctx.config.verification.baseline,
            "guardrails_mode" to ctx.config.guardrails.mode.name.lowercase(),
            "checker_task" to ctx.build.checkerTask,
            "source_compatibility" to ctx.sourceCompatibility,
            "target_compatibility" to ctx.targetCompatibility
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

    override suspend fun execute(ctx: RunContext, input: VerificationInput): StageResult<VerificationResult> {
        val worktree = input.worktreeDir

        // a. Fresh clean C-1 baseline, then apply both patches in order.
        log.info { "Verification ${input.sample.benchmarkId}: resetting worktree to clean C-1" }
        Git.requireOk(Git.run(worktree, "reset", "--hard", timeoutSeconds = 300), "git reset --hard (verification)")
        Git.requireOk(
            Git.run(worktree, "clean", "-fdx", timeoutSeconds = 300),
            "git clean -fdx (verification)"
        )
        applyPatchOrHalt(worktree, input.annotationsPatch, "annotations.patch")
        log.info { "Verification: annotations.patch applied" }
        applyPatchOrHalt(worktree, input.repair.repairPatch, "repair.patch")
        log.info { "Verification: repair.patch applied" }

        val cas = CasEngine(ctx.casDir)
        val stagingDir = cas.createStagingDir(stageName)
        val writer = StageArtifactWriter(
            stageName = stageName,
            bundleDir = stagingDir,
            upstreamKeys = emptyList(),
            // Deterministic bundle: not owned by any one sample, so it must not
            // claim a sample's identity when reused across seeds.
            sampleIndex = 0,
            sampleTemperature = 0.0
        )

        // b. Final checker run (main sources only, via BuildRunner).
        log.info { "Verification: running final checker ${input.lattice.processor}" }
        val checker = buildRunner.runChecker(
            worktreeDir = worktree,
            checkerHome = File(ctx.config.environment.checkerFrameworkHome),
            customQualsJar = input.lattice.customQualsJar,
            processor = input.lattice.processor,
            quals = input.lattice.qualifierClassNames,
            checkerCategory = input.lattice.checkerCategory,
            guardrailsMode = ctx.config.guardrails.mode
        )
        val checkerLogArtifact = writer.copyIn(ArtifactNames.FINAL_CHECKER_ERRORS, checker.diagnosticsLog)
        val checkerRunId = TelemetryIds.id()
        recordCheckerTelemetry(ctx, input, checker, checkerRunId, checkerLogArtifact.absolutePath)

        val checkerMissing = !checker.checkerInvoked || !checker.success
        val remainingCheckerErrors: Int? = if (checkerMissing) null else checker.errorCount

        // c. Apply the test patch, then run T_reg.
        val testPatchOk = Git.checkPatch(worktree, input.sample.testPatchFile).exitCode == 0 &&
            Git.applyPatch(worktree, input.sample.testPatchFile).exitCode == 0
        if (!testPatchOk) {
            log.warn { "Verification: test patch does not apply; regression evidence missing" }
        }
        var regPassed = false
        var regEvidenceMissing = true
        var regResult: TestRunResult? = null
        var regXmlName: String? = null
        if (testPatchOk) {
            log.info { "Verification: running T_reg ${input.sample.regressionTest}" }
            val result = buildRunner.runTests(worktree, testFilter = input.sample.regressionTest, customQualsJar = input.lattice.customQualsJar)
            regResult = result
            val hasPassed = result.outcomes.any { it.status == TestStatus.PASSED }
            val hasFailure = result.outcomes.any { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            regPassed = hasPassed && !hasFailure
            // A nonzero exit is valid evidence only when the report contains
            // test failures; compile failures and timeouts remain incomplete.
            regEvidenceMissing = regressionEvidenceMissing(result)
            regXmlName = snapshotXml(writer, result, ArtifactNames.TEST_RESULTS_XML, input.sample.regressionTest.testClass.value)
            recordTestTelemetry(
                ctx,
                input,
                result,
                suiteKind = "regression_test",
                xmlArtifactName = regXmlName,
                isRegressionRun = true
            )
            log.info { "Verification: T_reg passed=$regPassed evidenceMissing=$regEvidenceMissing outcomes=${result.outcomes.size}" }
        } else {
            // Record the missing-evidence run shape with zero counts for forensics.
            regXmlName = writer.writeText(ArtifactNames.TEST_RESULTS_XML, "<missing>test patch did not apply; no regression run</missing>").name
        }

        // d. Pre-existing suite (full_suite only).
        var suitePassed: Boolean? = null
        var suiteReportMissing = false
        var suiteExecuted = 0
        var suiteFailed = 0
        var suiteXmlName: String? = null
        if (input.verificationScope == VerificationScope.FULL_SUITE) {
            log.info { "Verification: running pre-existing suite" }
            val suiteResult = buildRunner.runTests(worktree, testFilter = null, customQualsJar = input.lattice.customQualsJar)
            // JUnit XML reports the display name, not the Java method name. The
            // filtered regression run above tells us which display name belongs
            // to the regression test, so exclude that exact outcome from the
            // pre-existing-suite counts.
            val regressionDisplayName = regResult?.outcomes
                ?.firstOrNull { it.testClass == input.sample.regressionTest.testClass }
                ?.testMethod
            val filtered = suiteResult.outcomes.filterNot {
                it.testClass == input.sample.regressionTest.testClass &&
                    (
                        it.testMethod == input.sample.regressionTest.testMethod ||
                            (regressionDisplayName != null && it.testMethod == regressionDisplayName)
                        )
            }
            suiteExecuted = filtered.count { it.status != TestStatus.SKIPPED }
            suiteFailed = filtered.count { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            // Apply the same evidence rule as T_reg, using raw outcomes to
            // retain failures from the excluded regression test as evidence.
            suiteReportMissing = suiteEvidenceMissing(
                suiteResult,
                suiteExecuted,
                regressionTestFailed = !regPassed
            )
            suitePassed = if (suiteReportMissing || suiteExecuted == 0) null else suiteFailed == 0
            suiteXmlName = snapshotXml(writer, suiteResult, ArtifactNames.SUITE_RESULTS_XML, preferredClass = null)
            // Record telemetry on the filtered (non-regression) outcomes; T_reg
            // is excluded from the pre-existing-suite counts entirely.
            val removedRegression = suiteResult.outcomes.size - filtered.size
            val adjusted = suiteResult.copy(
                outcomes = filtered,
                testsFound = (suiteResult.testsFound - removedRegression).coerceAtLeast(0)
            )
            recordTestTelemetry(
                ctx,
                input,
                adjusted,
                suiteKind = "pre_existing_suite",
                xmlArtifactName = suiteXmlName,
                reportedFailedCount = suiteResult.outcomes.count {
                    it.status == TestStatus.FAILED || it.status == TestStatus.ERROR
                }
            )
            log.info { "Verification: suite executed=$suiteExecuted failed=$suiteFailed reportMissing=$suiteReportMissing" }
        }

        // e. Verdict precedence (exact order). A failed task is accepted as
        // test evidence only when its report records a test failure; compile
        // failures, timeouts, and unexplained nonzero exits remain missing.
        val verdict = decideVerdict(
            checkerMissing = checkerMissing,
            regEvidenceMissing = regEvidenceMissing,
            suiteReportMissing = suiteReportMissing,
            remainingCheckerErrors = remainingCheckerErrors,
            suiteFailed = suiteFailed,
            suiteExecuted = suiteExecuted,
            regPassed = regPassed,
            scope = input.verificationScope
        )
        log.info { "Verification ${input.sample.benchmarkId}: verdict=$verdict regPassed=$regPassed suitePassed=$suitePassed remainingErrors=$remainingCheckerErrors" }

        // f. Summary artifact, telemetry already recorded above.
        val regCounts = regResult?.let { countStatuses(it.outcomes) }
        val summary = buildJsonObject {
            put("verdict", JsonPrimitive(verdict.name.lowercase()))
            put("scope", JsonPrimitive(input.verificationScope.name.lowercase()))
            put("baseline", JsonPrimitive(ctx.config.verification.baseline))
            if (regEvidenceMissing) {
                put("regression_test_passed", JsonNull)
            } else {
                put("regression_test_passed", JsonPrimitive(regPassed))
            }
            put("regression_test_evidence_present", JsonPrimitive(!regEvidenceMissing))
            if (suitePassed == null) {
                put("original_tests_passed", JsonNull)
            } else {
                put("original_tests_passed", JsonPrimitive(suitePassed))
            }
            if (remainingCheckerErrors == null) {
                put("remaining_checker_errors", JsonNull)
            } else {
                put("remaining_checker_errors", JsonPrimitive(remainingCheckerErrors))
            }
            put("checker_run_id", JsonPrimitive(checkerRunId))
            put("checker_invoked", JsonPrimitive(checker.checkerInvoked))
            put("checker_success", JsonPrimitive(checker.success))
            put("checker_exit_code", JsonPrimitive(checker.exitCode))
            put("regression_tests_found", JsonPrimitive(regResult?.testsFound ?: 0))
            put("regression_passed_count", JsonPrimitive(regCounts?.first ?: 0))
            put("regression_failed_count", JsonPrimitive(regCounts?.second ?: 0))
            put("regression_skipped_count", JsonPrimitive(regCounts?.third ?: 0))
            put("suite_executed", JsonPrimitive(suiteExecuted))
            put("suite_failed", JsonPrimitive(suiteFailed))
            put("regression_xml", JsonPrimitive(regXmlName ?: ""))
            if (suiteXmlName != null) put("suite_xml", JsonPrimitive(suiteXmlName))
            put("diagnostics_log", JsonPrimitive(ArtifactNames.FINAL_CHECKER_ERRORS))
        }
        val summaryFile = writer.writeJson(ArtifactNames.VERIFICATION_SUMMARY, summary)
        val key = computeCacheKey(ctx, input)
        val manifest = writer.buildManifest(key)
        return StageResult.Success(
            data = VerificationResult(
                verdict = verdict,
                regressionTestPassed = if (regEvidenceMissing) null else regPassed,
                originalTestsPassed = suitePassed,
                remainingCheckerErrors = remainingCheckerErrors,
                testResultsXml = regXmlName?.let { stagingDir.resolve(it) },
                suiteResultsXml = suiteXmlName?.let { stagingDir.resolve(it) },
                verificationSummaryFile = summaryFile
            ),
            casKey = key,
            artifactsManifest = manifest,
            bundleDir = stagingDir
        )
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): VerificationResult? {
        return runCatching {
            val summaryFile = bundleDir.resolve(ArtifactNames.VERIFICATION_SUMMARY)
            require(summaryFile.isFile) { "missing ${ArtifactNames.VERIFICATION_SUMMARY}" }
            val summary = PipelineJson.json.decodeFromString(
                kotlinx.serialization.json.JsonObject.serializer(),
                summaryFile.readText()
            )
            fun str(name: String): String? = summary[name]?.toString()?.trim('"')
            fun bool(name: String): Boolean? = summary[name]
                ?.takeUnless { it == JsonNull }
                ?.let { it.toString() == "true" }
            val verdict = EvalVerdict.fromStoredName(str("verdict"))
                ?: return null
            val testXml = bundleDir.resolve(ArtifactNames.TEST_RESULTS_XML).takeIf { it.isFile }
            val suiteXml = bundleDir.resolve(ArtifactNames.SUITE_RESULTS_XML).takeIf { it.isFile }
            VerificationResult(
                verdict = verdict,
                regressionTestPassed = bool("regression_test_passed"),
                originalTestsPassed = summary["original_tests_passed"]?.let {
                    if (it.toString() == "null") null else it.toString().toBooleanStrictOrNull()
                },
                remainingCheckerErrors = summary["remaining_checker_errors"]?.let {
                    if (it.toString() == "null") null else it.toString().toIntOrNull()
                },
                testResultsXml = testXml,
                suiteResultsXml = suiteXml,
                verificationSummaryFile = summaryFile
            )
        }.getOrElse {
            log.warn { "Verification: restoreCached failed: ${it.message}" }
            null
        }
    }

    // --- Helpers -----------------------------------------------------------

    private fun applyPatchOrHalt(worktree: File, patch: File, name: String) {
        require(patch.isFile) { "$name does not exist: ${patch.absolutePath}" }
        if (patch.length() == 0L || patch.readText().isBlank()) {
            log.info { "Verification: $name is empty; nothing to apply" }
            return
        }
        val check = Git.checkPatch(worktree, patch)
        if (check.exitCode != 0) {
            throw StageHaltException("$name does not apply cleanly to its declared baseline: ${check.output.take(2000)}")
        }
        val applied = Git.applyPatch(worktree, patch)
        if (applied.exitCode != 0) {
            throw StageHaltException("$name failed to apply: ${applied.output.take(2000)}")
        }
    }

    /**
     * Copies the most relevant JUnit XML into the CAS bundle immediately, so a
     * later test task cannot overwrite the report directory first. With
     * several reports, prefers one mentioning the regression class; the suite
     * XML prefers the largest report. With none, writes a wrapper file so the
     * artifact name still exists and the missing evidence is explicit.
     */
    private fun snapshotXml(
        writer: StageArtifactWriter,
        result: TestRunResult,
        artifactName: String,
        preferredClass: String?
    ): String {
        val candidates = result.xmlPaths.filter { it.isFile }
        val chosen = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first()
            preferredClass != null -> candidates.firstOrNull {
                it.name.contains(preferredClass.substringAfterLast('.')) ||
                    runCatching { it.readText().contains(preferredClass) }.getOrDefault(false)
            } ?: candidates.first()
            else -> candidates.maxByOrNull { it.length() }
        }
        return if (chosen != null) {
            writer.copyIn(artifactName, chosen).name
        } else {
            writer.writeText(artifactName, "<missing>no JUnit XML produced by this invocation</missing>").name
        }
    }

    private suspend fun recordCheckerTelemetry(
        ctx: RunContext,
        input: VerificationInput,
        checker: CheckerResult,
        checkerRunId: String,
        diagnosticsLogPath: String
    ) {
        val run = CheckerRunRecord(
            checkerRunId = checkerRunId,
            runId = ctx.runId,
            stepId = ctx.stepId,
            stage = "verification_final",
            attemptNumber = ctx.attemptNumber,
            processor = input.lattice.processor,
            checkerCategory = input.lattice.checkerCategory,
            quals = input.lattice.qualifierClassNames.joinToString(",") { it.value },
            exitCode = checker.exitCode,
            success = checker.success,
            checkerInvoked = checker.checkerInvoked,
            errorCount = checker.errorCount,
            warningCount = checker.warningCount,
            diagnosticsLogPath = diagnosticsLogPath
        )
        val checkerName = input.lattice.processor.substringAfterLast('.')
        val diagnostics = checker.diagnostics.map { diagnostic ->
            CheckerDiagnosticRecord(
                diagnosticId = TelemetryIds.id(),
                runId = ctx.runId,
                stepId = ctx.stepId,
                checkerRunId = checkerRunId,
                stage = "verification_final",
                turnIndex = null,
                severity = diagnostic.severity,
                checkerName = checkerName,
                errorCategory = diagnostic.errorCategory,
                filePath = diagnostic.filePath,
                lineNumber = diagnostic.lineNumber,
                enclosingMethod = null,
                message = diagnostic.message,
                localizationTier = null,
                isBaselineError = false
            )
        }
        ctx.database.recordStageTelemetry(TelemetryProjection(checkerRuns = listOf(run), checkerDiagnostics = diagnostics))
    }

    private suspend fun recordTestTelemetry(
        ctx: RunContext,
        input: VerificationInput,
        result: TestRunResult,
        suiteKind: String,
        xmlArtifactName: String?,
        isRegressionRun: Boolean = false,
        reportedFailedCount: Int? = null
    ) {
        val outcomes = result.outcomes
        val (passed, failed, skipped) = countStatuses(outcomes)
        val testRunId = TelemetryIds.id()
        val testRun = TestRunRecord(
            testRunId = testRunId,
            runId = ctx.runId,
            stepId = ctx.stepId,
            stage = "verification",
            suiteKind = suiteKind,
            attemptNumber = ctx.attemptNumber,
            scope = input.verificationScope,
            exitCode = result.exitCode,
            compileFailed = result.compileFailed,
            testsFound = result.testsFound,
            passedCount = passed,
            failedCount = failed,
            skippedCount = skipped,
            success = result.success,
            xmlPath = xmlArtifactName,
            timedOut = result.timedOut,
            reportedFailedCount = reportedFailedCount ?: failed
        )
        val executions = outcomes.map { outcome ->
            TestExecutionRecord(
                executionId = TelemetryIds.id(),
                runId = ctx.runId,
                stepId = ctx.stepId,
                testRunId = testRunId,
                stage = "verification",
                turnIndex = null,
                testSuite = outcome.testClass.packageName.ifBlank { outcome.testClass.value },
                testClass = outcome.testClass.value,
                testMethod = outcome.testMethod,
                isRegressionTest = isRegressionRun ||
                    (
                        outcome.testClass == input.sample.regressionTest.testClass &&
                            outcome.testMethod == input.sample.regressionTest.testMethod
                        ),
                status = outcome.status,
                durationMs = outcome.durationMs,
                failureMessage = outcome.failureMessage
            )
        }
        ctx.database.recordStageTelemetry(TelemetryProjection(testRuns = listOf(testRun), testExecutions = executions))
    }

    companion object {
        fun countStatuses(outcomes: List<TestOutcome>): Triple<Int, Int, Int> {
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

        /**
         * Evidence means a parseable JUnit report with at least one executed
         * (non-skipped) test. A nonzero exit is acceptable only when the report
         * contains test failures; timeouts and compile failures remain incomplete.
         */
        fun regressionEvidenceMissing(result: TestRunResult): Boolean {
            if (result.xmlPaths.none { it.isFile }) return true
            if (result.testsFound == 0 || result.outcomes.isEmpty()) return true
            if (result.outcomes.none { it.status != TestStatus.SKIPPED }) return true
            if (result.compileFailed || result.timedOut) return true
            val hasReportedFailure = result.outcomes.any {
                it.status == TestStatus.FAILED || it.status == TestStatus.ERROR
            }
            return !result.success && !hasReportedFailure
        }

        /**
         * Suite evidence uses the filtered (non-regression) executed count so
         * the verdict and the persisted view agree: a suite run that only
         * contains the regression test has no pre-existing-suite evidence.
         */
        fun suiteEvidenceMissing(
            result: TestRunResult,
            filteredExecuted: Int,
            regressionTestFailed: Boolean = false
        ): Boolean {
            if (result.xmlPaths.none { it.isFile }) return true
            if (result.testsFound == 0) return true
            if (filteredExecuted == 0) return true
            if (result.compileFailed || result.timedOut) return true
            val hasReportedFailure = result.outcomes.any {
                it.status == TestStatus.FAILED || it.status == TestStatus.ERROR
            }
            return !result.success && !hasReportedFailure && !regressionTestFailed
        }

        /**
         * Terminal evaluation verdict. Lifecycle completion (orchestrator run
         * status) is distinct: callers set `success` when verification
         * executed, even when this returns a substantive failure verdict.
         * `verification_missing` is reserved for genuinely absent/unusable
         * evidence, never for assertion failures backed by valid reports.
         */
        fun decideVerdict(
            checkerMissing: Boolean,
            regEvidenceMissing: Boolean,
            suiteReportMissing: Boolean,
            remainingCheckerErrors: Int?,
            suiteFailed: Int,
            suiteExecuted: Int,
            regPassed: Boolean,
            scope: VerificationScope
        ): EvalVerdict = when {
            checkerMissing -> EvalVerdict.VERIFICATION_MISSING
            regEvidenceMissing -> EvalVerdict.VERIFICATION_MISSING
            scope == VerificationScope.FULL_SUITE && suiteReportMissing -> EvalVerdict.VERIFICATION_MISSING
            (remainingCheckerErrors ?: 0) > 0 -> EvalVerdict.FIXER_INCOMPLETE
            scope == VerificationScope.FULL_SUITE && suiteFailed > 0 -> EvalVerdict.SUITE_FAILURE
            scope == VerificationScope.REGRESSION_ONLY && regPassed -> EvalVerdict.PARTIAL_SUCCESS_REGRESSION_ONLY
            scope == VerificationScope.FULL_SUITE && regPassed &&
                suiteExecuted > 0 && suiteFailed == 0 -> EvalVerdict.FULL_SUCCESS
            !regPassed && (scope == VerificationScope.REGRESSION_ONLY || suiteFailed == 0) -> EvalVerdict.TYPE_SUCCESS_RUNTIME_FAIL
            else -> EvalVerdict.VERIFICATION_MISSING
        }
    }
}
