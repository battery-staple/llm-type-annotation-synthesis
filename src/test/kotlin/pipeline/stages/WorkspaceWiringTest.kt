package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pipeline.config.BuildSection
import pipeline.config.PromptSection
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.DatabasePort
import pipeline.runners.CheckerInitScript
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.validators.GitRepos

/**
 * The application and repair stages build their agent workspaces through
 * prepareWorkspace / prepareRepairWorkspace. These tests check what those
 * functions produce, not the helpers they call, on a linked worktree like the
 * one the pipeline uses (its `.git` is a file).
 */
class WorkspaceWiringTest {

    @Test
    fun `application workspace has the compile helper, the init script, a snapshot repository, and no agent files`() =
        withWorktree { root, worktree ->
            val workspace = root.resolve("scratch/application_ws")
            val ctx = context(root, workspace)
            prepareWorkspace(worktree, workspace, lattice(root), ctx)

            assertTrue(workspace.resolve("run-compile.sh").canExecute())
            assertSnapshot(workspace)
            assertInitScriptBeside(workspace)

            // Retries and refreshes rebuild the workspace; it must stay a one-commit repository.
            prepareWorkspace(worktree, workspace, lattice(root), ctx)
            prepareWorkspace(worktree, workspace, lattice(root), ctx)
            assertSnapshot(workspace)
        }

    @Test
    fun `repair workspace has the checker helper, the init script, a snapshot repository, and no agent files`() =
        withWorktree { root, worktree ->
            val workspace = root.resolve("scratch/repair_ws")
            val ctx = context(root, workspace)
            prepareRepairWorkspace(worktree, workspace, lattice(root).toCheckerConfig(), ctx)

            assertTrue(workspace.resolve("run-checker.sh").canExecute())
            assertSnapshot(workspace)
            assertInitScriptBeside(workspace)

            refreshWorkspace(worktree, workspace, lattice(root).toCheckerConfig(), ctx)
            refreshWorkspace(worktree, workspace, lattice(root).toCheckerConfig(), ctx)
            assertSnapshot(workspace)
        }

    /**
     * The flag is an ablation: the requirement must reach the agent only when
     * it is set. Pre-fix, the template stated it unconditionally.
     */
    @Test
    fun `application prompt states the non-triviality requirement only when the flag is set`() = withDir { root ->
        val template = PromptTemplates.load("application.md")
        fun render(tell: Boolean): String {
            val base = context(root, root.resolve("ws"))
            val ctx = base.copy(config = base.config.copy(prompt = PromptSection(tellNonTrivialityError = tell)))
            return applicationPrompt(template, lattice(root), ctx)
        }
        val silent = render(false)
        val told = render(true)
        assertFalse("at least one" in silent, silent)
        assertTrue("at least one" in told, told)
        assertNotEquals(silent, told)
        listOf(silent, told).forEach { prompt ->
            assertFalse(Regex("""\{\{\w+}}""").containsMatchIn(prompt), "unfilled placeholder in:\n$prompt")
        }
    }

    private fun assertSnapshot(workspace: File) {
        val git = workspace.resolve(".git")
        assertTrue(git.isDirectory, "the workspace must have its own repository, not a worktree link")
        assertEquals("1", Git.requireOk(Git.run(workspace, "rev-list", "--all", "--count"), "rev-list"))
        assertFalse(workspace.resolve("AGENTS.md").exists())
        assertFalse(workspace.resolve("module/CLAUDE.md").exists())
        val tracked = Git.requireOk(Git.run(workspace, "ls-files"), "ls-files").lines()
        assertFalse(tracked.any { it.endsWith("AGENTS.md") || it.endsWith("CLAUDE.md") }, "$tracked")
        assertTrue("src/main/java/demo/App.java" in tracked, "$tracked")
    }

    private fun assertInitScriptBeside(workspace: File) {
        val script = workspace.parentFile.resolve(".pipeline-init/checker.init.gradle")
        assertEquals(CheckerInitScript.template(), script.readText())
        assertFalse(FileOps.isWithin(script, workspace), "the init script must stay outside the workspace")
    }

    private fun lattice(root: File): SynthesizedLattice {
        val src = root.resolve("lattice/src").apply { mkdirs() }
        return SynthesizedLattice(
            customQualsJar = root.resolve("lattice/custom-quals.jar").apply { parentFile.mkdirs(); writeText("not a real jar") },
            placementGuideFile = root.resolve("lattice/placement_guide.md").apply { writeText("# Guide\n") },
            customQualsSrcDir = src,
            qualifierClassNames = listOf(Fqcn("demo.qual.Raw")),
            qualifiersCount = 1,
            processor = "org.checkerframework.common.subtyping.SubtypingChecker",
            checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
        )
    }

    private fun context(root: File, workspace: File) = RunContext(
        config = ProjectConfig(),
        build = BuildSection(),
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("workspace-wiring"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = root,
        casDir = root,
        worktreeDir = root,
        agentWorkspaceDir = workspace.parentFile,
        database = NoopDatabase,
    )

    /** A repository with agent files, checked out as a linked worktree. */
    private fun withWorktree(block: (File, File) -> Unit) = withDir { root ->
        val origin = root.resolve("origin").apply { mkdirs() }
        GitRepos.init(
            origin,
            mapOf(
                "src/main/java/demo/App.java" to "package demo; class App {}\n",
                "src/test/java/demo/AppTest.java" to "package demo; class AppTest {}\n",
                "AGENTS.md" to "repository agent instructions\n",
                "module/CLAUDE.md" to "nested instructions\n",
            )
        )
        val worktree = root.resolve("worktree")
        Git.addWorktree(origin, worktree, Git.revParse(origin, "HEAD"))
        assertTrue(worktree.resolve(".git").isFile, "precondition: a linked worktree")
        block(root, worktree)
    }

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("workspace-wiring").toFile()
        try {
            block(dir)
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }

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
