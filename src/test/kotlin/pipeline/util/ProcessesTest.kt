package pipeline.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessesTest {

    @Test
    fun `PWD matches the requested working directory`() {
        val dir = Files.createTempDirectory("processes-test")
        try {
            val result = Processes.run(
                listOf("sh", "-c", "printf %s \"\$PWD\""),
                dir.toFile(),
                timeoutSeconds = 30
            )

            assertTrue(result.exitCode == 0, result.stderr)
            assertEquals(dir.toFile().absolutePath, result.stdout.trim())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
