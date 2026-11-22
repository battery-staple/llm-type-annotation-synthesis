package pipeline.runners

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import pipeline.util.Env

internal data class OpenCodeReasoning(
    val sessionId: String,
    val partId: String,
    val messageId: String,
    val timeCreated: Long,
    val turnIndex: Int,
    val text: String,
    val reasoningTokens: Int?
)

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
    private val json = Json { ignoreUnknownKeys = true }
    private val registeredForExit = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val copiedTables = listOf(
        "account",
        "account_state",
        "credential",
        "control_account",
        "migration",
        "kv"
    )

    fun prepare(target: File = defaultTarget()): File? {
        registerForExit(target)
        return prepare(locateGlobalDb(), target)
    }

    private fun registerForExit(target: File) {
        val base = target.absoluteFile
        if (!registeredForExit.add(base.path)) return
        Runtime.getRuntime().addShutdownHook(Thread({
            listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
                .forEach { runCatching { it.delete() } }
        }, "opencode-db-cleanup-${base.name}"))
    }

    internal fun prepare(global: File?, target: File): File? {
        if (global == null) {
            log.warn { "OpenCode global DB not found; agents will use private empty databases" }
            return null
        }
        if (target.exists() && !target.delete()) {
            log.warn { "Could not replace ${target.absolutePath}; using a fresh path" }
        }
        target.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")

        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${global.absolutePath}").use { source ->
                source.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
            }
            DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { db ->
                db.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
                copySchema(global, db)
                copyRows(global, db)
            }
            log.info { "Prepared isolated OpenCode DB at ${target.absolutePath}" }
            target
        }.getOrElse { error ->
            throw IllegalStateException("Could not seed isolated OpenCode DB from ${global.absolutePath}", error)
        }
    }

    /** Creates a private, transactionally consistent per-invocation copy of the seeded DB. */
    fun cloneForInvocation(seed: File?, target: File): File {
        target.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        if (seed?.isFile == true) {
            val escaped = target.absolutePath.replace("'", "''")
            DriverManager.getConnection("jdbc:sqlite:${seed.absolutePath}").use { source ->
                source.createStatement().use {
                    it.execute("PRAGMA busy_timeout=10000")
                    it.execute("VACUUM INTO '$escaped'")
                }
            }
        } else {
            DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}").use { db ->
                db.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
            }
        }
        return target
    }

    /**
     * Read final assistant reasoning parts for only the sessions observed in a
     * single `opencode run`. OpenCode's JSON stream is not consistent across
     * versions about emitting reasoning; the run database is the durable source
     * of truth when its part rows are available.
     */
    internal fun readReasoning(database: File, sessionIds: Set<String>): List<OpenCodeReasoning> {
        if (sessionIds.isEmpty()) return emptyList()
        if (!database.isFile) {
            log.warn { "OpenCode run database is missing; cannot collect reasoning for ${sessionIds.size} session(s)" }
            return emptyList()
        }

        return runCatching {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                readReasoning(connection, sessionIds)
            }
        }.onFailure { error ->
            log.warn(error) { "Could not read reasoning from OpenCode run database ${database.absolutePath}" }
        }.getOrDefault(emptyList())
    }

    private fun readReasoning(connection: Connection, sessionIds: Set<String>): List<OpenCodeReasoning> =
        when {
            hasTable(connection, "session_message") -> readSessionMessageReasoning(connection, sessionIds)
            hasTable(connection, "message") && hasTable(connection, "part") ->
                readLegacyReasoning(connection, sessionIds)
            else -> {
                log.warn { "OpenCode run database has neither the session_message nor the legacy message/part tables" }
                emptyList()
            }
        }

    private fun hasTable(connection: Connection, name: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { it.next() }
        }

    /**
     * OpenCode 2.x layout: one `session_message` row per message whose JSON `data`
     * carries a `content` array (`text`, `reasoning`, ...) and message-level
     * `tokens`. `session_part` is not populated for assistant content, and
     * content items have no ids, so part ids are synthesized as `<messageId>:<index>`.
     */
    private fun readSessionMessageReasoning(connection: Connection, sessionIds: Set<String>): List<OpenCodeReasoning> {
        val placeholders = sessionIds.joinToString(",") { "?" }
        val query = """SELECT id, session_id, time_created, data
                       FROM session_message
                       WHERE type='assistant' AND session_id IN ($placeholders)
                       ORDER BY session_id, seq"""
        val nextTurnBySession = mutableMapOf<String, Int>()
        val reasoning = mutableListOf<OpenCodeReasoning>()
        connection.prepareStatement(query).use { statement ->
            sessionIds.forEachIndexed { index, sessionId -> statement.setString(index + 1, sessionId) }
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val messageId = rows.getString(1)
                    val sessionId = rows.getString(2)
                    val messageTime = rows.getLong(3)
                    val data = parseObject(rows.getString(4))
                    if (data == null) {
                        log.warn { "Ignoring malformed OpenCode message data in session $sessionId" }
                        continue
                    }
                    val turnIndex = (nextTurnBySession[sessionId] ?: 0) + 1
                    nextTurnBySession[sessionId] = turnIndex

                    val messageTokens = (data["tokens"] as? JsonObject)?.let { intValue(it, "reasoning") }
                    var tokensAssigned = false
                    val content = data["content"] as? JsonArray ?: continue
                    content.forEachIndexed { index, item ->
                        val part = item as? JsonObject ?: return@forEachIndexed
                        if (stringValue(part, "type") != "reasoning") return@forEachIndexed
                        val text = stringValue(part, "text")
                        if (text == null) {
                            log.warn { "Ignoring OpenCode reasoning content without text (message $messageId)" }
                            return@forEachIndexed
                        }
                        reasoning += OpenCodeReasoning(
                            sessionId = sessionId,
                            partId = "$messageId:$index",
                            messageId = messageId,
                            timeCreated = messageTime,
                            turnIndex = turnIndex,
                            text = text,
                            reasoningTokens = if (tokensAssigned) null else messageTokens
                        )
                        tokensAssigned = true
                    }
                }
            }
        }
        return reasoning
    }

    /** OpenCode 1.x layout (`message` + `part` tables). */
    private fun readLegacyReasoning(connection: Connection, sessionIds: Set<String>): List<OpenCodeReasoning> {
        val placeholders = sessionIds.joinToString(",") { "?" }
        val query = """SELECT p.id, p.message_id, p.session_id, p.time_created, p.data, m.data
                       FROM part p JOIN message m ON m.id=p.message_id
                       WHERE p.session_id IN ($placeholders)
                       ORDER BY p.session_id, m.time_created, m.id, p.time_created, p.id"""
        val turnByMessage = mutableMapOf<String, Int>()
        val nextTurnBySession = mutableMapOf<String, Int>()
        val countedReasoningTokens = mutableSetOf<String>()
        val reasoning = mutableListOf<OpenCodeReasoning>()
        connection.prepareStatement(query).use { statement ->
            sessionIds.forEachIndexed { index, sessionId -> statement.setString(index + 1, sessionId) }
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val partId = rows.getString(1)
                    val messageId = rows.getString(2)
                    val sessionId = rows.getString(3)
                    val partTimeCreated = rows.getLong(4)
                    val partData = parseObject(rows.getString(5))
                    val messageData = parseObject(rows.getString(6))
                    if (partData == null || messageData == null) {
                        log.warn { "Ignoring malformed OpenCode part/message data in session $sessionId" }
                        continue
                    }
                    if (stringValue(messageData, "role") != "assistant") continue

                    val turnIndex = turnByMessage.getOrPut(messageId) {
                        (nextTurnBySession[sessionId] ?: 0) + 1
                    }
                    nextTurnBySession[sessionId] = maxOf(nextTurnBySession[sessionId] ?: 0, turnIndex)

                    if (stringValue(partData, "type") != "reasoning") continue
                    val text = stringValue(partData, "text")
                    if (text == null) {
                        log.warn { "Ignoring OpenCode reasoning part without text (part $partId)" }
                        continue
                    }
                    val tokens = if (countedReasoningTokens.add(messageId)) {
                        (messageData["tokens"] as? JsonObject)?.let { intValue(it, "reasoning") }
                    } else {
                        null
                    }
                    reasoning += OpenCodeReasoning(
                        sessionId = sessionId,
                        partId = partId,
                        messageId = messageId,
                        timeCreated = partTimeCreated,
                        turnIndex = turnIndex,
                        text = text,
                        reasoningTokens = tokens
                    )
                }
            }
        }
        return reasoning
    }

    private fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun stringValue(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull

    private fun intValue(obj: JsonObject, key: String): Int? =
        obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun defaultTarget(): File =
        File(Env.get("PIPELINE_OPENCODE_DB") ?: "data/opencode-agent-${ProcessHandle.current().pid()}.db")

    private fun locateGlobalDb(): File? {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            Env.get("OPENCODE_GLOBAL_DB")?.let(::File),
            File(home, ".local/share/opencode/opencode.db"),
            File(home, "Library/Application Support/opencode/opencode.db"),
            Env.get("XDG_DATA_HOME")?.let { File(it, "opencode/opencode.db") }
        ).firstOrNull { it.isFile }
    }

    private fun copySchema(global: File, db: Connection) {
        DriverManager.getConnection("jdbc:sqlite:${global.absolutePath}").use { source ->
            source.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
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
        db.createStatement().use {
            it.execute("PRAGMA busy_timeout=10000")
            it.execute("ATTACH DATABASE '$escaped' AS source")
            it.execute("PRAGMA source.busy_timeout=10000")
        }
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
