package pipeline.cas

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pipeline.stages.CasKey
import pipeline.stages.PreflightStage
import pipeline.stages.StageName
import pipeline.util.FileOps
import pipeline.util.RuntimeCleanup

/**
 * Bundles with a registered directory other than `custom_quals_src` (such as
 * preflight's logs) were never valid: `validateArtifacts` treated only the
 * synthesis directory as a directory, so `lookup` always returned null.
 */
class CasDirectoryArtifactTest {

    private val key = CasKey.fromHex("c".repeat(64))

    private fun publishBundle(root: File): File {
        val cas = CasEngine(root.resolve("stages"))
        val staging = cas.createStagingDir(StageName.PREFLIGHT)
        val writer = StageArtifactWriter(StageName.PREFLIGHT, staging)
        writer.writeText(PreflightStage.REPORT_FILE, "{}")
        staging.resolve(PreflightStage.LOGS_DIR).mkdirs()
        staging.resolve("${PreflightStage.LOGS_DIR}/P1-probe.log").writeText("probe output")
        writer.registerDirectory(PreflightStage.LOGS_DIR)
        return cas.publish(StageName.PREFLIGHT, key, staging, writer.buildManifest(key))
    }

    @Test
    fun `a bundle with a registered directory is found again`() = withDir { root ->
        publishBundle(root)
        assertNotNull(CasEngine(root.resolve("stages")).lookup(StageName.PREFLIGHT, key))
    }

    @Test
    fun `a changed, removed, or added file in the directory invalidates the bundle`() = withDir { root ->
        val bundle = publishBundle(root)
        val log = bundle.resolve("${PreflightStage.LOGS_DIR}/P1-probe.log")
        val cas = CasEngine(root.resolve("stages"))

        log.writeText("tampered")
        assertNull(cas.lookup(StageName.PREFLIGHT, key), "changed file")
        log.writeText("probe output")
        assertNotNull(cas.lookup(StageName.PREFLIGHT, key))

        log.delete()
        assertNull(cas.lookup(StageName.PREFLIGHT, key), "removed file")
        log.writeText("probe output")

        bundle.resolve("${PreflightStage.LOGS_DIR}/extra.log").writeText("added")
        assertNull(cas.lookup(StageName.PREFLIGHT, key), "added file")
    }

    /**
     * Startup cleanup in another process must recognize the owner encoded by
     * createStagingDir. The directory is back-dated past the legacy age limit,
     * so only the owner check can keep it.
     */
    @Test
    fun `staging directories carry their owner and survive another process's cleanup`() = withDir { root ->
        val stages = root.resolve("stages")
        val staging = CasEngine(stages).createStagingDir(StageName.PREFLIGHT)
        assertTrue(staging.name.startsWith("${CasStaging.PREFIX}${ProcessHandle.current().pid()}-"), staging.name)
        staging.setLastModified(System.currentTimeMillis() - 7 * 3600_000L)

        RuntimeCleanup.pruneCasStaging(stages)

        assertTrue(staging.isDirectory, "a live owner's staging directory must survive")
        assertEquals(1, stages.resolve(StageName.PREFLIGHT.identifier).listFiles()!!.size)
    }

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("cas-directory-artifact").toFile()
        try {
            block(dir)
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }
}
