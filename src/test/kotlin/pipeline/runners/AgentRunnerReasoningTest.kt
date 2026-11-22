package pipeline.runners

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pipeline.stages.AgentEventType

class AgentRunnerReasoningTest {

    private data class MessageFixture(
        val id: String,
        val sessionId: String,
        val type: String,
        val seq: Int,
        val timeCreated: Long,
        val data: String
    )

    @Test
    fun `runner reconciles persisted reasoning and preserves existing event types`() = runBlocking {
        val dir = Files.createTempDirectory("agent-reasoning-test").toFile()
        try {
            val database = File(dir, "opencode.db")
            createOpenCodeDb(database)
            val script = fakeOpenCode(
                dir,
                """
                {"type":"step_start","sessionID":"session-a","part":{"id":"step-1","messageID":"message-1"}}
                {"type":"reasoning","sessionID":"session-a","part":{"id":"stream-reason-1","messageID":"message-1","text":"stream copy"}}
                {"type":"text","sessionID":"session-a","part":{"text":"answer text"}}
                {"type":"tool_use","sessionID":"session-a","part":{"tool":"Read","state":{"status":"completed","input":{"file":"A.java"},"output":"file contents"}}}
                {"type":"step_finish","sessionID":"session-a","part":{"tokens":{"input":4,"output":5,"total":9}}}
                """.trimIndent()
            )
            val callbacks = mutableListOf<pipeline.stages.AgentTranscriptEvent>()
            val result = CoroutineOpenCodeRunner(
                executable = script.absolutePath,
                databasePath = database.absolutePath
            ).runAgent(
                prompt = "prompt",
                cwd = dir,
                agentRole = "test",
                model = "fake/model",
                maxSteps = 10,
                timeoutSeconds = 10,
                seed = 0,
                temperature = 0.0,
                onEvent = { callbacks += it }
            )

            assertEquals(0, result.exitCode)
            assertEquals(1, result.stepsCounted)
            val reasoning = result.transcript.filter { it.eventType == AgentEventType.REASONING }
            assertEquals(listOf("database reasoning", "database-only reasoning"), reasoning.map { it.content })
            assertEquals(listOf(1, 2), reasoning.map { it.turnIndex })
            assertEquals(listOf(7, 2), reasoning.map { it.reasoningTokens })
            assertFalse(result.transcript.any { it.content == "stream copy" })
            assertFalse(result.transcript.any { it.content == "other session reasoning" })

            assertTrue(result.transcript.any { it.eventType == AgentEventType.INITIAL_PROMPT && it.content == "prompt" })
            assertTrue(result.transcript.any { it.eventType == AgentEventType.STEP_START })
            assertTrue(result.transcript.any { it.eventType == AgentEventType.MESSAGE && it.content == "answer text" })
            assertTrue(result.transcript.any { it.eventType == AgentEventType.TOOL_CALL && it.toolName == "Read" })
            assertTrue(result.transcript.any { it.eventType == AgentEventType.TOOL_RESULT && it.toolResult == "file contents" })
            val finish = result.transcript.single { it.eventType == AgentEventType.STEP_FINISH }
            assertEquals(9, finish.tokenUsage?.totalTokens)
            assertEquals(2, callbacks.count { it.eventType == AgentEventType.REASONING })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stream reasoning remains typed when the run database has no reasoning rows`() = runBlocking {
        val dir = Files.createTempDirectory("agent-stream-reasoning-test").toFile()
        try {
            val script = fakeOpenCode(
                dir,
                """
                {"type":"step_start","sessionID":"session-stream","part":{"id":"step-1"}}
                {"type":"reasoning","sessionID":"session-stream","part":{"id":"reason-1","text":"stream-only reasoning","tokens":{"reasoning":3}}}
                {"type":"text","sessionID":"session-stream","part":{"text":"answer"}}
                """.trimIndent()
            )
            val result = CoroutineOpenCodeRunner(executable = script.absolutePath).runAgent(
                prompt = "prompt",
                cwd = dir,
                agentRole = "test",
                model = "fake/model",
                maxSteps = 10,
                timeoutSeconds = 10,
                seed = 0,
                temperature = 0.0
            )

            val reasoning = result.transcript.single { it.eventType == AgentEventType.REASONING }
            assertEquals("stream-only reasoning", reasoning.content)
            assertEquals(3, reasoning.reasoningTokens)
            assertTrue(result.transcript.any { it.eventType == AgentEventType.MESSAGE && it.content == "answer" })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Builds a run DB with OpenCode 2.x's `session_message` layout. */
    private fun createOpenCodeDb(file: File) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE TABLE session_message (
                       id TEXT PRIMARY KEY, session_id TEXT NOT NULL, type TEXT NOT NULL, seq INTEGER NOT NULL,
                       time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, data TEXT NOT NULL)"""
                )
            }
            connection.prepareStatement(
                "INSERT INTO session_message (id,session_id,type,seq,time_created,time_updated,data) VALUES (?,?,?,?,?,?,?)"
            ).use { statement ->
                listOf(
                    MessageFixture("message-1", "session-a", "assistant", 1, 1_000L,
                        """{"content":[{"type":"reasoning","text":"database reasoning"},{"type":"text","text":"x"}],"tokens":{"reasoning":7}}"""),
                    MessageFixture("message-2", "session-a", "assistant", 2, 2_000L,
                        """{"content":[{"type":"text","text":"y"},{"type":"reasoning","text":"database-only reasoning"}],"tokens":{"reasoning":2}}"""),
                    MessageFixture("other-message", "session-other", "assistant", 1, 3_000L,
                        """{"content":[{"type":"reasoning","text":"other session reasoning"}],"tokens":{"reasoning":99}}"""),
                    MessageFixture("user-message", "session-a", "user", 3, 4_000L,
                        """{"content":[{"type":"reasoning","text":"user reasoning"}],"tokens":{"reasoning":100}}""")
                ).forEach { values ->
                    statement.setString(1, values.id)
                    statement.setString(2, values.sessionId)
                    statement.setString(3, values.type)
                    statement.setInt(4, values.seq)
                    statement.setLong(5, values.timeCreated)
                    statement.setLong(6, values.timeCreated)
                    statement.setString(7, values.data)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun fakeOpenCode(dir: File, output: String): File {
        val script = File(dir, "fake-opencode")
        script.writeText("#!/bin/sh\ncat <<'EVENTS'\n$output\nEVENTS\n")
        assertTrue(script.setExecutable(true), "could not make fake executable")
        return script
    }
}
