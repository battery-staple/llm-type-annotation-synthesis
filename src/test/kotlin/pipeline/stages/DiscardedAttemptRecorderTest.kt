package pipeline.stages

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import pipeline.config.BuildSection
import pipeline.config.PathsSection
import pipeline.config.ProjectConfig
import pipeline.db.AgentSessionTermination
import pipeline.db.DiscardCheck
import pipeline.db.DiscardedAttemptRecord
import pipeline.db.EvaluationRunRecord
import pipeline.db.RetryEvent
import pipeline.db.SubprocessMetricRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.AgentRunResult
import pipeline.ports.DatabasePort
import pipeline.util.PipelineJson

class DiscardedAttemptRecorderTest {

    private class RecordingDatabase : DatabasePort {
        val discarded = mutableListOf<DiscardedAttemptRecord>()
        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: pipeline.db.StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) = Unit
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: pipeline.db.CommitTriageUpdate) = Unit
        override suspend fun recordRetry(event: RetryEvent) = Unit
        override suspend fun recordSubprocess(metric: SubprocessMetricRecord) = Unit
        override suspend fun recordDiscardedAttempt(record: DiscardedAttemptRecord) { discarded += record }
        override suspend fun readDiscardedAttempts(
            runId: RunId, stage: String?, stageAttemptNumber: Int?, internalAttemptNumber: Int?
        ): List<DiscardedAttemptRecord> = discarded
    }

    private fun context(database: DatabasePort, dbFile: File, stageAttempt: Int) = RunContext(
        config = ProjectConfig().copy(paths = PathsSection(database = dbFile.absolutePath)),
        build = BuildSection(),
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("recorder-run"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = File(dbFile.parentFile, "no-repo"),
        casDir = File(dbFile.parentFile, "cas"),
        worktreeDir = File(dbFile.parentFile, "worktree"),
        agentWorkspaceDir = File(dbFile.parentFile, "agent"),
        database = database,
        stepId = "step-$stageAttempt",
        attemptNumber = stageAttempt,
        stageName = StageName.APPLICATION,
    )

    @Test
    fun `records prompt transcript feedback and timeout outcome before reset`() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("discarded-recorder").toFile()
        try {
            val database = RecordingDatabase()
            val ctx = context(database, File(root, "data/pipeline.db"), stageAttempt = 2)
            val prompt = "base application prompt\n\n## Feedback from previous attempt\nempty patch after timeout"
            val transcript = listOf(
                AgentTranscriptEvent(
                    timestamp = "2026-01-01T23:08:19Z", eventType = AgentEventType.INITIAL_PROMPT,
                    turnIndex = 0, content = prompt,
                ),
                AgentTranscriptEvent(
                    timestamp = "2026-01-01T23:08:49Z", eventType = AgentEventType.MESSAGE,
                    turnIndex = 1, content = "stderr: killed",
                ),
            )
            val result = AgentRunResult(exitCode = -1, stepsCounted = 0, transcript = transcript, timedOut = true)

            DiscardedAttemptRecorder.record(
                ctx, StageName.APPLICATION, internalAttempt = 2,
                attemptStartedMs = 1_700_000_000_000L,
                effectivePrompt = prompt, transcript = transcript, runResult = result,
                maxSteps = 20, check = DiscardCheck.NO_CHANGES,
                feedback = "empty patch after timeout",
                patchOmittedReason = "no file changes were detected in the worktree",
                completedMs = 1_700_000_049_000L,
            )

            assertEquals(1, database.discarded.size)
            val record = database.discarded.single()
            assertEquals("application", record.stage)
            assertEquals(2, record.stageAttemptNumber)
            assertEquals(2, record.internalAttemptNumber)
            assertEquals(AgentSessionTermination.TIMEOUT, record.termination)
            assertEquals(DiscardCheck.NO_CHANGES, record.discardCheck)
            assertEquals("empty patch after timeout", record.feedback)
            assertTrue(record.agentFailureMessage!!.contains("agent timed out"))
            assertEquals("no file changes were detected in the worktree", record.patchOmittedReason)
            assertNull(record.patchPath)
            assertTrue(record.timedOut)

            val expectedDir = DiscardedAttemptRecorder.artifactDir(
                ctx, StageName.APPLICATION, 2, record.attemptId,
            )
            assertEquals(expectedDir.absolutePath, File(record.transcriptPath!!).parentFile.absolutePath)
            assertTrue(File(record.promptPath!!).readText().contains("Feedback from previous attempt"))
            val storedTranscript = PipelineJson.json.decodeFromString(
                ListSerializer(AgentTranscriptEvent.serializer()), File(record.transcriptPath).readText()
            )
            assertEquals(transcript, storedTranscript)
            assertEquals(prompt, storedTranscript.first().content)

            // A different internal attempt and a different whole-stage attempt
            // must never share an artifact directory or a record.
            DiscardedAttemptRecorder.record(
                ctx, StageName.APPLICATION, internalAttempt = 3,
                attemptStartedMs = 1_700_000_050_000L, effectivePrompt = prompt, transcript = transcript,
                runResult = result, maxSteps = 20, check = DiscardCheck.COMPILE, feedback = "compile failed",
            )
            DiscardedAttemptRecorder.record(
                context(database, File(root, "data/pipeline.db"), stageAttempt = 3), StageName.APPLICATION,
                internalAttempt = 1, attemptStartedMs = 1_700_000_100_000L, effectivePrompt = prompt,
                transcript = transcript, runResult = result, maxSteps = 20,
                check = DiscardCheck.TESTS, feedback = "CommentTest failed",
            )
            assertEquals(3, database.discarded.size)
            assertEquals(
                3,
                database.discarded.map { File(it.transcriptPath!!).parentFile.absolutePath }.distinct().size,
            )
            assertEquals(
                setOf(2 to 2, 2 to 3, 3 to 1),
                database.discarded.map { it.stageAttemptNumber to it.internalAttemptNumber }.toSet(),
            )

            // A second diagnostic for the SAME internal attempt (e.g. an
            // in-loop rejection followed by a final-checker failure) must not
            // overwrite the first one's evidence.
            DiscardedAttemptRecorder.record(
                ctx, StageName.APPLICATION, internalAttempt = 2,
                attemptStartedMs = 1_700_000_150_000L, effectivePrompt = prompt, transcript = transcript,
                runResult = result, maxSteps = 20, check = DiscardCheck.CHECKER,
                feedback = "final checker failed",
            )
            assertEquals(4, database.discarded.size)
            assertEquals(
                4,
                database.discarded.map { File(it.transcriptPath!!).parentFile.absolutePath }.distinct().size,
            )
            assertTrue(File(record.promptPath!!).readText().contains("Feedback from previous attempt"))
            assertEquals("final checker failed", database.discarded.last().feedback)
            assertTrue(record.promptPath != database.discarded.last().promptPath)
            assertTrue(File(database.discarded.last().promptPath!!).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `termination distinguishes step cap startup failure and normal exit`() = runBlocking {
        val file = File(kotlin.io.path.createTempDirectory("discarded-termination").toFile(), "pipeline.db")
        val ctx = context(RecordingDatabase(), file, stageAttempt = 1)
        fun result(exitCode: Int, steps: Int, timedOut: Boolean) =
            AgentRunResult(exitCode, steps, emptyList(), timedOut)

        assertEquals(
            AgentSessionTermination.TIMEOUT,
            DiscardedAttemptRecorder.classifyTermination(result(-1, 0, true), 20),
        )
        assertEquals(
            AgentSessionTermination.STEP_CAP,
            DiscardedAttemptRecorder.classifyTermination(result(-1, 20, false), 20),
        )
        assertEquals(
            AgentSessionTermination.STARTUP_FAILURE,
            DiscardedAttemptRecorder.classifyTermination(result(2, 0, false), 20),
        )
        assertEquals(
            AgentSessionTermination.AGENT_FAILURE,
            DiscardedAttemptRecorder.classifyTermination(result(2, 3, false), 20),
        )
        assertEquals(
            AgentSessionTermination.NORMAL_EXIT,
            DiscardedAttemptRecorder.classifyTermination(result(0, 3, false), 20),
        )
        file.parentFile.deleteRecursively()
    }
}
