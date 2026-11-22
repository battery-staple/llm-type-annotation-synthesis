package pipeline.runners

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeDbTest {

    @Test
    fun `isolated db is seeded with schema and provider credentials`() {
        val global = File(System.getProperty("user.home"), ".local/share/opencode/opencode.db")
        if (!global.isFile) return // OpenCode has never been used in this environment.

        val target = Files.createTempFile("opencode-db-test-", ".db").toFile()
        target.delete()
        try {
            val prepared = OpenCodeDb.prepare(target)
            assertNotNull(prepared)
            DriverManager.getConnection("jdbc:sqlite:${prepared.absolutePath}").use { db ->
                val tables = db.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
                assertTrue(tables > 10, "expected the OpenCode schema, found $tables tables")
                val credentials = db.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM credential").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
                assertTrue(credentials > 0, "expected provider credentials in the seeded DB")
                val accounts = db.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM account_state").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
                assertEquals(1, accounts, "expected exactly one active account row")
            }
        } finally {
            target.delete()
        }
    }
}
