package pipeline.db

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.stages.RunId

/**
 * The discarded-attempt table is additive. Opening a database created by an
 * older pipeline version must create it without touching existing tables, and
 * opening the same database twice must stay idempotent.
 */
class DiscardedAttemptMigrationTest {

    @Test
    fun `legacy database gains discarded_attempts table and index on open`() = runBlocking {
        Class.forName("org.sqlite.JDBC")
        val file: File = Files.createTempFile("pipeline-legacy-discards", ".db").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE evaluation_runs (
                            run_id TEXT PRIMARY KEY,
                            benchmark_id TEXT NOT NULL,
                            commit_hash TEXT NOT NULL,
                            sample_index INTEGER NOT NULL DEFAULT 0,
                            sample_temperature REAL NOT NULL DEFAULT 0.0,
                            cas_cache_key TEXT,
                            config_snapshot JSON NOT NULL,
                            guardrails_mode TEXT NOT NULL,
                            verification_scope TEXT NOT NULL DEFAULT 'full_suite',
                            status TEXT NOT NULL,
                            started_at TIMESTAMP NOT NULL,
                            completed_at TIMESTAMP
                        )
                        """.trimIndent()
                    )
                    statement.execute("INSERT INTO evaluation_runs(run_id,benchmark_id,commit_hash,config_snapshot,guardrails_mode,status,started_at) VALUES('legacy','b','c','{}','lenient','success','2026-01-01T00:00:00Z')")
                }
            }

            SQLiteDatabaseManager(file).use { }
            // Reopening the migrated database must not fail on duplicate DDL.
            SQLiteDatabaseManager(file).use { database ->
                val columns = mutableSetOf<String>()
                database.jdbc.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(discarded_attempts)").use { rs ->
                        while (rs.next()) columns += rs.getString("name")
                    }
                }
                listOf(
                    "attempt_id", "run_id", "step_id", "stage", "stage_attempt_number",
                    "internal_attempt_number", "started_at", "completed_at", "termination",
                    "discard_check", "feedback", "agent_failure_message", "exit_code",
                    "steps_counted", "timed_out", "prompt_path", "transcript_path",
                    "patch_path", "patch_omitted_reason", "diagnostics_path",
                ).forEach { column ->
                    assertTrue(column in columns, "expected discarded_attempts.$column, got: $columns")
                }

                val indexes = mutableSetOf<String>()
                database.jdbc.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA index_list(discarded_attempts)").use { rs ->
                        while (rs.next()) indexes += rs.getString("name")
                    }
                }
                assertTrue("idx_discarded_attempts_run_stage_attempt" in indexes, "indexes: $indexes")

                // The new table is writable and the pre-existing row is untouched.
                database.recordDiscardedAttempt(
                    DiscardedAttemptRecord(
                        attemptId = "legacy-attempt", runId = RunId("legacy"), stepId = "step", stage = "application",
                        stageAttemptNumber = 1, internalAttemptNumber = 1,
                        startedAt = "2026-01-01T00:00:00Z", completedAt = "2026-01-01T00:01:00Z",
                        termination = AgentSessionTermination.TIMEOUT, discardCheck = DiscardCheck.NO_CHANGES,
                        feedback = "empty patch", agentFailureMessage = null, exitCode = -1,
                        stepsCounted = 0, timedOut = true, promptPath = null, transcriptPath = null,
                        patchPath = null, patchOmittedReason = "no changes", diagnosticsPath = null,
                    )
                )
                assertEquals(1, database.readDiscardedAttempts(RunId("legacy")).size)
                database.jdbc.createStatement().use { statement ->
                    statement.executeQuery("SELECT status FROM evaluation_runs WHERE run_id='legacy'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("success", rs.getString(1))
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
