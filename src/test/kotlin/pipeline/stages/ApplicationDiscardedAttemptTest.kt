package pipeline.stages

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.config.BuildSection
import pipeline.config.PathsSection
import pipeline.config.ProjectConfig
import pipeline.db.AgentSessionTermination
import pipeline.db.DiscardCheck
import pipeline.db.EvaluationRunRecord
import pipeline.db.SQLiteDatabaseManager
import pipeline.db.StepTrajectoryRecord
import pipeline.ports.AgentRunResult
import pipeline.ports.AgentRunner
import pipeline.ports.BuildResult
import pipeline.ports.BuildRunner
import pipeline.ports.CheckerResult
import pipeline.ports.TestRunResult
import pipeline.util.Git

/**
 * Drives the application stage's internal re-prompt loop with agent sessions
 * that are killed by the timeout. Each attempt leaves a durable record with
 * its feedback, transcript and outcome even though the stage's worktree is
 * reset for the next attempt, and a whole-stage retry is distinguishable.
 */
class ApplicationDiscardedAttemptTest {

    private class TimedOutAgent : AgentRunner {
        val prompts = mutableListOf<String>()

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
            prompts += prompt
            val now = Instant.now().toString()
            return AgentRunResult(
                exitCode = -1,
                stepsCounted = 0,
                transcript = listOf(
                    AgentTranscriptEvent(now, AgentEventType.INITIAL_PROMPT, 0, content = prompt),
                    AgentTranscriptEvent(now, AgentEventType.MESSAGE, 1, content = "stderr: agent killed at 900s"),
                ),
                timedOut = true,
            )
        }
    }

    private class EditingAgent : AgentRunner {
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
            val source = File(cwd, "src/Main.java")
            source.parentFile.mkdirs()
            source.writeText("class Main { @example.NonNull String value; }\n")
            val now = Instant.now().toString()
            return AgentRunResult(
                exitCode = 0,
                stepsCounted = 3,
                transcript = listOf(
                    AgentTranscriptEvent(now, AgentEventType.INITIAL_PROMPT, 0, content = prompt),
                    AgentTranscriptEvent(now, AgentEventType.STEP_FINISH, 3),
                ),
                timedOut = false,
            )
        }
    }

    private object FailingCompileBuildRunner : BuildRunner {
        override suspend fun compile(
            worktreeDir: File, checker: CheckerConfig?, extraClasspath: List<File>
        ): BuildResult = BuildResult(false, 1, "compile error: cannot find symbol NonNull", false)

        override suspend fun runTests(
            worktreeDir: File, testFilter: TestCoordinate?, checker: CheckerConfig?, extraClasspath: List<File>
        ): TestRunResult = error("runTests must not run after a failed compile")

        override suspend fun runChecker(
            worktreeDir: File, checkerHome: File, customQualsJar: File, processor: String,
            quals: List<Fqcn>, checkerCategory: CheckerCategory, guardrailsMode: GuardrailsMode
        ): CheckerResult = error("runChecker must not run after a failed compile")
    }

    private object UnusedBuildRunner : BuildRunner {
        override suspend fun compile(
            worktreeDir: File, checker: CheckerConfig?, extraClasspath: List<File>
        ): BuildResult = error("compile must not run for a timed-out session with no edits")

        override suspend fun runTests(
            worktreeDir: File, testFilter: TestCoordinate?, checker: CheckerConfig?, extraClasspath: List<File>
        ): TestRunResult = error("runTests must not run for a timed-out session with no edits")

        override suspend fun runChecker(
            worktreeDir: File, checkerHome: File, customQualsJar: File, processor: String,
            quals: List<Fqcn>, checkerCategory: CheckerCategory, guardrailsMode: GuardrailsMode
        ): CheckerResult = error("runChecker must not run for a timed-out session with no edits")
    }

    @Test
    fun `every timed out application attempt is persisted before the next re-prompt`() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("application-discarded").toFile()
        val worktree = File(root, "worktree")
        val runId = RunId("run-timeout-at-2302")
        try {
            worktree.mkdirs()
            Git.run(worktree, "init")
            Git.run(worktree, "config", "user.email", "test@example.com")
            Git.run(worktree, "config", "user.name", "Test")
            File(worktree, "src/Main.java").apply {
                parentFile.mkdirs()
                writeText("class Main {}\n")
            }
            Git.run(worktree, "add", "-A")
            Git.run(worktree, "commit", "--no-gpg-sign", "-q", "-m", "baseline")

            val dbFile = File(root, "data/pipeline.db")
            val database = SQLiteDatabaseManager(dbFile)
            try {
                database.recordRunStart(
                    EvaluationRunRecord(
                        runId = runId,
                        benchmarkId = "benchmark",
                        commitHash = "c".repeat(40),
                        repoId = "repo",
                        repoName = "repo",
                        gitUrl = "local",
                        defaultBranch = "main",
                        configYaml = "{}",
                        parentCommit = "p".repeat(40),
                        authorDate = "2026-01-01T00:00:00Z",
                        commitMsg = "commit",
                        sampleIndex = 0,
                        sampleTemperature = 0.0,
                        configSnapshot = "{}",
                        guardrailsMode = GuardrailsMode.LENIENT,
                        verificationScope = VerificationScope.FULL_SUITE,
                        startedAt = Instant.now().toString(),
                    )
                )
                val ctx = RunContext(
                    config = ProjectConfig().copy(paths = PathsSection(database = dbFile.absolutePath)),
                    build = BuildSection(),
                    sourceCompatibility = "17",
                    targetCompatibility = "17",
                    runId = runId,
                    sampleIndex = 0,
                    sampleTemperature = 0.0,
                    // A missing repository dir disables contamination checks in this test.
                    repoDir = File(root, "no-repo"),
                    casDir = File(root, "cas"),
                    worktreeDir = worktree,
                    agentWorkspaceDir = File(root, "agent"),
                    database = database,
                    stepId = "application-step-2",
                    stepNumber = 4,
                    // The orchestrator is on its second whole-stage attempt.
                    attemptNumber = 2,
                    stageName = StageName.APPLICATION,
                )
                database.recordStep(
                    StepTrajectoryRecord(
                        stepId = ctx.stepId, runId = runId, stepNumber = 4, attemptNumber = 2,
                        stepName = "application", success = false, startedAt = Instant.now().toString(),
                    )
                )

                val lattice = SynthesizedLattice(
                    customQualsJar = File(root, "quals.jar").apply { writeText("placeholder") },
                    placementGuideFile = File(root, "guide.md").apply { writeText("# default\n- annotate fields\n") },
                    customQualsSrcDir = File(root, "quals-src").apply { mkdirs() },
                    qualifierClassNames = listOf(Fqcn("example.NonNull")),
                    qualifiersCount = 1,
                    processor = "example.Checker",
                    checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
                )
                val agent = TimedOutAgent()
                val outcome = ApplicationStage(agent, UnusedBuildRunner).execute(
                    ctx, ApplicationInput(ApplicationTarget(CommitHash("a".repeat(40)), worktree), lattice)
                )

                assertTrue(outcome is StageResult.TransientFailure)
                assertEquals(ApplicationStage.MAX_ATTEMPTS, agent.prompts.size)

                val records = database.readDiscardedAttempts(runId)
                assertEquals(ApplicationStage.MAX_ATTEMPTS, records.size)
                assertEquals(listOf(1, 2, 3), records.map { it.internalAttemptNumber })
                assertTrue(records.all { it.stage == "application" })
                assertTrue(records.all { it.stepId == ctx.stepId })
                // Whole-stage attempt 2, internal attempts 1..3: distinguishable
                // from any attempt recorded during orchestrator attempt 1.
                assertTrue(records.all { it.stageAttemptNumber == 2 })
                assertTrue(records.all { it.discardCheck == DiscardCheck.NO_CHANGES })
                assertTrue(records.all { it.termination == AgentSessionTermination.TIMEOUT })
                assertTrue(records.all { it.feedback!!.contains("No file changes were detected") })
                assertTrue(records.all { it.agentFailureMessage!!.contains("agent killed at 900s") })

                records.forEach { record ->
                    assertTrue(File(record.promptPath!!).isFile)
                    assertTrue(File(record.transcriptPath!!).isFile)
                    assertEquals("no file changes were detected in the worktree", record.patchOmittedReason)
                    assertEquals(null, record.patchPath)
                }
                // The feedback recorded for attempt 1 was injected into attempt 2.
                val secondPrompt = File(records[1].promptPath!!).readText()
                assertTrue(secondPrompt.contains("Feedback from previous attempt"))
                assertTrue(secondPrompt.contains("No file changes were detected in the worktree"))
                val firstTranscript = File(records[0].transcriptPath!!).readText()
                assertTrue(firstTranscript.contains("agent killed at 900s"))

                // Artifacts never leaked into the audited worktree.
                assertEquals("", Git.statusPorcelain(worktree).trim())
            } finally {
                database.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejected patch and diagnostics are snapshotted before the worktree reset`() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("application-rejected-patch").toFile()
        val worktree = File(root, "worktree")
        val runId = RunId("run-compile-rejection")
        try {
            worktree.mkdirs()
            Git.run(worktree, "init")
            Git.run(worktree, "config", "user.email", "test@example.com")
            Git.run(worktree, "config", "user.name", "Test")
            File(worktree, "src/Main.java").apply {
                parentFile.mkdirs()
                writeText("class Main { String value; }\n")
            }
            Git.run(worktree, "add", "-A")
            Git.run(worktree, "commit", "--no-gpg-sign", "-q", "-m", "baseline")

            val dbFile = File(root, "data/pipeline.db")
            val database = SQLiteDatabaseManager(dbFile)
            try {
                database.recordRunStart(
                    EvaluationRunRecord(
                        runId = runId, benchmarkId = "benchmark", commitHash = "c".repeat(40),
                        repoId = "repo", repoName = "repo", gitUrl = "local", defaultBranch = "main",
                        configYaml = "{}", parentCommit = "p".repeat(40),
                        authorDate = "2026-01-01T00:00:00Z", commitMsg = "commit", sampleIndex = 0,
                        sampleTemperature = 0.0, configSnapshot = "{}",
                        guardrailsMode = GuardrailsMode.LENIENT,
                        verificationScope = VerificationScope.FULL_SUITE,
                        startedAt = Instant.now().toString(),
                    )
                )
                val ctx = RunContext(
                    config = ProjectConfig().copy(paths = PathsSection(database = dbFile.absolutePath)),
                    build = BuildSection(), sourceCompatibility = "17", targetCompatibility = "17",
                    runId = runId, sampleIndex = 0, sampleTemperature = 0.0,
                    repoDir = File(root, "no-repo"), casDir = File(root, "cas"),
                    worktreeDir = worktree, agentWorkspaceDir = File(root, "agent"),
                    database = database, stepId = "application-step-1", stepNumber = 4,
                    attemptNumber = 1, stageName = StageName.APPLICATION,
                )
                database.recordStep(
                    StepTrajectoryRecord(
                        stepId = ctx.stepId, runId = runId, stepNumber = 4, attemptNumber = 1,
                        stepName = "application", success = false, startedAt = Instant.now().toString(),
                    )
                )
                val lattice = SynthesizedLattice(
                    customQualsJar = File(root, "quals.jar").apply { writeText("placeholder") },
                    placementGuideFile = File(root, "guide.md").apply { writeText("# default\n- annotate fields\n") },
                    customQualsSrcDir = File(root, "quals-src").apply { mkdirs() },
                    qualifierClassNames = listOf(Fqcn("example.NonNull")),
                    qualifiersCount = 1, processor = "example.Checker",
                    checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
                )
                val outcome = ApplicationStage(EditingAgent(), FailingCompileBuildRunner).execute(
                    ctx, ApplicationInput(ApplicationTarget(CommitHash("a".repeat(40)), worktree), lattice)
                )

                assertTrue(outcome is StageResult.TransientFailure)
                val records = database.readDiscardedAttempts(runId)
                assertEquals(ApplicationStage.MAX_ATTEMPTS, records.size)
                assertTrue(records.all { it.discardCheck == DiscardCheck.COMPILE })
                assertTrue(records.all { it.stageAttemptNumber == 1 })
                assertTrue(records.all { it.termination == AgentSessionTermination.NORMAL_EXIT })
                records.forEach { record ->
                    // The worktree was reset for the next attempt, but the patch
                    // snapshot still carries the rejected annotation edit.
                    assertTrue(File(record.patchPath!!).readText().contains("@example.NonNull"))
                    assertTrue(File(record.diagnosticsPath!!).readText().contains("cannot find symbol"))
                    assertEquals(null, record.patchOmittedReason)
                }
                assertEquals("", Git.statusPorcelain(worktree).trim())
            } finally {
                database.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
