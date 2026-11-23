package pipeline.util

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeCleanupTest {
    @Test
    fun `run log pruning removes expired and excess logs only`() {
        val root = Files.createTempDirectory("run-logs").toFile()
        try {
            val now = Instant.now()
            val keep = File(root, "new.log").apply { writeText("keep") }
            val excess = File(root, "older.log").apply { writeText("excess") }
            val expired = File(root, "expired.log").apply { writeText("expired") }
            keep.setLastModified(now.minusSeconds(60).toEpochMilli())
            excess.setLastModified(now.minusSeconds(120).toEpochMilli())
            expired.setLastModified(now.minusSeconds(20 * 86400L).toEpochMilli())

            RuntimeCleanup.pruneRunLogs(root, maxFiles = 1, maxAgeDays = 14, now = now)

            assertTrue(keep.exists())
            assertFalse(excess.exists())
            assertFalse(expired.exists())
        } finally {
            RuntimeCleanup.deleteTree(root)
        }
    }

    /** A pipeline process starting up must not delete a bundle another live process is writing. */
    @Test
    fun `staging cleanup keeps directories of live processes`() {
        val root = Files.createTempDirectory("staging-owners").toFile()
        try {
            val stage = File(root, "stages/preflight").apply { mkdirs() }
            val live = File(stage, ".staging-p${ProcessHandle.current().pid()}-1-1").apply { mkdirs() }
            val dead = File(stage, ".staging-p${Long.MAX_VALUE / 2}-1-1").apply { mkdirs() }
            val legacyFresh = File(stage, ".staging-123-1").apply { mkdirs() }

            RuntimeCleanup.pruneCasStaging(File(root, "stages"))

            assertTrue(live.exists())
            assertFalse(dead.exists())
            assertTrue(legacyFresh.exists(), "an unowned directory may still be in use until it is old")
        } finally {
            RuntimeCleanup.deleteTree(root)
        }
    }

    @Test
    fun `startup scavengers remove staging and stale temp databases but preserve fresh files`() {
        val root = Files.createTempDirectory("runtime-cleanup").toFile()
        try {
            val stages = File(root, "stages/ingestion/.staging-abandoned").apply { mkdirs() }
            stages.setLastModified(Instant.now().minusSeconds(7 * 3600L).toEpochMilli())
            val data = File(root, "data").apply { mkdirs() }
            val temp = File(root, "tmp").apply { mkdirs() }
            val stale = File(temp, "opencode-pipeline-old.db-wal").apply { writeText("stale") }
            val fresh = File(temp, "opencode-pipeline-current.db").apply { writeText("fresh") }
            val threshold = Instant.now().minusSeconds(25 * 3600L).toEpochMilli()
            stale.setLastModified(threshold)

            RuntimeCleanup.pruneCasStaging(File(root, "stages"))
            RuntimeCleanup.pruneOpenCodeDatabases(data, temp)

            assertFalse(stages.exists())
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
        } finally {
            RuntimeCleanup.deleteTree(root)
        }
    }
}
