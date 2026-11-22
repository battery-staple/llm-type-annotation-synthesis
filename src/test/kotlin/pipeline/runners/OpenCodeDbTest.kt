package pipeline.runners

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeDbTest {

    @Test
    fun `seed failure with an existing global database fails fast`() {
        val global = Files.createTempFile("opencode-invalid-global-", ".db").toFile()
        val target = Files.createTempFile("opencode-db-test-", ".db").toFile().also { it.delete() }
        try {
            global.writeText("not a sqlite database")
            val error = assertFailsWith<IllegalStateException> {
                OpenCodeDb.prepare(global, target)
            }
            assertTrue(error.message.orEmpty().contains("Could not seed isolated OpenCode DB"))
        } finally {
            global.delete()
            target.delete()
        }
    }

    @Test
    fun `invocation database is a private seeded copy`() {
        val seed = Files.createTempFile("opencode-seed-", ".db").toFile()
        val first = Files.createTempFile("opencode-invocation-", ".db").toFile().also { it.delete() }
        val second = Files.createTempFile("opencode-invocation-", ".db").toFile().also { it.delete() }
        try {
            DriverManager.getConnection("jdbc:sqlite:${seed.absolutePath}").use { db ->
                db.createStatement().use { it.execute("CREATE TABLE account (id TEXT)") }
                db.createStatement().use { it.execute("INSERT INTO account VALUES ('seed')") }
            }
            OpenCodeDb.cloneForInvocation(seed, first)
            OpenCodeDb.cloneForInvocation(seed, second)
            DriverManager.getConnection("jdbc:sqlite:${first.absolutePath}").use { db ->
                db.createStatement().use { it.execute("INSERT INTO account VALUES ('first')") }
            }
            DriverManager.getConnection("jdbc:sqlite:${second.absolutePath}").use { db ->
                db.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM account").use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        } finally {
            listOf(seed, first, second).forEach { it.delete(); File(it.path + "-wal").delete(); File(it.path + "-shm").delete() }
        }
    }

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
