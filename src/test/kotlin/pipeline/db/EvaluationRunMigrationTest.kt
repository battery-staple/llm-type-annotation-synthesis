package pipeline.db

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Existing pipeline databases predate `evaluation_runs.batch_id`. Opening one
 * must migrate the column before the schema tries to create its index; otherwise
 * every run fails with "no such column: batch_id" during initialization.
 */
class EvaluationRunMigrationTest {

    @Test
    fun `legacy evaluation_runs gains batch_id on open`() {
        Class.forName("org.sqlite.JDBC")
        val file: File = Files.createTempFile("pipeline-legacy-runs", ".db").toFile()
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
                }
            }

            // Opening the manager runs the migration and then the full DDL.
            SQLiteDatabaseManager(file).use { }

            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                val columns = mutableSetOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(evaluation_runs)").use { rs ->
                        while (rs.next()) columns += rs.getString("name")
                    }
                }
                assertTrue("batch_id" in columns, "expected batch_id migration, got: $columns")
            }
        } finally {
            file.delete()
        }
    }
}
