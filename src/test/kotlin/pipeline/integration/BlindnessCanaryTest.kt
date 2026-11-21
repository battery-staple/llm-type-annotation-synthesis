package pipeline.integration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pipeline.util.FileOps
import pipeline.util.Processes

/**
 * Filesystem-level blindness canary. The sanitized export, not prompt wording or
 * Git environment scrubbing, is the isolation boundary.
 *
 * Network isolation is deliberately not asserted here: the execution container
 * forbids `unshare -n`, and `repair.internet_access=false` is currently a
 * best-effort flag (see deviations.md).
 */
class BlindnessCanaryTest {

    @Test
    fun `sanitized export excludes histories patches reports and pipeline state`() {
        val root = Files.createTempDirectory("blindness-canary-source").toFile()
        try {
            val source = File(root, "repo").apply { mkdirs() }
            File(source, ".git").mkdirs()
            File(source, ".git/COMMIT_EDITMSG").writeText("CANARY_COMMIT_MESSAGE")
            File(source, "src/main/java/com/example").mkdirs()
            File(source, "src/main/java/com/example/App.java").writeText("class App {}")
            File(source, "src/test/java/com/example").mkdirs()
            File(source, "src/test/java/com/example/TransferServiceTest.java")
                .writeText("class TransferServiceTest { void testTransferDebitsSourceAndCreditsDestination() {} }")
            File(source, "build/test-results").mkdirs()
            File(source, "build/test-results/test_results.xml").writeText("<testsuite/>")
            File(source, ".cache/stages").mkdirs()
            File(source, ".cache/stages/canary").writeText("CANARY_CAS")
            File(source, "data").mkdirs()
            File(source, "data/pipeline.db").writeText("CANARY_DB")
            File(source, "logs").mkdirs()
            File(source, "logs/pipeline.log").writeText("CANARY_LOG")
            File(source, "eval-fixtures").mkdirs()
            File(source, "eval-fixtures/secret-test.patch").writeText("CANARY_TEST_PATCH")
            File(source, "test_patch.diff").writeText("CANARY_PATCH_FILENAME")
            File(source, "triage_metadata.json").writeText("CANARY_TRIAGE")
            File(source, "repair.patch").writeText("CANARY_REPAIR")

            val destination = File(root, "workspace")
            FileOps.exportSanitized(source, destination, excludeTests = false, testReadOnly = true)

            val relativeFiles = destination.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(destination).invariantSeparatorsPath }
                .toSet()

            assertTrue("src/main/java/com/example/App.java" in relativeFiles)
            assertTrue("src/test/java/com/example/TransferServiceTest.java" in relativeFiles)
            assertFalse(relativeFiles.any { it.startsWith(".git/") }, "Git metadata leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it.startsWith("build/") }, "Build outputs leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it.startsWith(".cache/") }, "CAS leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it.startsWith("data/") }, "Database leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it.startsWith("logs/") }, "Logs leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it.startsWith("eval-fixtures/") }, "Fixtures leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it.endsWith(".patch") || it.endsWith(".diff") }, "Patches leaked: $relativeFiles")
            assertFalse(relativeFiles.any { it == "triage_metadata.json" }, "Triage metadata leaked")
            assertFalse(relativeFiles.any { it.endsWith("test_results.xml") }, "Test report leaked")

            val allText = destination.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            listOf(
                "CANARY_COMMIT_MESSAGE", "CANARY_CAS", "CANARY_DB", "CANARY_LOG",
                "CANARY_TEST_PATCH", "CANARY_PATCH_FILENAME", "CANARY_TRIAGE", "CANARY_REPAIR"
            ).forEach { canary ->
                assertFalse(allText.contains(canary), "Canary '$canary' leaked into the agent workspace")
            }

            val testFile = destination.resolve("src/test/java/com/example/TransferServiceTest.java")
            val writeFailed = runCatching { testFile.appendText("// tamper") }.isFailure
            assertTrue(writeFailed, "src/test must be read-only inside the repair/application workspace")

            val gitLog = Processes.run(listOf("git", "log"), destination, timeoutSeconds = 20)
            assertTrue(gitLog.exitCode != 0, "git log must fail in the sanitized workspace")

            val parentEscape = destination.resolve("../repo/eval-fixtures/secret-test.patch")
            assertFalse(FileOps.isWithin(parentEscape, destination), "A ../ path must not count as inside the workspace")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }
}
