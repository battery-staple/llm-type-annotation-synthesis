package pipeline.runners

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentRunnerIsolationTest {

    @Test
    fun `agent process always receives a private database`() = runBlocking {
        val dir = Files.createTempDirectory("agent-runner-test").toFile()
        try {
            val capture = File(dir, "capture.txt")
            val script = File(dir, "fake-opencode")
            script.writeText(
                """
                #!/bin/sh
                printf '%s' "${'$'}OPENCODE_DB" > "${'$'}CAPTURE"
                exit 0
                """.trimIndent() + "\n"
            )
            assertTrue(script.setExecutable(true), "could not make fake executable")

            val sharedDb = File(dir, "shared.db").absolutePath
            val runner = CoroutineOpenCodeRunner(
                executable = script.absolutePath,
                baseEnvironment = mapOf("OPENCODE_DB" to sharedDb)
            )

            val result = runner.runAgent(
                prompt = "ignored",
                cwd = dir,
                agentRole = "test",
                model = "fake/model",
                maxSteps = 1,
                timeoutSeconds = 10,
                seed = 0,
                temperature = 0.0,
                environment = mapOf("CAPTURE" to capture.absolutePath)
            )

            assertEquals(0, result.exitCode)
            val usedDb = capture.readText().trim()
            assertNotEquals(sharedDb, usedDb, "inherited OPENCODE_DB must be overridden")
            assertTrue(
                usedDb.startsWith(System.getProperty("java.io.tmpdir")),
                "isolated DB should live in the temp directory, was: $usedDb"
            )
            assertFalse(File(usedDb).exists(), "isolated DB should be deleted after the run")
        } finally {
            dir.deleteRecursively()
        }
    }
}
