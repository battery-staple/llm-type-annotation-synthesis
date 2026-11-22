package pipeline.db

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.ports.TestStatus
import pipeline.stages.CheckerCategory
import pipeline.stages.EvalVerdict
import pipeline.stages.Fqcn
import pipeline.stages.GuardrailsMode
import pipeline.stages.RunId
import pipeline.stages.VerificationScope

class VerdictViewTest {

    private fun runRecord(runId: RunId, scope: VerificationScope) = EvaluationRunRecord(
        runId = runId,
        benchmarkId = "bench",
        commitHash = "abc123",
        repoId = "repo",
        repoName = "repo",
        gitUrl = "local",
        defaultBranch = "main",
        configYaml = "{}",
        parentCommit = "parent",
        authorDate = Instant.now().toString(),
        commitMsg = "test",
        sampleIndex = 0,
        sampleTemperature = 0.0,
        configSnapshot = "{}",
        guardrailsMode = GuardrailsMode.LENIENT,
        verificationScope = scope,
        startedAt = Instant.now().toString()
    )

    private suspend fun seedRun(
        db: SQLiteDatabaseManager,
        runId: RunId,
        scope: VerificationScope,
        checkerErrors: Int,
        regStatus: TestStatus?,
        suiteStatuses: List<TestStatus>?,
        regTaskSuccess: Boolean? = null,
        regCompileFailed: Boolean = false,
        regTimedOut: Boolean = false,
        suiteTaskSuccess: Boolean? = null,
        suiteReportedFailureCount: Int? = null,
        suiteCompileFailed: Boolean = false,
        suiteTimedOut: Boolean = false
    ) {
        db.recordRunStart(runRecord(runId, scope))
        val stepId = "step-${runId.value}"
        db.recordStep(
            StepTrajectoryRecord(
                stepId = stepId,
                runId = runId,
                stepNumber = 6,
                attemptNumber = 1,
                stepName = "verification",
                success = true,
                startedAt = Instant.now().toString(),
                completedAt = Instant.now().toString()
            )
        )
        db.recordStageTelemetry(
            TelemetryProjection(
                checkerRuns = listOf(
                    CheckerRunRecord(
                        checkerRunId = "checker-${runId.value}",
                        runId = runId,
                        stepId = stepId,
                        stage = "verification_final",
                        attemptNumber = 1,
                        processor = "proc",
                        checkerCategory = CheckerCategory.BUILTIN_CHECKER,
                        quals = "",
                        exitCode = 0,
                        success = true,
                        checkerInvoked = true,
                        errorCount = checkerErrors,
                        warningCount = 0,
                        diagnosticsLogPath = null
                    )
                )
            )
        )
        if (regStatus != null) {
            val testRunId = "reg-${runId.value}"
            db.recordStageTelemetry(
                TelemetryProjection(
                    testRuns = listOf(
                        TestRunRecord(
                            testRunId = testRunId,
                            runId = runId,
                            stepId = stepId,
                            stage = "verification",
                            suiteKind = "regression_test",
                            attemptNumber = 1,
                            scope = scope,
                            // Nonzero exit from assertion failures must not imply missing evidence.
                            exitCode = if (regStatus == TestStatus.PASSED) 0 else 1,
                            compileFailed = regCompileFailed,
                            testsFound = 1,
                            passedCount = if (regStatus == TestStatus.PASSED) 1 else 0,
                            failedCount = if (regStatus == TestStatus.PASSED) 0 else 1,
                            skippedCount = 0,
                            success = regTaskSuccess ?: (regStatus == TestStatus.PASSED),
                            xmlPath = "test.xml",
                            timedOut = regTimedOut
                        )
                    ),
                    testExecutions = listOf(
                        TestExecutionRecord(
                            executionId = "exec-reg-${runId.value}",
                            runId = runId,
                            stepId = stepId,
                            testRunId = testRunId,
                            stage = "verification",
                            turnIndex = null,
                            testSuite = "com.example",
                            testClass = "com.example.FooTest",
                            testMethod = "testBar",
                            isRegressionTest = true,
                            status = regStatus,
                            durationMs = 5,
                            failureMessage = null
                        )
                    )
                )
            )
        }
        if (suiteStatuses != null) {
            val testRunId = "suite-${runId.value}"
            db.recordStageTelemetry(
                TelemetryProjection(
                    testRuns = listOf(
                        TestRunRecord(
                            testRunId = testRunId,
                            runId = runId,
                            stepId = stepId,
                            stage = "verification",
                            suiteKind = "pre_existing_suite",
                            attemptNumber = 1,
                            scope = scope,
                            exitCode = if (suiteStatuses.all { it == TestStatus.PASSED }) 0 else 1,
                            compileFailed = suiteCompileFailed,
                            testsFound = suiteStatuses.size,
                            passedCount = suiteStatuses.count { it == TestStatus.PASSED },
                            failedCount = suiteStatuses.count { it == TestStatus.FAILED || it == TestStatus.ERROR },
                            skippedCount = suiteStatuses.count { it == TestStatus.SKIPPED },
                            success = suiteTaskSuccess ?: suiteStatuses.all { it == TestStatus.PASSED },
                            xmlPath = "suite.xml",
                            timedOut = suiteTimedOut,
                            reportedFailedCount = suiteReportedFailureCount
                                ?: suiteStatuses.count { it == TestStatus.FAILED || it == TestStatus.ERROR }
                        )
                    ),
                    testExecutions = suiteStatuses.mapIndexed { i, status ->
                        TestExecutionRecord(
                            executionId = "exec-suite-${runId.value}-$i",
                            runId = runId,
                            stepId = stepId,
                            testRunId = testRunId,
                            stage = "verification",
                            turnIndex = null,
                            testSuite = "com.example",
                            testClass = "com.example.OtherTest",
                            testMethod = "test$i",
                            isRegressionTest = false,
                            status = status,
                            durationMs = 5,
                            failureMessage = null
                        )
                    }
                )
            )
        }
        db.finishRun(runId, "success")
    }

    private fun withDb(block: suspend (SQLiteDatabaseManager) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("verdict-view-test").toFile()
        try {
            SQLiteDatabaseManager(File(dir, "pipeline.db")).use { db -> block(db) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun readNullableBoolean(db: SQLiteDatabaseManager, runId: RunId, column: String): Boolean? {
        require(column == "reg_test_passed" || column == "unit_tests_passed")
        db.connectionForReads().prepareStatement(
            "SELECT $column FROM v_evaluation_verdicts WHERE run_id=?"
        ).use { statement ->
            statement.setString(1, runId.value)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return null
                val value = rs.getInt(1)
                return if (rs.wasNull()) null else value != 0
            }
        }
    }

    @Test
    fun `failed regression with valid evidence is runtime fail not missing`() = withDb { db ->
        val runId = RunId("run-reg-fail")
        seedRun(db, runId, VerificationScope.REGRESSION_ONLY, checkerErrors = 0, regStatus = TestStatus.FAILED, suiteStatuses = null)
        assertEquals(EvalVerdict.TYPE_SUCCESS_RUNTIME_FAIL, db.readVerdict(runId))
    }

    @Test
    fun `failed suite with valid evidence is suite failure not missing`() = withDb { db ->
        val runId = RunId("run-suite-fail")
        seedRun(
            db, runId, VerificationScope.FULL_SUITE, checkerErrors = 0,
            regStatus = TestStatus.PASSED, suiteStatuses = listOf(TestStatus.PASSED, TestStatus.FAILED)
        )
        assertEquals(EvalVerdict.SUITE_FAILURE, db.readVerdict(runId))
    }

    @Test
    fun `absent regression evidence is verification missing`() = withDb { db ->
        val runId = RunId("run-reg-missing")
        seedRun(db, runId, VerificationScope.REGRESSION_ONLY, checkerErrors = 0, regStatus = null, suiteStatuses = null)
        assertEquals(EvalVerdict.VERIFICATION_MISSING, db.readVerdict(runId))
    }

    @Test
    fun `failed exit with valid evidence still classifies`() = withDb { db ->
        // success=false + exitCode=1, but parseable FAILED row present.
        val runId = RunId("run-exit-nonzero")
        seedRun(db, runId, VerificationScope.REGRESSION_ONLY, checkerErrors = 0, regStatus = TestStatus.ERROR, suiteStatuses = null)
        assertEquals(EvalVerdict.TYPE_SUCCESS_RUNTIME_FAIL, db.readVerdict(runId))
    }

    @Test
    fun `nonzero exit with passing rows and no reported failures is missing`() = withDb { db ->
        val runId = RunId("run-nonzero-pass-only")
        seedRun(
            db,
            runId,
            VerificationScope.REGRESSION_ONLY,
            checkerErrors = 0,
            regStatus = TestStatus.PASSED,
            suiteStatuses = null,
            regTaskSuccess = false
        )
        assertEquals(EvalVerdict.VERIFICATION_MISSING, db.readVerdict(runId))
        assertEquals(null, readNullableBoolean(db, runId, "reg_test_passed"))
    }

    @Test
    fun `compile failure is missing even when a failure row exists`() = withDb { db ->
        val runId = RunId("run-compile-failure")
        seedRun(
            db,
            runId,
            VerificationScope.REGRESSION_ONLY,
            checkerErrors = 0,
            regStatus = TestStatus.FAILED,
            suiteStatuses = null,
            regCompileFailed = true
        )
        assertEquals(EvalVerdict.VERIFICATION_MISSING, db.readVerdict(runId))
    }

    @Test
    fun `timeout is missing even when a partial failure row exists`() = withDb { db ->
        val runId = RunId("run-timeout")
        seedRun(
            db,
            runId,
            VerificationScope.REGRESSION_ONLY,
            checkerErrors = 0,
            regStatus = TestStatus.FAILED,
            suiteStatuses = null,
            regTimedOut = true
        )
        assertEquals(EvalVerdict.VERIFICATION_MISSING, db.readVerdict(runId))
    }

    @Test
    fun `suite report remains valid when nonzero exit is explained by excluded regression failure`() = withDb { db ->
        val runId = RunId("run-suite-regression-failure-only")
        seedRun(
            db,
            runId,
            VerificationScope.FULL_SUITE,
            checkerErrors = 0,
            regStatus = TestStatus.FAILED,
            suiteStatuses = listOf(TestStatus.PASSED, TestStatus.PASSED),
            suiteTaskSuccess = false,
            suiteReportedFailureCount = 0
        )
        assertEquals(EvalVerdict.TYPE_SUCCESS_RUNTIME_FAIL, db.readVerdict(runId))
    }

    @Test
    fun `unexplained nonzero suite exit is missing and unit test result stays unknown`() = withDb { db ->
        val runId = RunId("run-unexplained-suite-exit")
        seedRun(
            db,
            runId,
            VerificationScope.FULL_SUITE,
            checkerErrors = 0,
            regStatus = TestStatus.PASSED,
            suiteStatuses = listOf(TestStatus.PASSED),
            suiteTaskSuccess = false,
            suiteReportedFailureCount = 0
        )
        assertEquals(EvalVerdict.VERIFICATION_MISSING, db.readVerdict(runId))
        assertEquals(null, readNullableBoolean(db, runId, "unit_tests_passed"))
    }

    @Test
    fun `successful verification stays full success`() = withDb { db ->
        val runId = RunId("run-success")
        seedRun(
            db, runId, VerificationScope.FULL_SUITE, checkerErrors = 0,
            regStatus = TestStatus.PASSED, suiteStatuses = listOf(TestStatus.PASSED, TestStatus.PASSED)
        )
        assertEquals(EvalVerdict.FULL_SUCCESS, db.readVerdict(runId))
    }

    @Test
    fun `checker errors outrank test failures in view`() = withDb { db ->
        val runId = RunId("run-checker")
        seedRun(
            db, runId, VerificationScope.FULL_SUITE, checkerErrors = 3,
            regStatus = TestStatus.FAILED, suiteStatuses = listOf(TestStatus.FAILED)
        )
        assertEquals(EvalVerdict.FIXER_INCOMPLETE, db.readVerdict(runId))
    }

    @Test
    fun `legacy regression introduced label maps to suite failure`() {
        assertEquals(EvalVerdict.SUITE_FAILURE, EvalVerdict.fromStoredName("regression_introduced"))
        assertEquals(EvalVerdict.SUITE_FAILURE, EvalVerdict.fromStoredName("REGRESSION_INTRODUCED"))
        assertEquals(null, EvalVerdict.fromStoredName("unknown-label"))
    }

    @Test
    fun `opening a legacy database migrates evidence columns and recreates verdict view`() {
        val dir = Files.createTempDirectory("legacy-verdict-db").toFile()
        val file = File(dir, "pipeline.db")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """CREATE TABLE test_runs (
                            test_run_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            step_id TEXT NOT NULL,
                            stage TEXT NOT NULL,
                            suite_kind TEXT NOT NULL,
                            attempt_number INTEGER NOT NULL DEFAULT 1,
                            scope TEXT NOT NULL,
                            exit_code INTEGER NOT NULL,
                            compile_failed BOOLEAN NOT NULL DEFAULT 0,
                            tests_found INTEGER NOT NULL DEFAULT 0,
                            passed_count INTEGER NOT NULL DEFAULT 0,
                            failed_count INTEGER NOT NULL DEFAULT 0,
                            skipped_count INTEGER NOT NULL DEFAULT 0,
                            success BOOLEAN NOT NULL DEFAULT 0,
                            xml_path TEXT,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )"""
                    )
                    statement.execute(
                        """INSERT INTO test_runs
                           (test_run_id, run_id, step_id, stage, suite_kind, scope, exit_code,
                            failed_count, xml_path)
                           VALUES ('old-test-run','old-run','old-step','verification',
                                   'regression_test','regression_only',1,2,'old.xml')"""
                    )
                    statement.execute(
                        "CREATE VIEW v_evaluation_verdicts AS " +
                            "SELECT 'old-run' AS run_id, 'regression_introduced' AS verdict"
                    )
                }
            }

            SQLiteDatabaseManager(file).use { db ->
                val columns = mutableSetOf<String>()
                db.connectionForReads().createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(test_runs)").use { rs ->
                        while (rs.next()) columns += rs.getString("name")
                    }
                    statement.executeQuery(
                        "SELECT reported_failed_count FROM test_runs WHERE test_run_id='old-test-run'"
                    ).use { rs ->
                        assertTrue(rs.next())
                        assertEquals(2, rs.getInt(1))
                    }
                    statement.executeQuery(
                        "SELECT sql FROM sqlite_master WHERE type='view' AND name='v_evaluation_verdicts'"
                    ).use { rs ->
                        assertTrue(rs.next())
                        val viewSql = rs.getString(1).lowercase()
                        assertTrue(viewSql.contains("suite_failure"))
                        assertFalse(viewSql.contains("regression_introduced"))
                    }
                }
                assertTrue("timed_out" in columns)
                assertTrue("reported_failed_count" in columns)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
