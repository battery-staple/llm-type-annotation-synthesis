package pipeline.runners

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import pipeline.ports.AgentRunResult
import pipeline.ports.AgentRunner
import pipeline.stages.AgentEventType
import pipeline.stages.AgentTranscriptEvent
import pipeline.util.TranscriptStream
import pipeline.stages.TokenUsage
import pipeline.util.Env

private data class StreamReasoning(
    val sessionId: String?,
    val messageId: String?,
    val partId: String?,
    val event: AgentTranscriptEvent
)

private data class OpenCodeLineMetadata(
    val sessionId: String?,
    val messageId: String?,
    val partId: String?
)

/**
 * Spawns the OpenCode CLI (`opencode run --auto --format json`) and parses its
 * JSON event stream into a terminal transcript.
 *
 * Observed event shapes: `step_start`, `tool_use` (with `part.tool` /
 * `part.state.{status,input,output}`), `step_finish` (with
 * `part.tokens.{input,output}`), and `text` (with `part.text`).
 */
class CoroutineOpenCodeRunner(
    private val executable: String = "opencode",
    private val baseEnvironment: Map<String, String> = emptyMap(),
    // A pre-seeded per-run OpenCode DB. Unlike the blank temporary fallback,
    // this DB contains the OpenCode Go account/credential rows copied by
    // OpenCodeDb, so provider routing resolves opencode-go/* models.
    private val databasePath: String? = null
) : AgentRunner {

    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

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
    ): AgentRunResult = withContext(Dispatchers.IO) {
        val command = listOf(executable, "run", "--standalone", "--auto", "--format", "json", "-m", model, prompt)
        val builder = ProcessBuilder(command).directory(cwd)
        val env = builder.environment()
        env.remove("GIT_DIR")
        env.remove("GIT_WORK_TREE")
        env.remove("GIT_INDEX_FILE")
        env.putAll(baseEnvironment)
        env.putAll(environment)
        Env.applyTo(env)
        // ProcessBuilder changes the OS working directory but leaves PWD
        // inherited. The OpenCode CLI resolves its workspace from PWD, so
        // without this every agent writes into the parent pipeline checkout
        // instead of the isolated stage workspace.
        env["PWD"] = cwd.absolutePath
        env["PIPELINE_AGENT_SEED"] = seed.toString()
        env["PIPELINE_AGENT_TEMPERATURE"] = temperature.toString()
        // Isolate session storage: `run --standalone` still defaults to
        // ~/.local/share/opencode/opencode.db, so concurrent pipeline agents
        // plus SIGKILL in terminateTree() corrupts the user's chats
        // ("database disk image is malformed" -> every request 500s).
        // A throwaway DB outside cwd keeps pipeline writes off user sessions.
        // It lives outside cwd deliberately: stage code mirrors cwd into the
        // worktree, so anything inside cwd would leak into patches.
        // Always override an inherited OPENCODE_DB. A shared DB is never safe
        // here: concurrent runs, subagents, and SIGKILL during terminateTree()
        // can corrupt the user's session database.
        env.remove("OPENCODE_DB")
        val ownedDbPath = Files.createTempFile(
            "opencode-pipeline-${UUID.randomUUID()}-", ".db"
        ).toAbsolutePath().toString()
        runCatching { File(ownedDbPath).delete() }
        try {
            OpenCodeDb.cloneForInvocation(databasePath?.let(::File), File(ownedDbPath))
        } catch (t: Throwable) {
            deleteIsolatedDb(ownedDbPath)
            throw IllegalStateException("Could not seed private OpenCode database", t)
        }
        env["OPENCODE_DB"] = ownedDbPath

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            deleteIsolatedDb(ownedDbPath)
            throw t
        }
        // No interactive stdin: the CLI must see EOF or it may wait indefinitely.
        runCatching { process.outputStream.close() }
        val stdoutLines = Collections.synchronizedList(mutableListOf<String>())
        val stderrBuffer = StringBuilder()
        val outThread = Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { stdoutLines.add(it) }
            } catch (_: Exception) {
            }
        }, "opencode-stdout").apply { isDaemon = true; start() }
        val errThread = Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                }
            } catch (_: Exception) {
            }
        }, "opencode-stderr").apply { isDaemon = true; start() }

        val transcript = mutableListOf(
            AgentTranscriptEvent(
                timestamp = Instant.now().toString(),
                eventType = AgentEventType.INITIAL_PROMPT,
                turnIndex = 0,
                content = prompt
            )
        )
        val sessionIds = linkedSetOf<String>()
        val streamReasoning = mutableListOf<StreamReasoning>()
        var turnIndex = 0
        var stepsCounted = 0
        var parsed = 0
        var timedOut = false
        val startedNanos = System.nanoTime()
        var cumPrompt = 0L
        var cumCompletion = 0L
        var toolsThisTurn = 0

        // Progress logging: one INFO line per finished turn, one DEBUG line per tool call.
        // (The MDC turn_index is not used here: MDCContext restores the stage's value
        // each time the coroutine resumes, so the turn is put in the message instead.)
        fun logProgress(event: AgentTranscriptEvent) {
            when (event.eventType) {
                AgentEventType.STEP_START -> toolsThisTurn = 0
                AgentEventType.TOOL_CALL -> {
                    toolsThisTurn++
                    log.debug {
                        "agent[$agentRole] turn $turnIndex tool=${event.toolName} ${summarizeToolArgs(event.toolArguments)}"
                    }
                }
                AgentEventType.STEP_FINISH -> {
                    val usage = event.tokenUsage
                    cumPrompt += usage?.promptTokens ?: 0
                    cumCompletion += usage?.completionTokens ?: 0
                    val elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000L
                    log.info {
                        "agent[$agentRole] turn $turnIndex/$maxSteps done tools=$toolsThisTurn " +
                            "tokens in=${usage?.promptTokens ?: 0} out=${usage?.completionTokens ?: 0} " +
                            "(cum in=$cumPrompt out=$cumCompletion) elapsed=${elapsed / 60}m${"%02d".format(elapsed % 60)}s"
                    }
                }
                else -> {}
            }
        }

        val liveStream = TranscriptStream.current()

        suspend fun drainAvailable() {
            val batch: List<String> = synchronized(stdoutLines) {
                if (parsed >= stdoutLines.size) emptyList()
                else stdoutLines.subList(parsed, stdoutLines.size).toList()
            }
            for (line in batch) {
                parsed++
                if (line.isBlank()) continue
                val metadata = lineMetadata(line)
                metadata.sessionId?.let(sessionIds::add)
                val events = parseLine(line, turnIndex)
                for (event in events) {
                    if (event.eventType == AgentEventType.STEP_START) {
                        turnIndex++
                        stepsCounted++
                        val indexed = event.copy(turnIndex = turnIndex)
                        logProgress(indexed)
                        transcript.add(indexed)
                        onEvent?.invoke(indexed)
                        liveStream?.append(indexed)
                    } else {
                        val indexed = if (event.turnIndex <= 0) event.copy(turnIndex = maxOf(turnIndex, 1)) else event
                        logProgress(indexed)
                        if (indexed.eventType == AgentEventType.REASONING) {
                            // Buffer reasoning until the run DB can be reconciled;
                            // OpenCode may expose the same part in both sources.
                            streamReasoning += StreamReasoning(
                                sessionId = metadata.sessionId,
                                messageId = metadata.messageId,
                                partId = metadata.partId,
                                event = indexed
                            )
                        } else {
                            transcript.add(indexed)
                            onEvent?.invoke(indexed)
                            liveStream?.append(indexed)
                        }
                    }
                }
            }
        }

        val timeoutMs = timeoutSeconds.coerceAtLeast(1) * 1000L
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        var exitCode: Int? = null
        try {
            while (true) {
                drainAvailable()
                if (stepsCounted >= maxSteps && process.isAlive) {
                    terminateTree(process)
                    break
                }
                if (!process.isAlive) break
                if (System.nanoTime() >= deadlineNanos) {
                    timedOut = true
                    terminateTree(process)
                    break
                }
                delay(50)
            }
            // Reap the exit code with a short grace period after step-cap termination.
            val exited = process.waitFor(30, TimeUnit.SECONDS)
            if (!exited && process.isAlive) terminateTree(process)
            drainAvailable()
            exitCode = runCatching { if (process.isAlive) null else process.exitValue() }.getOrNull()
                ?: runCatching {
                    if (process.waitFor(30, TimeUnit.SECONDS)) process.exitValue() else null
                }.getOrNull()
        } catch (t: TimeoutCancellationException) {
            timedOut = true
            terminateTree(process)
            runCatching { drainAvailable() }
            exitCode = runCatching {
                if (process.waitFor(10, TimeUnit.SECONDS)) process.exitValue() else null
            }.getOrNull()
        } finally {
            outThread.join(5_000)
            errThread.join(5_000)
            runCatching { drainAvailable() }
            if (exitCode == null) {
                exitCode = runCatching {
                    if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else -1
                }.getOrDefault(-1)
            }
            try {
                val databaseFile = env["OPENCODE_DB"]?.let(::File)
                if (databaseFile != null && sessionIds.isNotEmpty()) {
                    val persisted = OpenCodeDb.readReasoning(databaseFile, sessionIds)
                    val persistedSessions = persisted.mapTo(mutableSetOf()) { it.sessionId }
                    val persistedPartIds = persisted.mapTo(mutableSetOf()) { it.partId }
                    val persistedMessages = persisted.mapTo(mutableSetOf()) { it.sessionId to it.messageId }
                    val streamFallback = streamReasoning.filterNot { streamed ->
                        when {
                            streamed.partId != null && streamed.partId in persistedPartIds -> true
                            streamed.sessionId != null && streamed.messageId != null &&
                                (streamed.sessionId to streamed.messageId) in persistedMessages -> true
                            streamed.sessionId != null && streamed.sessionId in persistedSessions &&
                                persisted.any {
                                    it.sessionId == streamed.sessionId &&
                                        it.turnIndex == streamed.event.turnIndex &&
                                        it.text == streamed.event.content
                                } -> true
                            streamed.sessionId == null && persisted.any {
                                it.turnIndex == streamed.event.turnIndex && it.text == streamed.event.content
                            } -> true
                            else -> false
                        }
                    }.map { it.event }
                    val dbEvents = persisted.map { part ->
                        AgentTranscriptEvent(
                            timestamp = runCatching { Instant.ofEpochMilli(part.timeCreated).toString() }
                                .getOrDefault(Instant.now().toString()),
                            eventType = AgentEventType.REASONING,
                            turnIndex = part.turnIndex,
                            content = part.text,
                            reasoningTokens = part.reasoningTokens
                        )
                    }
                    (streamFallback + dbEvents)
                        .sortedWith(compareBy<AgentTranscriptEvent>({ it.turnIndex }, { it.timestamp }))
                        .forEach { event ->
                            insertReasoningEvent(transcript, event)
                            runCatching { onEvent?.invoke(event) }
                        }
                } else {
                    if (databaseFile != null && sessionIds.isEmpty()) {
                        log.warn { "OpenCode stream did not expose a session ID; cannot associate persisted reasoning with this run" }
                    }
                    streamReasoning.map { it.event }
                        .sortedWith(compareBy<AgentTranscriptEvent>({ it.turnIndex }, { it.timestamp }))
                        .forEach { event ->
                            insertReasoningEvent(transcript, event)
                            runCatching { onEvent?.invoke(event) }
                        }
                }
            } catch (error: Exception) {
                log.warn(error) { "Could not reconcile OpenCode reasoning; retaining streamed reasoning" }
                streamReasoning.map { it.event }
                    .sortedWith(compareBy<AgentTranscriptEvent>({ it.turnIndex }, { it.timestamp }))
                    .forEach { event ->
                        insertReasoningEvent(transcript, event)
                        runCatching { onEvent?.invoke(event) }
                    }
            }
            ownedDbPath?.let { deleteIsolatedDb(it) }
        }

        val stderrText = synchronized(stderrBuffer) { stderrBuffer.toString() }.trim()
        val finalExit = exitCode ?: -1
        if (finalExit != 0 && stderrText.isNotEmpty()) {
            val event = AgentTranscriptEvent(
                timestamp = Instant.now().toString(),
                eventType = AgentEventType.MESSAGE,
                turnIndex = maxOf(turnIndex, 1),
                content = "stderr:\n$stderrText"
            )
            transcript.add(event)
            runCatching { onEvent?.invoke(event) }
        }
        AgentRunResult(exitCode = finalExit, stepsCounted = stepsCounted, transcript = transcript, timedOut = timedOut)
    }

    private fun summarizeToolArgs(args: Map<String, JsonElement>?): String {
        if (args.isNullOrEmpty()) return ""
        val preferred = listOf("command", "filePath", "path", "pattern", "url")
            .firstNotNullOfOrNull { key -> args[key]?.let { key to it } }
            ?: args.entries.first().toPair()
        val value = (preferred.second as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: preferred.second.toString()
        val flat = value.replace(Regex("\\s+"), " ").trim()
        val clipped = if (flat.length > 120) flat.take(120) + "…" else flat
        return "${preferred.first}=\"$clipped\""
    }

    private fun parseLine(line: String, currentTurn: Int): List<AgentTranscriptEvent> {
        val now = Instant.now().toString()
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull()
            ?: return listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.MESSAGE,
                    turnIndex = maxOf(currentTurn, 1),
                    content = line
                )
            )
        if (element !is JsonObject) {
            return listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.MESSAGE,
                    turnIndex = maxOf(currentTurn, 1),
                    content = line
                )
            )
        }
        val type = element["type"]?.jsonPrimitive?.contentOrNull
        val part = element["part"] as? JsonObject
        return when (type) {
            "step_start" -> listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.STEP_START,
                    turnIndex = currentTurn
                )
            )
            "tool_use" -> parseToolUse(part, line, now, currentTurn)
            "step_finish" -> listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.STEP_FINISH,
                    turnIndex = maxOf(currentTurn, 1),
                    tokenUsage = parseTokenUsage(part)
                )
            )
            "reasoning" -> listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.REASONING,
                    turnIndex = maxOf(currentTurn, 1),
                    content = part?.get("text")?.jsonPrimitive?.contentOrNull,
                    reasoningTokens = parseReasoningTokens(part)
                )
            )
            "text" -> listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.MESSAGE,
                    turnIndex = maxOf(currentTurn, 1),
                    content = part?.get("text")?.jsonPrimitive?.contentOrNull ?: line
                )
            )
            else -> listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.MESSAGE,
                    turnIndex = maxOf(currentTurn, 1),
                    content = line
                )
            )
        }
    }

    private fun parseToolUse(
        part: JsonObject?,
        rawLine: String,
        now: String,
        currentTurn: Int
    ): List<AgentTranscriptEvent> {
        if (part == null) {
            return listOf(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.MESSAGE,
                    turnIndex = maxOf(currentTurn, 1),
                    content = rawLine
                )
            )
        }
        val toolName = part["tool"]?.jsonPrimitive?.contentOrNull
        val state = part["state"] as? JsonObject
        val input = state?.get("input") as? JsonObject
        val output = state?.get("output")?.let { primitiveOrText(it) }
        val events = mutableListOf<AgentTranscriptEvent>()
        events.add(
            AgentTranscriptEvent(
                timestamp = now,
                eventType = AgentEventType.TOOL_CALL,
                turnIndex = maxOf(currentTurn, 0),
                toolName = toolName,
                toolArguments = input?.entries?.associate { (k, v) -> k to v },
                content = toolName
            )
        )
        if (output != null) {
            events.add(
                AgentTranscriptEvent(
                    timestamp = now,
                    eventType = AgentEventType.TOOL_RESULT,
                    turnIndex = maxOf(currentTurn, 1),
                    toolName = toolName,
                    toolResult = output
                )
            )
        }
        return events
    }

    private fun parseTokenUsage(part: JsonObject?): TokenUsage? {
        val tokens = part?.get("tokens") as? JsonObject ?: return null
        val prompt = tokens["input"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val completion = tokens["output"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val total = tokens["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: (prompt + completion)
        return TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
    }

    private fun parseReasoningTokens(part: JsonObject?): Int? {
        val tokens = part?.get("tokens") as? JsonObject
        return tokens?.get("reasoning")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: part?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }

    private fun lineMetadata(line: String): OpenCodeLineMetadata {
        val element = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return OpenCodeLineMetadata(null, null, null)
        val part = element["part"] as? JsonObject
        fun string(obj: JsonObject?, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> obj?.get(key)?.jsonPrimitive?.contentOrNull }
        return OpenCodeLineMetadata(
            sessionId = string(element, "sessionID", "sessionId", "session_id")
                ?: string(part, "sessionID", "sessionId", "session_id"),
            messageId = string(part, "messageID", "messageId", "message_id"),
            partId = string(part, "id")
        )
    }

    private fun insertReasoningEvent(
        transcript: MutableList<AgentTranscriptEvent>,
        event: AgentTranscriptEvent
    ) {
        val firstNonReasoning = transcript.indexOfFirst {
            it.turnIndex == event.turnIndex &&
                it.eventType != AgentEventType.STEP_START &&
                it.eventType != AgentEventType.REASONING
        }
        val insertAt = if (firstNonReasoning >= 0) {
            firstNonReasoning
        } else {
            transcript.indexOfLast { it.turnIndex == event.turnIndex }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: transcript.size
        }
        transcript.add(insertAt, event)
    }

    private fun primitiveOrText(element: JsonElement): String =
        when (element) {
            is kotlinx.serialization.json.JsonPrimitive ->
                if (element.isString) element.contentOrNull ?: element.toString() else element.toString()
            else -> element.toString()
        }

    /** Best-effort removal of a throwaway pipeline DB and its SQLite sidecars. */
    private fun deleteIsolatedDb(path: String) {
        runCatching { File(path).delete() }
        runCatching { File("$path-wal").delete() }
        runCatching { File("$path-shm").delete() }
        runCatching { File("$path-journal").delete() }
    }

    /** Graceful SIGTERM-style destroy of the whole tree, then forcible kill. */
    private fun terminateTree(process: Process) {
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        runCatching { process.destroy() }
        descendants.forEach { runCatching { it.destroy() } }
        val exited = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(true)
        if (!exited) {
            runCatching { process.destroyForcibly() }
            val refreshed = runCatching { process.toHandle().descendants().toList() }.getOrDefault(descendants)
            (refreshed + descendants).toSet().forEach { runCatching { it.destroyForcibly() } }
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}
