package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.DatabasePort
import pipeline.util.FileOps

/**
 * The checker post-pass recompiles `main.allJava` with only the Checker
 * Framework processor. Annotation processors that generate types (AutoValue,
 * static JPA weaving) are therefore not re-run, and sources referencing their
 * output cannot compile unless the normally compiled classes are on the
 * classpath. CMSX hits this with seven AutoValue classes.
 */
class CheckerInitScriptTest {

    @Test
    fun `bundled template puts compiled main output on the checker classpath`() {
        val template = resourceText("/templates/checker.init.gradle")
        assertContains(template, "classpath = project.files(main.output,")
        assertContains(template, "dependsOn project.tasks.named('compileJava')")
    }

    @Test
    fun `repair agent init script puts compiled main output on the checker classpath`() {
        val root = Files.createTempDirectory("checker-init-script").toFile()
        try {
            val workspace = root.resolve("workspace")
            val ctx = RunContext(
                config = ProjectConfig(),
                build = BuildSection(),
                sourceCompatibility = "21",
                targetCompatibility = "21",
                runId = RunId("test-run"),
                sampleIndex = 0,
                sampleTemperature = 0.0,
                repoDir = root,
                casDir = root,
                worktreeDir = root,
                agentWorkspaceDir = workspace,
                database = NoopDatabase
            )
            val checker = CheckerConfig(
                customQualsJar = File(root, "custom-quals.jar"),
                processor = "org.checkerframework.common.subtyping.SubtypingChecker",
                checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
                qualifierClassNames = listOf(Fqcn("policy.qual.RawPolicyState"))
            )

            writeCheckerInitScript(workspace, checker, ctx)

            val script = root.resolve(".pipeline-init/checker.init.gradle").readText()
            assertContains(script, "classpath = project.files(main.output,")
            assertContains(script, "dependsOn project.tasks.named('compileJava')")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    private fun resourceText(path: String): String =
        CheckerInitScriptTest::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing classpath resource $path")

    private object NoopDatabase : DatabasePort {
        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) = Unit
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: CommitTriageUpdate) = Unit
        override suspend fun recordRetry(event: pipeline.db.RetryEvent) = Unit
    }
}
