package pipeline.util

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import mu.KotlinLogging
import pipeline.stages.AgentTranscriptEvent

private val logger = KotlinLogging.logger {}

/**
 * Live, provisional transcript for one step attempt, for the run viewer.
 *
 * Events are appended as newline-delimited JSON, one complete line per event,
 * written with a single append so a concurrent reader never sees a torn line.
 * The orchestrator installs a stream in the coroutine context around a stage;
 * the agent runner finds it via [current] and appends each event as it is
 * parsed, so stages need no changes.
 *
 * The stream is best-effort telemetry: IO failures are logged once and never
 * propagate into the agent run. It lacks reasoning events, which the runner
 * reconciles only at the end; the durable transcript (CAS bundle or the
 * `agent_transcript` column) is authoritative and supersedes this file.
 */
class TranscriptStream(val file: File) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranscriptStream> {
        /** The stream installed for the running stage, if any. */
        suspend fun current(): TranscriptStream? = coroutineContext[Key]
    }

    private var failed = false

    @Synchronized
    fun append(event: AgentTranscriptEvent) {
        if (failed) return
        try {
            file.parentFile?.mkdirs()
            val line = PipelineJson.compact.encodeToString(AgentTranscriptEvent.serializer(), event) + "\n"
            Files.write(
                file.toPath(),
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            failed = true
            logger.warn(e) { "Live transcript stream disabled for ${file.path}" }
        }
    }

    /** Remove the file once the durable transcript has been committed. */
    @Synchronized
    fun delete() {
        failed = true
        runCatching { Files.deleteIfExists(file.toPath()) }
            .onFailure { logger.warn(it) { "Could not delete live transcript ${file.path}" } }
    }
}

object TranscriptStreams {
    const val EXTENSION = ".jsonl"

    /** Mirrors logback's PIPELINE_LOG_DIR resolution (system property, then env, then `logs`). */
    fun defaultRoot(): File {
        val base = System.getProperty("PIPELINE_LOG_DIR") ?: System.getenv("PIPELINE_LOG_DIR") ?: "logs"
        return File(base, "transcripts")
    }

    /** One file per step attempt: `<root>/<run_id>/<step_id>.jsonl`. */
    fun forStep(root: File, runId: String, stepId: String): TranscriptStream =
        TranscriptStream(File(File(root, runId), stepId + EXTENSION))

    /**
     * Delete stream files older than [maxAge], plus emptied run directories.
     * Catches files orphaned by a crash between the durable write and the
     * delete. Streams of failed/discarded steps are kept until they age out.
     */
    fun sweep(root: File, maxAge: Duration, now: Instant = Instant.now()) {
        if (!root.isDirectory) return
        val cutoff = now.minus(maxAge).toEpochMilli()
        runCatching {
            root.listFiles { f -> f.isDirectory }?.forEach { runDir ->
                runDir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }?.forEach { f ->
                    if (f.lastModified() < cutoff) f.delete()
                }
                if (runDir.list().isNullOrEmpty()) runDir.delete()
            }
        }.onFailure { logger.warn(it) { "Transcript stream sweep failed under ${root.path}" } }
    }
}
