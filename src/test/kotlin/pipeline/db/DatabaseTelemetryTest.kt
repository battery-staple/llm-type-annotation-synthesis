package pipeline.db

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseTelemetryTest {
    @Test fun metricsCaptureSuccessfulAndFailedOperationsWithoutNesting() = runBlocking {
        val dir = Files.createTempDirectory("db-metrics").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.transaction("successful_test") { it.createStatement().executeUpdate("INSERT INTO repositories(repo_id,name,git_url,default_branch,config_yaml) VALUES('r','r','u','main','')") }
            runCatching { db.transaction("failed_test") { throw IllegalStateException("expected failure") } }
            runCatching { db.read("failed_read") { throw IllegalArgumentException("bad read") } }
            val rows = db.read { c -> c.createStatement().executeQuery("SELECT operation,success FROM db_operation_metrics ORDER BY metric_id").use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) } } }
            assertTrue(rows.any { it == "successful_test" to 1 })
            assertTrue(rows.any { it == "failed_test" to 0 })
            assertTrue(rows.any { it == "failed_read" to 0 })
        }
        dir.deleteRecursively()
    }

    @Test fun retryEventsPersistFinalAttemptAndBoundErrorText() = runBlocking {
        val dir = Files.createTempDirectory("db-retry").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.recordRetry(RetryEvent("id", "run", "step", "triage", 3, 3, false, 0, "Timeout", "x".repeat(1500)))
            db.read { c -> c.createStatement().executeQuery("SELECT will_retry,error_message FROM retry_events WHERE retry_id='id'").use { rs ->
                assertTrue(rs.next()); assertEquals(0, rs.getInt(1)); assertEquals(1000, rs.getString(2).length)
            } }
        }
        dir.deleteRecursively()
    }

    @Test fun triageOnlyIsNotAnEvaluatedVerdict() = runBlocking {
        val dir = Files.createTempDirectory("db-triage-only").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.jdbc.createStatement().use { s ->
                s.execute("INSERT INTO repositories(repo_id,name,git_url,default_branch,config_yaml) VALUES('r','r','u','main','')")
                s.execute("INSERT INTO commits(commit_hash,repo_id,parent_commit,author_date,commit_msg,triage_status) VALUES('c','r','p','','m','verified')")
                s.execute("INSERT INTO evaluation_runs(run_id,benchmark_id,commit_hash,config_snapshot,guardrails_mode,verification_scope,status,started_at,completed_at) VALUES('run','b','c','{}','none','full_suite','triage_only','now','now')")
            }
            db.read { c -> c.createStatement().executeQuery("SELECT outcome_kind,verdict FROM v_evaluation_verdicts WHERE run_id='run'").use { rs ->
                assertTrue(rs.next()); assertEquals("triage_only", rs.getString("outcome_kind")); assertEquals(null, rs.getString("verdict"))
            } }
        }
        dir.deleteRecursively()
    }
}
