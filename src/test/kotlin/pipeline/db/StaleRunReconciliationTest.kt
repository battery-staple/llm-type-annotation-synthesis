package pipeline.db

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Several pipeline processes may share one database, so starting a new process
 * must only interrupt runs whose owning process is gone.
 */
class StaleRunReconciliationTest {

    private fun status(file: File, runId: String): String {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.prepareStatement("SELECT status FROM evaluation_runs WHERE run_id=?").use { s ->
                s.setString(1, runId)
                s.executeQuery().use { rs -> rs.next(); return rs.getString(1) }
            }
        }
    }

    @Test
    fun `only runs with a dead owner are interrupted`() {
        val file = Files.createTempFile("pipeline-stale", ".db").toFile()
        try {
            SQLiteDatabaseManager(file).use { }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { it.execute("PRAGMA foreign_keys=OFF") }
                val rows = listOf(
                    Triple("live", ProcessHandle.current().pid(), "running"),
                    Triple("dead", 2_000_000_000L, "running"),
                    Triple("legacy", null, "running"),
                )
                for ((id, pid, st) in rows) {
                    c.prepareStatement(
                        """INSERT INTO evaluation_runs (run_id, benchmark_id, commit_hash, config_snapshot,
                           guardrails_mode, status, started_at, owner_pid) VALUES (?,?,?,?,?,?,?,?)"""
                    ).use { s ->
                        s.setString(1, id); s.setString(2, "b"); s.setString(3, "c"); s.setString(4, "{}")
                        s.setString(5, "lenient"); s.setString(6, st); s.setString(7, "2026-01-01T00:00:00Z")
                        if (pid == null) s.setNull(8, java.sql.Types.INTEGER) else s.setLong(8, pid)
                        s.executeUpdate()
                    }
                }
            }
            SQLiteDatabaseManager(file).use { it.markStaleRunningRuns() }
            assertEquals("running", status(file, "live"))
            assertEquals("interrupted", status(file, "dead"))
            assertEquals("interrupted", status(file, "legacy"))
        } finally {
            file.delete()
        }
    }
}
