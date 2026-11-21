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
        connection.createStatement().use { statement ->
            // JDBC's execute() only runs the first statement in a multi-statement string.
            Schema.DDL.split(';').forEach { ddl ->
                if (ddl.isNotBlank()) statement.execute(ddl)
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
