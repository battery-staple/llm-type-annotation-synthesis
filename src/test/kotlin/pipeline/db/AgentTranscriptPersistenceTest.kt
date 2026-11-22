package pipeline.db

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import pipeline.stages.AgentEventType
import pipeline.stages.AgentTranscriptEvent
import pipeline.stages.GuardrailsMode
import pipeline.stages.RunId
import pipeline.stages.VerificationScope

class AgentTranscriptPersistenceTest {

    @Test
    fun `reasoning event remains typed and queryable in step telemetry`() = runBlocking {
        val dir = Files.createTempDirectory("agent-transcript-db-test").toFile()
        val runId = RunId("reasoning-run")
        try {
            SQLiteDatabaseManager(File(dir, "pipeline.db")).use { database ->
                database.recordRunStart(
                    EvaluationRunRecord(
                        runId = runId,
                        benchmarkId = "benchmark",
                        commitHash = "commit",
                        repoId = "repo",
                        repoName = "repo",
                        gitUrl = "local",
                        defaultBranch = "main",
                        configYaml = "{}",
                        parentCommit = "parent",
                        authorDate = "2026-01-01T00:00:00Z",
                        commitMsg = "test",
                        sampleIndex = 0,
                        sampleTemperature = 0.0,
                        configSnapshot = "{}",
                        guardrailsMode = GuardrailsMode.LENIENT,
                        verificationScope = VerificationScope.FULL_SUITE,
                        startedAt = Instant.now().toString()
                    )
                )
                database.recordStep(
                    StepTrajectoryRecord(
                        stepId = "reasoning-step",
                        runId = runId,
                        stepNumber = 1,
                        attemptNumber = 1,
                        stepName = "synthesis",
                        success = true,
                        agentTranscript = listOf(
                            AgentTranscriptEvent(
                                timestamp = "2026-01-01T00:00:01Z",
                                eventType = AgentEventType.REASONING,
                                turnIndex = 1,
                                content = "separate reasoning content",
                                reasoningTokens = 12
                            ),
                            AgentTranscriptEvent(
                                timestamp = "2026-01-01T00:00:02Z",
                                eventType = AgentEventType.MESSAGE,
                                turnIndex = 1,
                                content = "assistant answer"
                            )
                        )
                    )
                )

                val stored = database.jdbc.prepareStatement(
                    """SELECT json_extract(event.value, '$.event_type'),
                              json_extract(event.value, '$.content'),
                              json_extract(event.value, '$.reasoning_tokens')
                       FROM step_trajectories, json_each(agent_transcript) AS event
                       WHERE step_trajectories.step_id=?
                         AND json_extract(event.value, '$.event_type')='reasoning'"""
                ).use { statement ->
                    statement.setString(1, "reasoning-step")
                    statement.executeQuery().use { rows ->
                        assertEquals(true, rows.next())
                        Triple(rows.getString(1), rows.getString(2), rows.getInt(3))
                    }
                }

                assertNotNull(stored)
                assertEquals(Triple("reasoning", "separate reasoning content", 12), stored)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
