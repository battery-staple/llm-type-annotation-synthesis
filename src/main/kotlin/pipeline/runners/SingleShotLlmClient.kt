package pipeline.runners

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import pipeline.ports.AgentRunner
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
        val result = runner.runAgent(
            prompt = fullPrompt,
            cwd = scratch,
            agentRole = "triage",
            model = model,
            maxSteps = 5,
            timeoutSeconds = 300,
            seed = 0L,
            temperature = temperature
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
        val value = decodeLastClassification(messageContents)
            ?: throw SingleShotLlmException(
                "Single-shot LLM produced no parseable TriageClassification JSON " +
                    "(exit=${result.exitCode}, messages=${messageContents.size}, totalEvents=${result.transcript.size})",
                transcript = result.transcript
            )
        return SingleShotLlmResponse(
            value = value,
            usage = usage,
            latencyMs = latencyMs,
            transcript = result.transcript
        )
    }

    private fun decodeLastClassification(contents: List<String>): TriageClassification? {
        val candidates = mutableListOf<String>()
        for (content in contents) {
            candidates.addAll(extractJsonObjects(content))
        }
        for (candidate in candidates.asReversed()) {
            runCatching {
                val element = json.parseToJsonElement(candidate)
                if (element is JsonObject) {
                    val normalized = normalizeRegressionTest(element)
                    return json.decodeFromJsonElement(TriageClassification.serializer(), normalized)
                }
            }
        }
        return null
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
