package pipeline.runners

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import pipeline.ports.AgentRunResult
import pipeline.ports.AgentRunner
import pipeline.ports.SingleShotFailure
import pipeline.ports.SingleShotLlmClient
import pipeline.ports.SingleShotLlmException
import pipeline.ports.SingleShotLlmResponse
import pipeline.ports.TriageClassification
import pipeline.stages.AgentEventType
import pipeline.stages.TokenUsage

/**
 * Single-turn JSON classifier used by the deferred crawler. Reuses the
 * OpenCode CLI in JSON mode and decodes the last JSON object found in MESSAGE
 * events as a [TriageClassification].
 */
class OpenCodeSingleShotLlmClient(
    private val executable: String = "opencode",
    private val agentRunner: AgentRunner? = null
) : SingleShotLlmClient {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Triage is a classifier, not a coding agent: it must answer from the prompt
     * alone. The config is injected via the environment so no files are written
     * into the agent workspace and project-level opencode configs cannot
     * re-enable tools. `steps = 1` makes the first (final) step tool-free; the
     * deny-all permission rule is a second layer.
     */
    private val noToolsConfig = """
        {"default_agent":"pipeline-triage-json","agents":{"pipeline-triage-json":{"mode":"primary","steps":1,"permissions":[{"action":"*","resource":"*","effect":"deny"}]}}}
    """.trimIndent()

    override suspend fun classifyCommit(
        prompt: String,
        model: String,
        temperature: Double
    ): SingleShotLlmResponse<TriageClassification> {
        val runner = agentRunner ?: CoroutineOpenCodeRunner(executable = executable)
        val scratch = Files.createTempDirectory("triage-llm-").toFile()
        val fullPrompt = buildString {
            append(prompt)
            append("\n\nRespond with exactly one JSON object matching this schema and nothing else:\n")
            append("{\"isBugfix\": boolean, \"bugfixScore\": number 0..1, ")
            append("\"category\": \"bugfix\" | \"non_bugfix\", \"rationale\": string, ")
            append("\"regressionTest\": {\"testClass\": string, \"testMethod\": string} | null}")
        }
        val started = System.currentTimeMillis()
        val maxSteps = 5
        val result = runner.runAgent(
            prompt = fullPrompt,
            cwd = scratch,
            agentRole = "triage",
            model = model,
            maxSteps = maxSteps,
            timeoutSeconds = 300,
            seed = 0L,
            temperature = temperature,
            environment = mapOf("OPENCODE_CONFIG_CONTENT" to noToolsConfig)
        )
        val latencyMs = System.currentTimeMillis() - started
        runCatching { scratch.deleteRecursively() }

        var usage = TokenUsage()
        for (event in result.transcript) {
            if (event.eventType == AgentEventType.STEP_FINISH && event.tokenUsage != null) {
                val u = event.tokenUsage
                usage = usage.copy(
                    promptTokens = usage.promptTokens + u.promptTokens,
                    completionTokens = usage.completionTokens + u.completionTokens,
                    totalTokens = usage.totalTokens + u.totalTokens
                )
            }
        }
        val messageContents = result.transcript
            .filter { it.eventType == AgentEventType.MESSAGE }
            .mapNotNull { it.content }
        val steps = result.transcript.count { it.eventType == AgentEventType.STEP_START }
        val reason = when {
            result.timedOut -> SingleShotFailure.TIMEOUT
            result.exitCode == 130 && steps >= maxSteps && messageContents.isEmpty() -> SingleShotFailure.STEP_CAP
            messageContents.isEmpty() -> SingleShotFailure.NO_MESSAGE
            else -> SingleShotFailure.NO_JSON
        }
        val decoded = decodeLastClassification(messageContents)
        if (decoded.value == null) {
            throw SingleShotLlmException(
                buildFailureMessage(reason, result, maxSteps, messageContents, decoded),
                transcript = result.transcript,
                reason = reason
            )
        }
        val value = decoded.value
        return SingleShotLlmResponse(
            value = value,
            usage = usage,
            latencyMs = latencyMs,
            transcript = result.transcript
        )
    }

    private data class DecodeResult(
        val value: TriageClassification?,
        val candidateCount: Int,
        val lastError: String?
    )

    private fun decodeLastClassification(contents: List<String>): DecodeResult {
        val candidates = mutableListOf<String>()
        for (content in contents) {
            candidates.addAll(extractJsonObjects(content))
        }
        var lastError: String? = null
        for (candidate in candidates.asReversed()) {
            runCatching {
                val element = json.parseToJsonElement(candidate)
                if (element is JsonObject) {
                    val normalized = normalizeRegressionTest(element)
                    return DecodeResult(
                        json.decodeFromJsonElement(TriageClassification.serializer(), normalized),
                        candidates.size,
                        null
                    )
                }
            }.onFailure { lastError = it.message ?: it.javaClass.simpleName }
        }
        return DecodeResult(null, candidates.size, lastError)
    }

    private fun buildFailureMessage(
        reason: SingleShotFailure,
        result: AgentRunResult,
        maxSteps: Int,
        messages: List<String>,
        decoded: DecodeResult
    ): String {
        val steps = result.transcript.count { it.eventType == AgentEventType.STEP_START }
        val toolCalls = result.transcript.filter { it.eventType == AgentEventType.TOOL_CALL }
        val tools = toolCalls.groupingBy { it.toolName ?: "unknown" }.eachCount()
            .toList().sortedByDescending { it.second }.take(5)
            .joinToString(", ") { "${it.first}=${it.second}" }
            .ifEmpty { "none" }
        val tail = "exit=${result.exitCode}, timedOut=${result.timedOut}, steps=$steps, " +
            "toolCalls=${toolCalls.size} [$tools], messages=${messages.size}"
        val headline = when (reason) {
            SingleShotFailure.TIMEOUT -> "Triage classifier timed out before emitting a final text message"
            SingleShotFailure.STEP_CAP -> "Triage classifier hit the $maxSteps-step cap and was terminated before emitting a final text message"
            SingleShotFailure.NO_MESSAGE -> "Triage classifier emitted no final text message"
            SingleShotFailure.NO_JSON -> "Triage classifier emitted ${messages.size} message(s) but no parseable TriageClassification JSON"
        }
        val detail = if (reason == SingleShotFailure.NO_JSON) {
            val preview = messages.lastOrNull()?.replace(Regex("\\s+"), " ")?.take(240).orEmpty()
            "; jsonCandidates=${decoded.candidateCount}, lastDecodeError=${decoded.lastError?.take(160)}, lastMessage=$preview"
        } else {
            "; triage runs with tools disabled via OPENCODE_CONFIG_CONTENT — verify that config and the model response"
        }
        return "$headline ($tail)$detail"
    }

    /**
     * Accepts both `{"testClass": ..., "testMethod": ...}` and the nested
     * `{"regressionTest": {"testClass": {"value": ...}, ...}}` renderings by
     * unwrapping single-key `value` objects.
     */
    private fun normalizeRegressionTest(element: JsonObject): JsonObject {
        val regression = element["regressionTest"] as? JsonObject ?: return element
        val testClass = unwrapValue(regression["testClass"]) ?: return element
        val testMethod = unwrapValue(regression["testMethod"]) ?: return element
        val rebuilt = buildMap {
            putAll(element)
            put(
                "regressionTest",
                JsonObject(
                    mapOf(
                        "testClass" to testClass,
                        "testMethod" to testMethod
                    )
                )
            )
        }
        return JsonObject(rebuilt)
    }

    private fun unwrapValue(element: kotlinx.serialization.json.JsonElement?): kotlinx.serialization.json.JsonElement? {
        if (element == null) return null
        if (element is JsonObject && element.size == 1 && element["value"] != null) return element["value"]
        if (element is JsonObject) {
            val nested = element["value"]
            if (nested != null && element.size == 1) return nested
        }
        return element
    }

    /** Balanced-brace scan that tolerates braces inside JSON strings. */
    private fun extractJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            results.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return results
    }

    @Suppress("unused")
    private fun fallbackCategory(content: String?): String? =
        content?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonObject)?.get("category")?.jsonPrimitive?.contentOrNull }
}
