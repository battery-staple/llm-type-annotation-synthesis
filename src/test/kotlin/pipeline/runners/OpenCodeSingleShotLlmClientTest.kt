package pipeline.runners

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pipeline.ports.AgentRunResult
import pipeline.ports.AgentRunner
import pipeline.ports.SingleShotFailure
import pipeline.ports.SingleShotLlmException
import pipeline.stages.AgentEventType
import pipeline.stages.AgentTranscriptEvent

class OpenCodeSingleShotLlmClientTest {

    private class RecordingRunner(private val result: AgentRunResult) : AgentRunner {
        lateinit var prompt: String
        lateinit var cwd: File
        var environment: Map<String, String> = emptyMap()
        var maxSteps: Int = -1

        override suspend fun runAgent(
            prompt: String,
            cwd: File,
            agentRole: String,
            model: String,
            maxSteps: Int,
            timeoutSeconds: Long,
            seed: Long,
            temperature: Double,
            environment: Map<String, String>,
            onEvent: (suspend (AgentTranscriptEvent) -> Unit)?
        ): AgentRunResult {
            this.prompt = prompt
            this.cwd = cwd
            this.environment = environment
            this.maxSteps = maxSteps
            return result
        }
    }

    private fun event(
        type: AgentEventType,
        turn: Int = 1,
        content: String? = null,
        toolName: String? = null
    ) = AgentTranscriptEvent(
        timestamp = "2026-01-01T00:00:00Z",
        eventType = type,
        turnIndex = turn,
        content = content,
        toolName = toolName
    )

    private fun result(exitCode: Int, transcript: List<AgentTranscriptEvent>, timedOut: Boolean = false) =
        AgentRunResult(
            exitCode = exitCode,
            stepsCounted = transcript.count { it.eventType == AgentEventType.STEP_START },
            transcript = transcript,
            timedOut = timedOut
        )

    @Test
    fun `triage passes an inline no-tools opencode config and still decodes valid JSON`() = runBlocking {
        val classification = """{"isBugfix":true,"bugfixScore":0.9,"category":"bugfix","rationale":"ok","regressionTest":{"testClass":"com.example.FooTest","testMethod":"testBar"}}"""
        val transcript = listOf(event(AgentEventType.INITIAL_PROMPT, 0), event(AgentEventType.MESSAGE, 1, classification))
        val runner = RecordingRunner(result(0, transcript))
        val client = OpenCodeSingleShotLlmClient(agentRunner = runner)

        val response = client.classifyCommit("prompt", "model", 0.0)

        assertEquals(true, response.value.isBugfix)
        assertEquals(transcript, response.transcript)
        assertEquals(5, runner.maxSteps)
        assertTrue(runner.cwd.name.startsWith("triage-llm-"))
        assertTrue(runner.prompt.contains("Respond with exactly one JSON object matching this schema"))

        val configText = runner.environment["OPENCODE_CONFIG_CONTENT"]
        assertNotNull(configText)
        val config = Json.parseToJsonElement(configText).jsonObject
        assertEquals("pipeline-triage-json", config["default_agent"]?.jsonPrimitive?.content)
        val agent = requireNotNull(config["agents"]).jsonObject["pipeline-triage-json"]
        assertNotNull(agent)
        assertEquals("1", agent.jsonObject["steps"]?.jsonPrimitive?.content)
        val permissions = requireNotNull(agent.jsonObject["permissions"]).jsonArray
        assertTrue(
            permissions.any {
                it.jsonObject["action"]?.jsonPrimitive?.content == "*" &&
                    it.jsonObject["effect"]?.jsonPrimitive?.content == "deny"
            }
        )
    }

    @Test
    fun `step cap termination reports the tool loop instead of a bare exit code`() = runBlocking {
        val transcript = listOf(event(AgentEventType.INITIAL_PROMPT, 0)) +
            (1..5).flatMap {
                listOf(
                    event(AgentEventType.STEP_START, it),
                    event(AgentEventType.TOOL_CALL, it, toolName = if (it % 2 == 0) "websearch" else "webfetch")
                )
            }
        val client = OpenCodeSingleShotLlmClient(agentRunner = RecordingRunner(result(130, transcript)))

        val failure = assertFailsWith<SingleShotLlmException> { client.classifyCommit("prompt", "model", 0.0) }

        assertEquals(SingleShotFailure.STEP_CAP, failure.reason)
        assertEquals(transcript, failure.transcript)
        assertTrue(failure.message!!.contains("step cap"), failure.message)
        assertTrue(failure.message!!.contains("webfetch=3, websearch=2"), failure.message)
        assertTrue(failure.message!!.contains("messages=0"), failure.message)
        assertTrue(failure.message!!.contains("OPENCODE_CONFIG_CONTENT"), failure.message)
    }

    @Test
    fun `unparseable model message includes a preview and the decode error`() = runBlocking {
        val transcript = listOf(
            event(AgentEventType.INITIAL_PROMPT, 0, "prompt with a JSON example"),
            event(AgentEventType.STEP_START, 1),
            event(AgentEventType.MESSAGE, 1, "Result: {\"isBugfix\": true}")
        )
        val client = OpenCodeSingleShotLlmClient(agentRunner = RecordingRunner(result(0, transcript)))

        val failure = assertFailsWith<SingleShotLlmException> { client.classifyCommit("prompt", "model", 0.0) }

        assertEquals(SingleShotFailure.NO_JSON, failure.reason)
        assertTrue(failure.message!!.contains("no parseable TriageClassification JSON"), failure.message)
        assertTrue(failure.message!!.contains("jsonCandidates=1"), failure.message)
        assertTrue(failure.message!!.contains("lastDecodeError="), failure.message)
        assertTrue(failure.message!!.contains("Result:"), failure.message)
    }

    @Test
    fun `timeout and message-less failures are distinguished`() = runBlocking {
        val timeoutClient = OpenCodeSingleShotLlmClient(
            agentRunner = RecordingRunner(result(-1, listOf(event(AgentEventType.STEP_START, 1)), timedOut = true))
        )
        val timeout = assertFailsWith<SingleShotLlmException> { timeoutClient.classifyCommit("prompt", "model", 0.0) }
        assertEquals(SingleShotFailure.TIMEOUT, timeout.reason)
        assertTrue(timeout.message!!.contains("timed out"), timeout.message)

        val crashedClient = OpenCodeSingleShotLlmClient(
            agentRunner = RecordingRunner(result(1, listOf(event(AgentEventType.STEP_START, 1))))
        )
        val crashed = assertFailsWith<SingleShotLlmException> { crashedClient.classifyCommit("prompt", "model", 0.0) }
        assertEquals(SingleShotFailure.NO_MESSAGE, crashed.reason)
        assertTrue(crashed.message!!.contains("no final text message"), crashed.message)
    }
}
