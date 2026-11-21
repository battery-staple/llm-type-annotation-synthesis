package pipeline.stages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import pipeline.ports.AgentRunResult

class StageSupportTest {

    private fun message(text: String) = AgentTranscriptEvent(
        timestamp = "2026-01-01T00:00:00Z",
        eventType = AgentEventType.MESSAGE,
        turnIndex = 1,
        content = text
    )

    @Test
    fun `successful agent has no failure message`() {
        val result = AgentRunResult(exitCode = 0, stepsCounted = 3, transcript = emptyList(), timedOut = false)

        assertNull(agentFailureMessage(result))
        assertNull(agentStartupFailure(result))
    }

    @Test
    fun `startup failure surfaces stderr and halts retries`() {
        val result = AgentRunResult(
            exitCode = 1,
            stepsCounted = 0,
            transcript = listOf(message("stderr:\nunknown model: opencode-go/muse-spark-1.2-contributor")),
            timedOut = false
        )

        val startup = agentStartupFailure(result)
        assertNotNull(startup)
        assertEquals(
            "agent exited with code 1: stderr:\nunknown model: opencode-go/muse-spark-1.2-contributor",
            startup
        )
    }

    @Test
    fun `nonzero exit after completing steps is not a startup failure`() {
        val result = AgentRunResult(
            exitCode = 2,
            stepsCounted = 3,
            transcript = listOf(message("stderr:\nconnection reset")),
            timedOut = false
        )

        assertNotNull(agentFailureMessage(result))
        assertNull(agentStartupFailure(result))
    }

    @Test
    fun `timeout is reported but not treated as a startup failure`() {
        val result = AgentRunResult(
            exitCode = -1,
            stepsCounted = 0,
            transcript = emptyList(),
            timedOut = true
        )

        assertEquals("agent timed out", agentFailureMessage(result))
        assertNull(agentStartupFailure(result))
    }
}
