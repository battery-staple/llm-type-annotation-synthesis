package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.DatabasePort
import pipeline.util.Git

class ContaminationScopeTest {

    @Test
    fun `contamination scope follows checkout path so all runs on a checkout share guard state`() {
        val root = Files.createTempDirectory("contamination-scope").toFile()
        try {
            val repo = File(root, "checkout").apply { mkdirs() }
            initRepo(repo)
            val first = context(repo, RunId("run-1"))
            val second = context(repo, RunId("run-2"))
            val third = context(repo, RunId("run-3"))

            assertEquals(repo.canonicalPath, contaminationScopeFor(repo))
            assertEquals(first.contaminationScope, second.contaminationScope)
            assertEquals(second.contaminationScope, third.contaminationScope)

            // The guard is reset once at the top level for the shared checkout,
            // not per run; no later run can clear another run's poison.
            BenchmarkCheckoutGuard.reset(repo, contaminationScopeFor(repo))
            File(repo, "tracked.txt").writeText("contaminated")
            assertFailsWith<StageHaltException> { haltIfBenchmarkRepoDirty(first, "stage") }
            // Every other run on the same checkout must see the poisoned state.
            assertFailsWith<StageHaltException> { haltIfBenchmarkRepoDirty(second, "stage") }
            assertFailsWith<StageHaltException> { haltIfBenchmarkRepoDirty(third, "stage") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `contamination state is isolated between different checkout paths`() {
        val root = Files.createTempDirectory("contamination-scope-paths").toFile()
        try {
            val first = File(root, "checkout-a").apply { mkdirs() }
            val second = File(root, "checkout-b").apply { mkdirs() }
            initRepo(first)
            initRepo(second)
            assertNotEquals(contaminationScopeFor(first), contaminationScopeFor(second))

            BenchmarkCheckoutGuard.reset(first, contaminationScopeFor(first))
            BenchmarkCheckoutGuard.reset(second, contaminationScopeFor(second))
            File(first, "tracked.txt").writeText("contaminated")

            // Poisoning one checkout must not poison a different checkout.
            assertFailsWith<StageHaltException> {
                haltIfBenchmarkRepoDirty(context(first, RunId("run-a")), "stage")
            }
            haltIfBenchmarkRepoDirty(context(second, RunId("run-b")), "stage")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reset clears prior poison so a later run on the checkout can proceed`() {
        val root = Files.createTempDirectory("contamination-reset").toFile()
        try {
            val repo = File(root, "checkout").apply { mkdirs() }
            initRepo(repo)
            val scope = contaminationScopeFor(repo)
            val ctx = context(repo, RunId("run-1"))

            BenchmarkCheckoutGuard.reset(repo, scope)
            File(repo, "tracked.txt").writeText("contaminated")
            assertFailsWith<StageHaltException> { haltIfBenchmarkRepoDirty(ctx, "stage") }
            // Still poisoned until reset; a second check must not re-run the agent.
            assertFailsWith<StageHaltException> { haltIfBenchmarkRepoDirty(ctx, "stage") }

            BenchmarkCheckoutGuard.reset(repo, scope)
            // The first check rolled the checkout back, so a fresh run now passes.
            haltIfBenchmarkRepoDirty(ctx, "stage")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun initRepo(repo: File) {
        Git.run(repo, "init", "-q")
        Git.run(repo, "config", "user.email", "test@example.com")
        Git.run(repo, "config", "user.name", "Test")
        File(repo, "tracked.txt").writeText("baseline")
        Git.run(repo, "add", "-A")
        Git.run(repo, "commit", "-q", "--no-gpg-sign", "-m", "baseline")
    }

    private fun context(repoDir: File, runId: RunId) = RunContext(
        config = ProjectConfig(),
        build = BuildSection(),
        sourceCompatibility = "21",
        targetCompatibility = "21",
        runId = runId,
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = repoDir,
        casDir = repoDir,
        worktreeDir = repoDir,
        agentWorkspaceDir = repoDir,
        database = NoopDatabase
    )

    private object NoopDatabase : DatabasePort {
        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) = Unit
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: CommitTriageUpdate) = Unit
        override suspend fun recordRetry(event: pipeline.db.RetryEvent) = Unit
        override suspend fun recordSubprocess(metric: pipeline.db.SubprocessMetricRecord) = Unit
        override suspend fun recordDiscardedAttempt(record: pipeline.db.DiscardedAttemptRecord) = Unit
        override suspend fun readDiscardedAttempts(
            runId: RunId,
            stage: String?,
            stageAttemptNumber: Int?,
            internalAttemptNumber: Int?
        ): List<pipeline.db.DiscardedAttemptRecord> = emptyList()
    }
}
