package pipeline.util

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import mu.KotlinLogging

private val cleanupLog = KotlinLogging.logger {}

/** Startup housekeeping for bounded logs and abandoned temporary artifacts. */
object RuntimeCleanup {
    private const val RUN_LOG_LIMIT = 2_000
    private const val RUN_LOG_DAYS = 14L
    private const val DB_AGE_HOURS = 24L
    private const val LEGACY_STAGING_HOURS = 6L

    fun startup(logDir: File = logDirectory(), projectDir: File = File("."), now: Instant = Instant.now()) {
        bestEffort("run log retention") { pruneRunLogs(File(logDir, "runs"), RUN_LOG_LIMIT, RUN_LOG_DAYS, now) }
        bestEffort("CAS staging cleanup") { pruneCasStaging(projectDir.resolve(".cache/stages")) }
        bestEffort("stale OpenCode database cleanup") {
            pruneOpenCodeDatabases(projectDir.resolve("data"), File(System.getProperty("java.io.tmpdir")), now)
        }
    }

    fun logDirectory(): File = File(
        System.getProperty("PIPELINE_LOG_DIR") ?: Env.get("PIPELINE_LOG_DIR") ?: "logs"
    )

    fun pruneRunLogs(directory: File, maxFiles: Int = RUN_LOG_LIMIT, maxAgeDays: Long = RUN_LOG_DAYS, now: Instant = Instant.now()) {
        if (!directory.isDirectory) return
        val cutoff = now.minus(Duration.ofDays(maxAgeDays))
        val logs = directory.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: return
        logs.forEachIndexed { index, file ->
            if (index >= maxFiles || Instant.ofEpochMilli(file.lastModified()).isBefore(cutoff)) {
                runCatching { Files.deleteIfExists(file.toPath()) }
                    .onFailure { cleanupLog.warn(it) { "Could not prune run log ${file.path}" } }
            }
        }
    }

    /**
     * Removes CAS staging directories whose writer is gone. A staging directory
     * belongs to the process named in it (`.staging-p<pid>-...`), and another
     * pipeline process may be writing one right now, so only directories of
     * dead processes are removed. Directories from before owner names are
     * removed once they are older than [LEGACY_STAGING_HOURS].
     */
    fun pruneCasStaging(stagesDirectory: File, now: Instant = Instant.now()) {
        if (!stagesDirectory.isDirectory) return
        val legacyCutoff = now.minus(Duration.ofHours(LEGACY_STAGING_HOURS)).toEpochMilli()
        stagesDirectory.listFiles()?.forEach { stage ->
            if (stage.isDirectory) stage.listFiles { child -> child.isDirectory && child.name.startsWith(".staging-") }
                ?.filter { dir ->
                    val owner = Regex("""^\${pipeline.cas.CasStaging.PREFIX}(\d+)-""")
                        .find(dir.name)?.groupValues?.get(1)?.toLongOrNull()
                    if (owner != null) {
                        ProcessHandle.of(owner).map { !it.isAlive }.orElse(true)
                    } else {
                        dir.lastModified() < legacyCutoff
                    }
                }
                ?.forEach { stale -> deleteTree(stale) }
        }
    }

    fun pruneOpenCodeDatabases(dataDirectory: File, tempDirectory: File, now: Instant = Instant.now()) {
        val cutoff = now.minus(Duration.ofHours(DB_AGE_HOURS)).toEpochMilli()
        val currentPid = ProcessHandle.current().pid()
        val dataFiles = dataDirectory.listFiles { file -> file.name.matches(Regex("opencode-agent-\\d+\\.db(?:-(?:wal|shm)|-journal)?")) }
            .orEmpty()
        dataFiles.forEach { file ->
            val pid = Regex("opencode-agent-(\\d+)\\.db.*").matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (pid != currentPid && (pid == null || ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) && file.lastModified() < cutoff) {
                runCatching { Files.deleteIfExists(file.toPath()) }
                    .onFailure { cleanupLog.warn(it) { "Could not prune stale OpenCode DB ${file.path}" } }
            }
        }
        tempDirectory.listFiles { file -> file.name.matches(Regex("opencode-pipeline-.*\\.db(?:-(?:wal|shm)|-journal)?")) }
            .orEmpty().filter { it.lastModified() < cutoff }.forEach { file ->
                runCatching { Files.deleteIfExists(file.toPath()) }
                    .onFailure { cleanupLog.warn(it) { "Could not prune stale OpenCode temp DB ${file.path}" } }
            }
    }

    fun deleteTree(directory: File) {
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            if (!file.delete() && file.exists()) cleanupLog.warn { "Could not delete ${file.path}" }
        }
    }

    private inline fun bestEffort(label: String, action: () -> Unit) {
        runCatching(action).onFailure { cleanupLog.warn(it) { "Best-effort $label failed" } }
    }
}
