package pipeline.db

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

class SynthesisRunMigrationTest {
    @Test
    fun `legacy synthesis_runs gains decision telemetry columns on open`() {
        Class.forName("org.sqlite.JDBC")
        val file: File = Files.createTempFile("pipeline-legacy-synthesis", ".db").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE synthesis_runs (
                            synthesis_run_id TEXT PRIMARY KEY,
                            run_id TEXT NOT NULL,
                            step_id TEXT NOT NULL,
                            attempt_number INTEGER NOT NULL DEFAULT 1,
                            custom_quals_jar_path TEXT NOT NULL,
                            placement_guide_path TEXT NOT NULL,
                            qualifier_count INTEGER NOT NULL,
                            helpers_count INTEGER NOT NULL DEFAULT 0,
                            policy_rule_count INTEGER NOT NULL DEFAULT 0,
                            processor TEXT NOT NULL,
                            checker_category TEXT NOT NULL,
                            transcript_path TEXT,
                            metrics_path TEXT,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """.trimIndent()
                    )
                }
            }

            SQLiteDatabaseManager(file).use { }

            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                val columns = mutableSetOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(synthesis_runs)").use { rs ->
                        while (rs.next()) columns += rs.getString("name")
                    }
                }
                assertTrue("decision_source" in columns, "expected decision_source migration, got: $columns")
                assertTrue("decision_rationale" in columns, "expected decision_rationale migration, got: $columns")
                assertTrue("fallback_occurred" in columns, "expected fallback_occurred migration, got: $columns")
            }
        } finally {
            file.delete()
        }
    }
}
