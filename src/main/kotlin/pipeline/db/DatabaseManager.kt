package pipeline.db

import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

open class DatabaseManager(private val connection: Connection) : AutoCloseable {
    private val mutex = Mutex()
    // One shared single-permit dispatcher; never create a new limitedParallelism
    // view per call, which would defeat confinement and add allocation overhead.
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var inTransaction = false // guarded by mutex + dbDispatcher

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA foreign_keys=ON")
        }
        migrateTestRunColumns()
        connection.createStatement().use { statement ->
            // JDBC's execute() only runs the first statement in a multi-statement string.
            Schema.DDL.split(';').forEach { ddl ->
                if (ddl.isNotBlank()) statement.execute(ddl)
            }
        }
    }

    /** Add evidence metadata to databases created by earlier pipeline versions. */
    private fun migrateTestRunColumns() {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(test_runs)").use { rs ->
                while (rs.next()) columns += rs.getString("name")
            }
        }
        if (columns.isEmpty()) return // Fresh database; the DDL creates the current schema.

        connection.createStatement().use { statement ->
            if ("timed_out" !in columns) {
                statement.execute("ALTER TABLE test_runs ADD COLUMN timed_out BOOLEAN NOT NULL DEFAULT 0")
            }
            if ("reported_failed_count" !in columns) {
                statement.execute("ALTER TABLE test_runs ADD COLUMN reported_failed_count INTEGER NOT NULL DEFAULT 0")
                // Existing failed_count values already reflect the recorded outcomes.
                statement.execute("UPDATE test_runs SET reported_failed_count = failed_count")
            }
        }
    }

    suspend fun <T> transaction(block: (Connection) -> T): T =
        mutex.withLock {
            withContext(dbDispatcher) {
                check(!inTransaction) { "Nested transactions are not supported" }
                check(connection.autoCommit) {
                    "Connection must be in auto-commit mode; BEGIN IMMEDIATE manages the transaction"
                }
                connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                inTransaction = true
                try {
                    val result = block(connection)
                    connection.createStatement().use { it.execute("COMMIT") }
                    result
                } catch (t: Throwable) {
                    runCatching {
                        connection.createStatement().use { it.execute("ROLLBACK") }
                    }
                    logger.warn(t) { "Database transaction rolled back" }
                    throw t
                } finally {
                    inTransaction = false
                }
            }
        }

    /** Serialized read on the same single connection (MVP intentionally has no reader pool). */
    suspend fun <T> read(block: (Connection) -> T): T =
        mutex.withLock {
            withContext(dbDispatcher) {
                block(connection)
            }
        }

    val jdbc: Connection get() = connection

    override fun close() {
        connection.close()
    }
}
