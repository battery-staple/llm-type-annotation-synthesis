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
        migrateCheckerRunColumns()
        migrateEvaluationRunColumns()
        connection.createStatement().use { statement ->
            // JDBC's execute() only runs the first statement in a multi-statement string.
            Schema.DDL.split(';').forEach { ddl ->
                if (ddl.isNotBlank()) statement.execute(ddl)
            }
        }
    }

    /** Add evidence metadata to databases created by earlier pipeline versions. */
    private fun migrateTestRunColumns() {
        val columns = tableColumns("test_runs")
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

    /**
     * `evaluation_runs.batch_id` was added after the first databases were
     * created. `CREATE TABLE IF NOT EXISTS` does not add columns to an
     * existing table, and the schema's index on `batch_id` would otherwise
     * fail with "no such column" before any run can start.
     */
    private fun migrateEvaluationRunColumns() {
        val columns = tableColumns("evaluation_runs")
        if (columns.isEmpty()) return // Fresh database; the DDL creates the current schema.
        if ("batch_id" !in columns) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE evaluation_runs ADD COLUMN batch_id TEXT")
            }
        }
        if ("owner_pid" !in columns) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE evaluation_runs ADD COLUMN owner_pid INTEGER")
            }
        }
    }

    private fun migrateCheckerRunColumns() {
        val columns = tableColumns("checker_runs")
        if (columns.isNotEmpty() && "timed_out" !in columns) {
            connection.createStatement().use { it.execute("ALTER TABLE checker_runs ADD COLUMN timed_out BOOLEAN NOT NULL DEFAULT 0") }
        }
    }

    private fun tableColumns(table: String): Set<String> {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) columns += rs.getString("name")
            }
        }
        return columns
    }

    suspend fun <T> transaction(operation: String = "transaction", block: (Connection) -> T): T =
        mutex.withLock {
            withContext(dbDispatcher) {
                val started = System.nanoTime()
                var success = false
                var error: Throwable? = null
                check(!inTransaction) { "Nested transactions are not supported" }
                check(connection.autoCommit) {
                    "Connection must be in auto-commit mode; BEGIN IMMEDIATE manages the transaction"
                }
                connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                inTransaction = true
                try {
                    val result = block(connection)
                    connection.createStatement().use { it.execute("COMMIT") }
                    success = true
                    result
                } catch (t: Throwable) {
                    error = t
                    runCatching {
                        connection.createStatement().use { it.execute("ROLLBACK") }
                    }
                    logger.warn(t) { "Database transaction rolled back" }
                    throw t
                } finally {
                    inTransaction = false
                    recordOperationMetric(operation, (System.nanoTime() - started) / 1_000_000.0, success, error)
                }
            }
        }

    /** Serialized read on the same single connection (MVP intentionally has no reader pool). */
    suspend fun <T> read(operation: String = "read", block: (Connection) -> T): T =
        mutex.withLock {
            withContext(dbDispatcher) {
                val started = System.nanoTime()
                var success = false
                var error: Throwable? = null
                try { block(connection).also { success = true } }
                catch (t: Throwable) { error = t; throw t }
                finally { recordOperationMetric(operation, (System.nanoTime() - started) / 1_000_000.0, success, error) }
            }
        }

    private fun recordOperationMetric(operation: String, durationMs: Double, success: Boolean, error: Throwable?) {
        runCatching {
            connection.prepareStatement("INSERT INTO db_operation_metrics(operation,duration_ms,success,error_message) VALUES(?,?,?,?)").use { s ->
                s.setString(1, operation); s.setDouble(2, durationMs); s.setInt(3, if (success) 1 else 0)
                s.setString(4, error?.message?.take(300)); s.executeUpdate()
            }
        }.onFailure { logger.warn(it) { "Could not record database operation metric" } }
    }

    val jdbc: Connection get() = connection

    override fun close() {
        connection.close()
    }
}
