package pipeline.stages

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.CheckerDiagnosticRecord
import pipeline.db.CheckerRunRecord
import pipeline.db.CommitTriageUpdate
import pipeline.db.DiscardedAttemptRecord
import pipeline.db.EvaluationRunRecord
import pipeline.db.GuardrailEventRecord
import pipeline.db.RetryEvent
import pipeline.db.StepTrajectoryRecord
import pipeline.db.SubprocessMetricRecord
import pipeline.db.TelemetryProjection
import pipeline.db.TestExecutionRecord
import pipeline.db.TestRunRecord
import pipeline.ports.DatabasePort
import pipeline.ports.TestStatus

class ApplicationStageFailureTelemetryTest {

    private class RecordingDatabase : DatabasePort {
        val projections = mutableListOf<TelemetryProjection>()

        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) {
            projections += projection
        }
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: CommitTriageUpdate) = Unit
        override suspend fun recordRetry(event: RetryEvent) = Unit
        override suspend fun recordSubprocess(metric: SubprocessMetricRecord) = Unit
        override suspend fun recordDiscardedAttempt(record: DiscardedAttemptRecord) = Unit
        override suspend fun readDiscardedAttempts(
            runId: RunId,
            stage: String?,
            stageAttemptNumber: Int?,
            internalAttemptNumber: Int?
        ): List<DiscardedAttemptRecord> = emptyList()
    }

    private fun context(database: DatabasePort) = RunContext(
        config = ProjectConfig(),
        build = BuildSection(),
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("application-failure-telemetry"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = File("."),
        casDir = File("./cas"),
        worktreeDir = File("."),
        agentWorkspaceDir = File("./agent"),
        database = database,
        stepId = "application-step",
    )

    @Test
    fun `failed application records agent turns and LLM invocations from the full transcript`() = runBlocking {
        val database = RecordingDatabase()
        val ctx = context(database)
        val transcript = listOf(
            AgentTranscriptEvent(
                timestamp = "2026-01-01T00:00:00Z",
                eventType = AgentEventType.STEP_START,
                turnIndex = 1,
            ),
            AgentTranscriptEvent(
                timestamp = "2026-01-01T00:00:01Z",
                eventType = AgentEventType.TOOL_CALL,
                turnIndex = 1,
                toolName = "edit",
                toolArguments = mapOf("path" to JsonPrimitive("src/Main.java")),
                toolResult = "edited source",
            ),
            AgentTranscriptEvent(
                timestamp = "2026-01-01T00:00:02Z",
                eventType = AgentEventType.REASONING,
                turnIndex = 1,
                content = "private reasoning",
            ),
            AgentTranscriptEvent(
                timestamp = "2026-01-01T00:00:03Z",
                eventType = AgentEventType.MESSAGE,
                turnIndex = 2,
                content = "applied annotations",
            ),
            AgentTranscriptEvent(
                timestamp = "2026-01-01T00:00:04Z",
                eventType = AgentEventType.STEP_FINISH,
                turnIndex = 2,
                tokenUsage = TokenUsage(promptTokens = 120, completionTokens = 30, totalTokens = 150),
            ),
        )
        val guardrailEvent = GuardrailEventRecord(
            "event-1", ctx.runId, ctx.stepId, StageName.APPLICATION.identifier, 1,
            "ast_gate", GuardrailViolationCategory.EXECUTABLE_EDIT, true,
            "src/Main.java", "diff", "blocked edit",
        )
        val checkerRun = CheckerRunRecord(
            "checker-1", ctx.runId, ctx.stepId, "application_initial", 1,
            "org.checkerframework.NullnessChecker", CheckerCategory.BUILTIN_CHECKER,
            "", 1, true, true, 2, 0, "/tmp/checker.log",
        )
        val diagnostic = CheckerDiagnosticRecord(
            "diagnostic-1", ctx.runId, ctx.stepId, "checker-1", "application_initial", null,
            "error", "NullnessChecker", "type", "src/Main.java", 3, null, "bad type", null,
        )
        val testRun = TestRunRecord(
            "test-1", ctx.runId, ctx.stepId, "application", "pre_existing_suite", 1,
            VerificationScope.FULL_SUITE, 1, false, 4, 3, 1, 0, true, null,
        )
        val testExecution = TestExecutionRecord(
            "execution-1", ctx.runId, ctx.stepId, "test-1", "application", null,
            "suite", "TestClass", "testMethod", false, TestStatus.FAILED, 12L, "failed",
        )

        recordApplicationFailureTelemetry(
            database, ctx, transcript, 9876L,
            guardrailEvents = listOf(guardrailEvent),
            checkerRuns = listOf(checkerRun),
            checkerDiagnostics = listOf(diagnostic),
            testRun = testRun,
            testExecutions = listOf(testExecution),
        )

        assertEquals(1, database.projections.size)
        val projection = database.projections.single()

        // Agent turns: only TOOL_CALL/MESSAGE events, tagged with the application stage.
        assertEquals(2, projection.agentTurns.size)
        assertEquals(listOf("tool_call", "message"), projection.agentTurns.map { it.actionType })
        assertEquals(listOf(1, 2), projection.agentTurns.map { it.turnIndex })
        assertTrue(projection.agentTurns.all { it.stage == StageName.APPLICATION.identifier })
        assertTrue(projection.agentTurns.all { it.runId == ctx.runId && it.stepId == ctx.stepId })

        // LLM invocations: STEP_FINISH usage from the whole transcript, with the failure duration.
        assertEquals(1, projection.llmInvocations.size)
        val invocation = projection.llmInvocations.single()
        assertEquals(StageName.APPLICATION.identifier, invocation.stage)
        assertEquals(ctx.config.model.applicationModel, invocation.modelName)
        assertEquals(2, invocation.turnIndex)
        assertEquals(120, invocation.promptTokens)
        assertEquals(30, invocation.completionTokens)
        assertEquals(150, invocation.totalTokens)
        assertEquals(9876L, invocation.latencyMs)

        // Checker/test telemetry already gathered by the stage is carried along.
        assertEquals(listOf(guardrailEvent), projection.guardrailEvents)
        assertEquals(listOf(checkerRun), projection.checkerRuns)
        assertEquals(listOf(diagnostic), projection.checkerDiagnostics)
        assertEquals(listOf(testRun), projection.testRuns)
        assertEquals(listOf(testExecution), projection.testExecutions)
    }
}
