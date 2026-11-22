package pipeline.util

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pipeline.stages.AgentEventType
import pipeline.stages.AgentTranscriptEvent

class TranscriptStreamTest {
    private fun event(turn: Int, content: String) =
        AgentTranscriptEvent(timestamp = "t", eventType = AgentEventType.MESSAGE, turnIndex = turn, content = content)

    @Test
    fun `append writes one complete json line per event and delete removes the file`() {
        val root = Files.createTempDirectory("stream-test").toFile()
        try {
            val stream = TranscriptStreams.forStep(root, "run-1", "step-1")
            stream.append(event(1, "multi\nline"))
            stream.append(event(2, "second"))

            val lines = stream.file.readLines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].contains("\"turn_index\":1"))
            assertTrue(lines[0].contains("multi\\nline"), "newlines inside content must be escaped")

            stream.delete()
            assertFalse(stream.file.exists())
            stream.append(event(3, "after delete")) // disabled after delete; must not recreate
            assertFalse(stream.file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `current finds the stream installed in the coroutine context`() = runBlocking {
        val root = Files.createTempDirectory("stream-test").toFile()
        try {
            assertNull(TranscriptStream.current())
            val stream = TranscriptStreams.forStep(root, "r", "s")
            withContext(stream) { assertEquals(stream, TranscriptStream.current()) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sweep removes only files older than the max age and empties run dirs`() {
        val root = Files.createTempDirectory("stream-test").toFile()
        try {
            val old = TranscriptStreams.forStep(root, "run-old", "s1").also { it.append(event(1, "x")) }
            val fresh = TranscriptStreams.forStep(root, "run-new", "s2").also { it.append(event(1, "x")) }
            old.file.setLastModified(Instant.now().minus(Duration.ofDays(30)).toEpochMilli())

            TranscriptStreams.sweep(root, Duration.ofDays(14))

            assertFalse(old.file.exists())
            assertFalse(old.file.parentFile.exists())
            assertTrue(fresh.file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
