package pipeline.db

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pipeline.stages.RunId

class DatabaseTelemetryTest {
    @Test fun metricsCaptureSuccessfulAndFailedOperationsWithoutNesting() = runBlocking {
        val dir = Files.createTempDirectory("db-metrics").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.transaction("successful_test") { it.createStatement().executeUpdate("INSERT INTO repositories(repo_id,name,git_url,default_branch,config_yaml) VALUES('r','r','u','main','')") }
            runCatching { db.transaction("failed_test") { throw IllegalStateException("expected failure") } }
            runCatching { db.read("failed_read") { throw IllegalArgumentException("bad read") } }
            db.flushOperationMetrics()
            val rows = db.jdbc.createStatement().executeQuery("SELECT operation,success FROM db_operation_metrics ORDER BY metric_id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
            }
            assertTrue(rows.any { it == "successful_test" to 1 })
            assertTrue(rows.any { it == "failed_test" to 0 })
            assertTrue(rows.any { it == "failed_read" to 0 })
        }
        dir.deleteRecursively()
    }

    @Test fun finishRunAndCloseFlushBufferedMetrics() = runBlocking {
        val dir = Files.createTempDirectory("db-metrics-flush").toFile()
        val dbFile = File(dir, "test.db")
        SQLiteDatabaseManager(dbFile).use { db ->
            db.transaction("before_finish") { }
            db.finishRun(RunId("missing-run"), "failed")
            val afterFinish = db.jdbc.createStatement().executeQuery(
                "SELECT COUNT(*) FROM db_operation_metrics WHERE operation IN ('before_finish','finish_run')"
            ).use { rs -> rs.next(); rs.getInt(1) }
            assertEquals(2, afterFinish)

            db.transaction("before_close") { }
        }
        SQLiteDatabaseManager(dbFile).use { reopened ->
            val afterClose = reopened.jdbc.createStatement().executeQuery(
                "SELECT COUNT(*) FROM db_operation_metrics WHERE operation='before_close'"
            ).use { rs -> rs.next(); rs.getInt(1) }
            assertEquals(1, afterClose)
        }
        dir.deleteRecursively()
    }

    @Test fun metricBufferFlushesAtThreshold() = runBlocking {
        val dir = Files.createTempDirectory("db-metrics-threshold").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            repeat(200) { db.read("threshold_read") { } }
            val flushed = db.jdbc.createStatement().executeQuery(
                "SELECT COUNT(*) FROM db_operation_metrics WHERE operation='threshold_read'"
            ).use { rs -> rs.next(); rs.getInt(1) }
            assertEquals(200, flushed)
        }
        dir.deleteRecursively()
    }

    @Test fun telemetryWritesDoNotCreateDbOperationMetrics() = runBlocking {
        val dir = Files.createTempDirectory("db-metrics-no-recursion").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.recordRetry(RetryEvent("id", "run", "step", "triage", 1, 3, true, 2000, "Timeout", "temporary"))
            db.recordSubprocess(SubprocessMetricRecord("m1", RunId("run"), "step", "verification", "test", "gradle", 10, 0, false, true, null))
            db.flushOperationMetrics()
            val rows = db.jdbc.createStatement().executeQuery(
                "SELECT COUNT(*) FROM db_operation_metrics WHERE operation IN ('record_retry','record_subprocess')"
            ).use { rs -> rs.next(); rs.getInt(1) }
            assertEquals(0, rows)
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

    @Test fun subprocessMetricsPersistAndAggregate() = runBlocking {
        val dir = Files.createTempDirectory("db-subprocess").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.recordSubprocess(SubprocessMetricRecord("m1", RunId("run"), "step", "verification", "test", "gradle", 10, 1, true, false, "timeout"))
            db.recordSubprocess(SubprocessMetricRecord("m2", RunId("run"), "step", "verification", "test", "gradle", 30, 0, false, true, null))
            db.read { c -> c.createStatement().executeQuery("SELECT calls,failures,timeouts,avg_duration_ms,max_duration_ms,total_duration_ms FROM v_subprocess_summary WHERE stage='verification' AND kind='test'").use { rs ->
                assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); assertEquals(1, rs.getInt(2)); assertEquals(1, rs.getInt(3))
                assertEquals(20.0, rs.getDouble(4)); assertEquals(30, rs.getLong(5)); assertEquals(40, rs.getLong(6))
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
