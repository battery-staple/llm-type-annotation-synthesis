package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `repair agent init script is the bundled harness template`() {
        val root = Files.createTempDirectory("checker-init-script").toFile()
        try {
            val workspace = root.resolve("workspace")
            writeCheckerInitScript(workspace)

            // The agent's checker must be the one that scores it; a hand-maintained
            // copy had drifted (Gradle 8-only APIs, no module-info exclusion).
            val script = root.resolve(".pipeline-init/checker.init.gradle").readText()
            assertEquals(resourceText("/templates/checker.init.gradle"), script)
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `repair run-checker script invokes the project-qualified checker task`() {
        val root = Files.createTempDirectory("checker-run-script").toFile()
        try {
            val workspace = root.resolve("workspace").also { it.mkdirs() }
            val ctx = context(root, workspace, BuildSection(subproject = "core"))
            writeRunCheckerScript(workspace, checker(root), ctx)

            val script = workspace.resolve("run-checker.sh").readText()
            assertContains(script, "exec ./gradlew :core:checkerPass --init-script")
            assertContains(script, "export CF_AQUALS=\"policy.qual.RawPolicyState\"")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    /**
     * JSqlParser's JavaCC plugin realizes every JavaCompile task during
     * configuration, `checkerPass` included, even when only `compileJava` runs.
     * The -Aquals check must therefore wait until checkerPass executes, not run
     * when the task is configured.
     */
    @Test
    fun `template defers the -Aquals check to checkerPass execution`() {
        val template = resourceText("/templates/checker.init.gradle")
        val registerBlock = template.substringAfter("project.tasks.register('checkerPass', JavaCompile) {")
            .substringBefore("// Qualifier jars are compile/runtime visible")
        assertContains(registerBlock, "options.compilerArgumentProviders.add(")
        assertFalse("CF_AQUALS is required" in registerBlock, "the check must not run while the task is configured")
        assertFalse("-Aquals=" in registerBlock, "-Aquals must come from the argument provider")

        val provider = template.substringAfter("class CheckerQualsArgumentProvider")
            .substringBefore("allprojects {")
        assertContains(provider, "Iterable<String> asArguments()")
        assertContains(provider, "CF_AQUALS is required for custom_qualifier_hierarchy")
        assertContains(provider, "CF_AQUALS must be empty for builtin_checker")
        // Both values stay @Input so a changed lattice is not reported UP-TO-DATE.
        assertEquals(2, Regex("""@org\.gradle\.api\.tasks\.Input""").findAll(provider).count())
    }

    private fun checker(root: File) = CheckerConfig(
        customQualsJar = File(root, "custom-quals.jar"),
        processor = "org.checkerframework.common.subtyping.SubtypingChecker",
        checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
        qualifierClassNames = listOf(Fqcn("policy.qual.RawPolicyState"))
    )

    private fun context(root: File, workspace: File, build: BuildSection) = RunContext(
        config = ProjectConfig(),
        build = build,
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
        override suspend fun recordSubprocess(metric: pipeline.db.SubprocessMetricRecord) = Unit
    }
}
