package pipeline.runners

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import mu.KotlinLogging

/**
 * OpenCode stores provider credentials in its global SQLite DB. The pipeline
 * isolates agent sessions in a separate DB so a killed agent cannot corrupt the
 * user's chats, but a blank DB has no OpenCode Go account/credential rows and
 * the provider router therefore reports `provider.no-route`.
 *
 * This helper builds a one-run DB from the global schema and copies only the
 * schema/account/migration/catalog rows needed for provider routing. It never
 * copies chat/session history.
 */
object OpenCodeDb {
    private val log = KotlinLogging.logger {}

    private val copiedTables = listOf(
        "account",
        "account_state",
        "credential",
        "control_account",
        "migration",
        "kv"
    )

    fun prepare(target: File = defaultTarget()): File? {
        val global = locateGlobalDb()
        if (global == null) {
            log.warn { "OpenCode global DB not found; agents will use the shared OpenCode DB" }
            return null
        }
        if (target.exists() && !target.delete()) {
            log.warn { "Could not replace ${target.absolutePath}; using a fresh path" }
        }
        target.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")

        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { db ->
                copySchema(global, db)
                copyRows(global, db)
            }
            log.info { "Prepared isolated OpenCode DB at ${target.absolutePath}" }
            target
        }.getOrElse { error ->
            log.warn(error) { "Could not seed isolated OpenCode DB; falling back to the shared DB" }
            null
        }
    }

    private fun defaultTarget(): File =
        File(System.getenv("PIPELINE_OPENCODE_DB") ?: "data/opencode-agent-${ProcessHandle.current().pid()}.db")

    private fun locateGlobalDb(): File? {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            System.getenv("OPENCODE_GLOBAL_DB")?.let(::File),
            File(home, ".local/share/opencode/opencode.db"),
            File(home, "Library/Application Support/opencode/opencode.db"),
            System.getenv("XDG_DATA_HOME")?.let { File(it, "opencode/opencode.db") }
        ).firstOrNull { it.isFile }
    }

    private fun copySchema(global: File, db: Connection) {
        DriverManager.getConnection("jdbc:sqlite:${global.absolutePath}").use { source ->
            val statements = source.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT sql FROM sqlite_master
                       WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
                       ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'trigger' THEN 2 ELSE 3 END"""
                ).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
            db.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
        }
    }

    private fun copyRows(global: File, db: Connection) {
        val escaped = global.absolutePath.replace("'", "''")
        db.createStatement().use { it.execute("ATTACH DATABASE '$escaped' AS source") }
        try {
            copiedTables.forEach { table ->
                val columns = db.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(2)) }
                    }
                }
                if (columns.isEmpty()) return@forEach
                val list = columns.joinToString(",")
                db.createStatement().use { statement ->
                    statement.execute("INSERT OR REPLACE INTO main.$table ($list) SELECT $list FROM source.$table")
                }
            }
        } finally {
            runCatching { db.createStatement().use { it.execute("DETACH DATABASE source") } }
        }
    }
}
