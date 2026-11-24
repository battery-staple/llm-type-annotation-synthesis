package pipeline.db

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.stages.RunId

/**
 * Dispatched agent sessions whose work is reset between attempts must remain
 * diagnosable after the fact: the typed row, its exact feedback and every
 * artifact path have to survive a process restart (database reopen).
 */
class DiscardedAttemptPersistenceTest {

    @Test
    fun `discarded attempts are queryable by run stage and attempts and survive reopen`() = runBlocking {
        val dir = Files.createTempDirectory("discarded-attempts-db").toFile()
        val runId = RunId("run-aab1c476")
        val otherRun = RunId("run-other")
        try {
            val dbFile = File(dir, "pipeline.db")
            SQLiteDatabaseManager(dbFile).use { database ->
                database.recordDiscardedAttempt(
                    sample(dir, runId, "application", 1, 1, DiscardCheck.NO_CHANGES, AgentSessionTermination.TIMEOUT,
                        "empty patch after 900s timeout", withPatch = false)
                )
                database.recordDiscardedAttempt(
                    sample(dir, runId, "application", 1, 2, DiscardCheck.COMPILE, AgentSessionTermination.NORMAL_EXIT,
                        "Compilation of the annotated tree failed:\nCommentTest cannot find symbol")
                )
                database.recordDiscardedAttempt(
                    sample(dir, runId, "application", 2, 1, DiscardCheck.TESTS, AgentSessionTermination.NORMAL_EXIT,
                        "existing tests fail on annotated tree: CommentTest")
                )
                database.recordDiscardedAttempt(
                    sample(dir, runId, "synthesis", 1, 1, DiscardCheck.OTHER, AgentSessionTermination.STEP_CAP,
                        "Corrective feedback: decide a checker")
                )
                database.recordDiscardedAttempt(
                    sample(dir, otherRun, "application", 1, 1, DiscardCheck.GUARDRAILS, AgentSessionTermination.NORMAL_EXIT,
                        "Guardrails rejected the edits")
                )
            }

            // Reopen from disk: nothing here may live only in the writer's memory.
            SQLiteDatabaseManager(dbFile).use { database ->
                val all = database.readDiscardedAttempts(runId)
                assertEquals(4, all.size)
                assertEquals(listOf(1, 1, 1, 2), all.map { it.stageAttemptNumber })
                assertEquals(listOf(1, 1, 2, 1), all.map { it.internalAttemptNumber })
                assertEquals("run-aab1c476", all.first().runId.value)

                val applications = database.readDiscardedAttempts(runId, stage = "application")
                assertEquals(3, applications.size)
                assertEquals(
                    listOf(DiscardCheck.NO_CHANGES, DiscardCheck.COMPILE, DiscardCheck.TESTS),
                    applications.map { it.discardCheck },
                )
                assertEquals(
                    listOf(AgentSessionTermination.TIMEOUT, AgentSessionTermination.NORMAL_EXIT,
                        AgentSessionTermination.NORMAL_EXIT),
                    applications.map { it.termination },
                )

                // A later internal attempt in a later whole-stage retry is a distinct row.
                val secondStageAttempt = database.readDiscardedAttempts(
                    runId, stage = "application", stageAttemptNumber = 2
                )
                assertEquals(1, secondStageAttempt.size)
                assertEquals(1, secondStageAttempt.single().internalAttemptNumber)
                assertEquals("existing tests fail on annotated tree: CommentTest", secondStageAttempt.single().feedback)

                val firstInternal = database.readDiscardedAttempts(
                    runId, stage = "application", internalAttemptNumber = 1
                )
                assertEquals(2, firstInternal.size)
                assertEquals(listOf(1, 2), firstInternal.map { it.stageAttemptNumber })

                val discarded = all.first()
                assertEquals("agent timed out: stderr: killed", discarded.agentFailureMessage)
                assertEquals(-1, discarded.exitCode)
                assertEquals(0, discarded.stepsCounted)
                assertTrue(discarded.timedOut)
                assertEquals("no file changes were detected in the worktree", discarded.patchOmittedReason)
                assertEquals(null, discarded.patchPath)

                for (record in all) {
                    assertNotNull(record.promptPath) { "missing prompt artifact for $record" }
                    assertNotNull(record.transcriptPath) { "missing transcript artifact for $record" }
                    assertTrue(File(record.promptPath).isFile, "prompt artifact missing: ${record.promptPath}")
                    assertTrue(File(record.transcriptPath).isFile, "transcript artifact missing: ${record.transcriptPath}")
                    assertTrue(File(record.transcriptPath).readText().contains("initial prompt"))
                    if (record.patchPath != null) {
                        assertTrue(File(record.patchPath).readText().startsWith("diff --git"))
                    }
                    if (record.diagnosticsPath != null) {
                        assertTrue(File(record.diagnosticsPath).isFile)
                    }
                    assertTrue(record.startedAt < record.completedAt || record.startedAt == record.completedAt)
                }

                assertEquals(1, database.readDiscardedAttempts(otherRun).size)
                assertTrue(database.readDiscardedAttempts(RunId("no-such-run")).isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun sample(
        dir: File,
        runId: RunId,
        stage: String,
        stageAttempt: Int,
        internal: Int,
        check: DiscardCheck,
        termination: AgentSessionTermination,
        feedback: String,
        withPatch: Boolean = true,
    ): DiscardedAttemptRecord {
        val artifacts = File(dir, "discarded-attempts/${runId.value}/$stage/$stageAttempt/attempt-$internal")
        artifacts.mkdirs()
        val prompt = artifacts.resolve("prompt.txt").apply { writeText("initial prompt\n$feedback") }
        val transcript = artifacts.resolve("transcript.json").apply { writeText("[{\"initial prompt\": true}]") }
        val patch = if (withPatch) {
            artifacts.resolve("patch.diff").apply { writeText("diff --git a/A.java b/A.java\n+annotation\n") }
        } else null
        val diagnostics = artifacts.resolve("diagnostics.txt").apply { writeText("diagnostic for $stage $internal") }
        return DiscardedAttemptRecord(
            attemptId = "$runId-$stage-$stageAttempt-$internal",
            runId = runId,
            stepId = "step-$stageAttempt",
            stage = stage,
            stageAttemptNumber = stageAttempt,
            internalAttemptNumber = internal,
            startedAt = "2026-01-01T23:02:00Z",
            completedAt = "2026-01-01T23:02:49Z",
            termination = termination,
            discardCheck = check,
            feedback = feedback,
            agentFailureMessage = "agent timed out: stderr: killed".takeIf { termination == AgentSessionTermination.TIMEOUT },
            exitCode = if (termination == AgentSessionTermination.TIMEOUT) -1 else 0,
            stepsCounted = when (termination) {
                AgentSessionTermination.TIMEOUT -> 0
                AgentSessionTermination.STEP_CAP -> 20
                else -> 5
            },
            timedOut = termination == AgentSessionTermination.TIMEOUT,
            promptPath = prompt.absolutePath,
            transcriptPath = transcript.absolutePath,
            patchPath = patch?.absolutePath,
            patchOmittedReason = "no file changes were detected in the worktree".takeIf { !withPatch },
            diagnosticsPath = diagnostics.absolutePath,
        )
    }
}
