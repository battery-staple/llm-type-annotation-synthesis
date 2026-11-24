package pipeline.stages

import mu.KotlinLogging
import java.io.File
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import pipeline.cas.PatchManager
import pipeline.db.AgentSessionTermination
import pipeline.db.DiscardCheck
import pipeline.db.DiscardedAttemptRecord
import pipeline.ports.AgentRunResult
import pipeline.util.Env
import pipeline.util.PipelineJson

private val logger = KotlinLogging.logger {}

/**
 * Persists the debugging artifacts of an agent session whose output is about to
 * be thrown away by a stage's retry loop.
 *
 * Callers MUST invoke this before `git reset` / workspace re-preparation: after
 * that point the transcript's source tree and patch no longer exist. Artifacts
 * are written under `<pipeline database dir>/discarded-attempts/...` (the
 * gitignored `data/` tree by default), never inside a benchmark worktree or an
 * agent workspace, so they survive both resets and process restarts. The whole
 * application/synthesis/repair retry history can be inspected later with
 * `DatabasePort.readDiscardedAttempts(runId, stage, stageAttemptNumber)`.
 */
internal object DiscardedAttemptRecorder {

    suspend fun record(
        ctx: RunContext,
        stage: StageName,
        internalAttempt: Int,
        attemptStartedMs: Long,
        effectivePrompt: String,
        transcript: List<AgentTranscriptEvent>,
        runResult: AgentRunResult,
        maxSteps: Int,
        check: DiscardCheck,
        feedback: String?,
        patch: String? = null,
        patchOmittedReason: String? = null,
        diagnostics: String? = null,
        completedMs: Long = System.currentTimeMillis(),
    ) {
        val dir = artifactDir(ctx, stage, internalAttempt)
        runCatching { dir.mkdirs() }

        // The prompt is written separately from the transcript so the initial
        // prompt and the injected feedback remain visible even with a partial
        // transcript (e.g. the agent was killed before its first STEP_START).
        val promptPath = writeIfPresent(dir, "prompt.txt", effectivePrompt)
        val transcriptPath = writeIfPresent(
            dir,
            "transcript.json",
            PipelineJson.json.encodeToString(ListSerializer(AgentTranscriptEvent.serializer()), transcript),
        )
        val patchPath = patch?.let { writeIfPresent(dir, "patch.diff", it) }
        val diagnosticsPath = diagnostics?.let { writeIfPresent(dir, "diagnostics.txt", it) }

        val record = DiscardedAttemptRecord(
            attemptId = TelemetryIds.id(),
            runId = ctx.runId,
            stepId = ctx.stepId,
            stage = stage.identifier,
            // ctx.attemptNumber is set by the orchestrator per whole-stage attempt;
            // internalAttempt is this stage's own re-prompt loop counter.
            stageAttemptNumber = ctx.attemptNumber,
            internalAttemptNumber = internalAttempt,
            startedAt = Instant.ofEpochMilli(attemptStartedMs).toString(),
            completedAt = Instant.ofEpochMilli(completedMs).toString(),
            termination = classifyTermination(runResult, maxSteps),
            discardCheck = check,
            feedback = feedback,
            agentFailureMessage = agentFailureMessage(runResult),
            exitCode = runResult.exitCode,
            stepsCounted = runResult.stepsCounted,
            timedOut = runResult.timedOut,
            promptPath = promptPath,
            transcriptPath = transcriptPath,
            patchPath = patchPath,
            patchOmittedReason = if (patchPath == null) {
                patchOmittedReason ?: "patch could not be captured from the worktree"
            } else null,
            diagnosticsPath = diagnosticsPath,
        )
        // Diagnostics are best-effort: a DB failure must not change stage control
        // flow, but it is logged so a silently missing record is detectable.
        runCatching { ctx.database.recordDiscardedAttempt(record) }
            .onFailure { logger.warn(it) { "Could not persist discarded ${stage.identifier} attempt $internalAttempt for ${ctx.runId}" } }
        logger.info {
            "discarded ${stage.identifier} attempt ${ctx.attemptNumber}/$internalAttempt " +
                "($check, ${record.termination}) artifacts=$dir"
        }
    }

    /** Artifact root, derived from the database location unless explicitly overridden. */
    internal fun artifactDir(ctx: RunContext, stage: StageName, internalAttempt: Int): File {
        val configured = Env.get("PIPELINE_DISCARDED_ATTEMPTS_DIR")?.takeIf { it.isNotBlank() }
        val base = configured?.let(::File)
            ?: File(ctx.config.paths.databaseFile().parentFile ?: File("."), "discarded-attempts")
        return base
            .resolve(ctx.runId.value)
            .resolve(stage.identifier)
            .resolve("stage-attempt-${ctx.attemptNumber}")
            .resolve("attempt-$internalAttempt")
    }

    /**
     * Best-effort snapshot of the rejected worktree. A patch-capture failure
     * must never change the stage's rejection/retry control flow.
     */
    internal fun patchOrNull(worktree: File): String? =
        runCatching { PatchManager(worktree).generatePatch() }.getOrNull()

    internal fun classifyTermination(result: AgentRunResult, maxSteps: Int): AgentSessionTermination = when {
        result.timedOut -> AgentSessionTermination.TIMEOUT
        maxSteps > 0 && result.stepsCounted >= maxSteps -> AgentSessionTermination.STEP_CAP
        agentStartupFailure(result) != null -> AgentSessionTermination.STARTUP_FAILURE
        result.exitCode != 0 -> AgentSessionTermination.AGENT_FAILURE
        else -> AgentSessionTermination.NORMAL_EXIT
    }

    private fun writeIfPresent(dir: File, name: String, content: String): String? = runCatching {
        val file = dir.resolve(name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.absolutePath
    }.getOrNull()
}
