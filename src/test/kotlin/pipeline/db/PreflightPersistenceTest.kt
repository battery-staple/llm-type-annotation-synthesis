package pipeline.db

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import pipeline.stages.GuardrailsMode
import pipeline.stages.PreflightCheck
import pipeline.stages.PreflightReport
import pipeline.stages.RunId
import pipeline.stages.VerificationScope

class PreflightPersistenceTest {

    private fun withDb(block: suspend (SQLiteDatabaseManager) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("preflight-persistence").toFile()
        try {
            SQLiteDatabaseManager(File(dir, "pipeline.db")).use { db -> block(db) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(runId: RunId) = EvaluationRunRecord(
        runId = runId, benchmarkId = "bench", commitHash = "abc123", repoId = "repo", repoName = "repo",
        gitUrl = "local", defaultBranch = "main", configYaml = "{}", parentCommit = "parent",
        authorDate = Instant.now().toString(), commitMsg = "test", sampleIndex = 0, sampleTemperature = 0.0,
        configSnapshot = "{}", guardrailsMode = GuardrailsMode.LENIENT, verificationScope = VerificationScope.FULL_SUITE,
        startedAt = Instant.now().toString(),
    )

    private val unsupported = PreflightReport(
        status = PreflightReport.STATUS_UNSUPPORTED,
        failedCheck = "P7",
        reasonCode = "suite_timeout",
        message = "the full suite did not finish within 900 s",
        gradleVersion = "9.8.2",
        sourceLevelDeclared = "11",
        sourceLevelObserved = "11",
        suiteDurationMs = 900_000,
        durationMs = 1_000_000,
        cacheKey = "key",
        checks = listOf(PreflightCheck("P1", "pass"), PreflightCheck("P7", "fail", detail = "suite_timeout")),
    )

    private fun query(db: SQLiteDatabaseManager, sql: String, vararg args: String): List<String?> =
        db.connectionForReads().prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setString(i + 1, a) }
            s.executeQuery().use { rs ->
                if (!rs.next()) return emptyList()
                (1..rs.metaData.columnCount).map { rs.getString(it) }
            }
        }

    /** Plan, section 7.2: an unsupported repository is excluded from evaluation metrics. */
    @Test
    fun `unsupported run has its own outcome kind and no verdict`() = withDb { db ->
        val runId = RunId("run-unsupported")
        db.recordRunStart(run(runId))
        db.finishRun(runId, "unsupported")

        assertNull(db.readVerdict(runId))
        assertEquals(
            listOf("unsupported"),
            query(db, "SELECT outcome_kind FROM v_evaluation_verdicts WHERE run_id=?", runId.value),
        )
    }

    /** readVerdict threw on the NULL verdict these lifecycle states have in the view. */
    @Test
    fun `triage-only run has no verdict`() = withDb { db ->
        val runId = RunId("run-triage-only")
        db.recordRunStart(run(runId))
        db.finishRun(runId, "triage_only")
        assertNull(db.readVerdict(runId))
    }

    @Test
    fun `preflight results keep the report and the reason code`() = withDb { db ->
        val runId = RunId("run-preflight")
        db.recordRunStart(run(runId))
        db.recordPreflight(
            PreflightResultRecord(
                preflightId = "p1", runId = runId, benchmarkId = "bench", commitHash = "abc123",
                parentCommit = "parent", report = unsupported, cacheHit = true, logPath = "/tmp/logs",
            )
        )
        db.recordPreflight(
            PreflightResultRecord(
                preflightId = "p2", runId = null, benchmarkId = "standalone", commitHash = "c", parentCommit = "c",
                report = PreflightReport(status = PreflightReport.STATUS_PASS), cacheHit = false, logPath = null,
            )
        )

        assertEquals(
            listOf("unsupported", "P7", "suite_timeout", "900000", "1"),
            query(db, "SELECT status, failed_check, reason_code, suite_duration_ms, cache_hit FROM preflight_results WHERE preflight_id='p1'"),
        )
        assertEquals(listOf<String?>(null), query(db, "SELECT run_id FROM preflight_results WHERE preflight_id='p2'"))
        val report = query(db, "SELECT report FROM preflight_results WHERE preflight_id='p1'").single()!!
        assertEquals(unsupported, pipeline.util.PipelineJson.json.decodeFromString(PreflightReport.serializer(), report))
        assertEquals(
            listOf("suite_timeout", "P7", "1"),
            query(db, "SELECT outcome, failed_check, results FROM v_preflight_reason_summary WHERE outcome='suite_timeout'"),
        )
    }
}
