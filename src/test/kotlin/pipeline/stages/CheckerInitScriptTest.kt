package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test
    fun `builtin repair run-checker script leaves -Aquals empty`() {
        val root = Files.createTempDirectory("checker-run-script-builtin").toFile()
        try {
            val workspace = root.resolve("workspace").also { it.mkdirs() }
            val ctx = context(root, workspace, BuildSection())
            val builtin = CheckerConfig(
                customQualsJar = File(root, "custom-quals.jar"),
                processor = BuiltinCheckerCatalog.nullness.processor,
                checkerCategory = CheckerCategory.BUILTIN_CHECKER,
                qualifierClassNames = emptyList(),
            )
            writeRunCheckerScript(workspace, builtin, ctx)

            val script = workspace.resolve("run-checker.sh").readText()
            assertContains(script, "export CF_AQUALS=\"\"")
            assertContains(script, "export CF_CHECKER_CATEGORY=\"builtin_checker\"")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `application compile helper uses the harness init script and never runs the checker`() {
        val root = Files.createTempDirectory("run-compile-script").toFile()
        try {
            val workspace = root.resolve("workspace").also { it.mkdirs() }
            val ctx = context(root, workspace, BuildSection(subproject = "core"))
            writeRunCompileScript(workspace, checker(root), ctx)

            val script = workspace.resolve("run-compile.sh").readText()
            assertTrue(script.startsWith("#!/usr/bin/env bash\n"), "shebang must start the file:\n$script")
            assertContains(script, "exec ./gradlew :core:compileJava :core:compileTestJava --init-script")
            assertContains(script, "export CUSTOM_QUAL_JAR_PATH=\"\$SCRIPT_DIR/.agent-libs/custom-quals.jar\"")
            assertContains(script, "export CF_AQUALS=\"policy.qual.RawPolicyState\"")
            assertFalse("checkerPass" in script, "the application agent must not run the checker")
            assertFalse(Regex("""(?m)^\s+export""").containsMatchIn(script), "exports must not be indented:\n$script")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `repair run-checker script keeps a column-zero shebang`() {
        val root = Files.createTempDirectory("checker-run-shebang").toFile()
        try {
            val workspace = root.resolve("workspace").also { it.mkdirs() }
            writeRunCheckerScript(workspace, checker(root), context(root, workspace, BuildSection()))
            val script = workspace.resolve("run-checker.sh").readText()
            assertTrue(script.startsWith("#!/usr/bin/env bash\n"), "shebang must start the file:\n$script")
            assertContains(script, "\nexport GUARDRAILS_MODE=\"lenient\"\n")
        } finally {
            FileOps.deleteRecursively(root)
        }
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
        override suspend fun recordDiscardedAttempt(record: pipeline.db.DiscardedAttemptRecord) = Unit
        override suspend fun readDiscardedAttempts(
            runId: pipeline.stages.RunId,
            stage: String?,
            stageAttemptNumber: Int?,
            internalAttemptNumber: Int?
        ): List<pipeline.db.DiscardedAttemptRecord> = emptyList()
    }
}
