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
            .substringBefore("// Every other compile task whose sources declare a module")
        assertContains(registerBlock, "options.compilerArgumentProviders.add(")
        assertFalse("CF_AQUALS is required" in registerBlock, "the check must not run while the task is configured")
        assertFalse("-Aquals=" in registerBlock, "-Aquals must come from the argument provider")

        val provider = template.substringAfter("class CheckerQualsArgumentProvider")
            .substringBefore("class CheckerArgumentsProvider")
        assertContains(provider, "Iterable<String> asArguments()")
        assertContains(provider, "CF_AQUALS is required for custom_qualifier_hierarchy")
        assertContains(provider, "CF_AQUALS must be empty for builtin_checker")
        // Both values stay @Input so a changed lattice is not reported UP-TO-DATE.
        assertEquals(2, Regex("""@org\.gradle\.api\.tasks\.Input""").findAll(provider).count())
    }

    /**
     * Module projects (JSqlParser, RoaringBitmap, caffeine, picocli): excluding
     * module-info.java from the source set did not avoid module mode, because
     * javac rediscovered it through -sourcepath, and it stripped the descriptor
     * from the project's own compile. The descriptor is kept, and the module is
     * granted read access to the harness jars instead.
     */
    @Test
    fun `template keeps module descriptors and grants module reads per task`() {
        val template = resourceText("/templates/checker.init.gradle")
        assertFalse("main.java.exclude" in template, "the main source set must keep module-info.java")
        val provider = template.substringAfter("class ModuleReadsArgumentProvider")
            .substringBefore("class CheckerCompileSanitizer")
        assertContains(provider, "it.name == 'module-info.java'")
        assertContains(provider, "'--add-reads'")
        assertContains(provider, "'--add-modules'")
        assertContains(provider, "ALL-UNNAMED")
        val wiring = template.substringAfter("project.tasks.withType(JavaCompile).configureEach { compileTask ->")
        assertContains(wiring.substringBefore("}"), "if (compileTask.name == 'checkerPass') return")
    }

    @Test
    fun `checkerPass alone compiles in classpath mode without the descriptor`() {
        val registerBlock = checkerPassBlock()
        assertContains(registerBlock, "exclude '**/module-info.java'")
        assertContains(registerBlock, "modularity.inferModulePath.set(false)")
        assertContains(registerBlock, "options.sourcepath = project.files()")
    }

    /**
     * Agrona: -Werror without checker-qual on the main classpath fails on
     * "Cannot find annotation method 'value()' in type 'SubtypeOf'", and a
     * package-info.java importing checker-qual does not compile at all.
     */
    @Test
    fun `checker-qual is on the main compile classpath`() {
        val template = resourceText("/templates/checker.init.gradle")
        assertContains(
            template,
            "project.sourceSets.main.compileClasspath += project.files(checkerQualJar, customQualJar, extraFiles)"
        )
    }

    /**
     * Agrona configures every JavaCompile task with -Xlint:all -Werror, and
     * checkerPass inherited them. The sanitizer runs at execution, after every
     * configureEach action, and keeps only compile-semantics flags.
     */
    @Test
    fun `checkerPass replaces inherited compiler flags at execution time`() {
        val registerBlock = checkerPassBlock()
        assertContains(registerBlock, "doFirst(new CheckerCompileSanitizer(")
        val sanitizer = resourceText("/templates/checker.init.gradle")
            .substringAfter("class CheckerCompileSanitizer")
            .substringBefore("allprojects {")
        // Kept: flags without which sources may not compile (agrona passes --release in compilerArgs).
        listOf("'--release'", "'--add-exports'", "'--enable-preview'", "'-encoding'").forEach {
            assertContains(sanitizer, it)
        }
        // Dropped with their values, so a value cannot become a stray argument.
        listOf("'-processorpath'", "'-Xmaxerrs'", "'--add-reads'").forEach {
            assertContains(sanitizer.substringAfter("DROP_WITH_VALUE"), it)
        }
        assertContains(sanitizer, "it instanceof CheckerQualsArgumentProvider || it instanceof CheckerArgumentsProvider")
        assertContains(sanitizer, "findByName('errorprone')")
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

    @Test
    fun `application prompt states the non-triviality requirement only when the flag is set`() {
        assertEquals("", nonTrivialityRequirement(false))
        assertContains(nonTrivialityRequirement(true), "at least one")
        val template = PromptTemplates.load("application.md")
        assertFalse("at least one" in template, "the requirement must come only from the flag")
        assertContains(template, "{{non_triviality_requirement}}")
        assertContains(template, "./run-compile.sh")
        assertFalse("classpath you set up yourself" in template)
    }

    private fun checkerPassBlock(): String =
        resourceText("/templates/checker.init.gradle")
            .substringAfter("project.tasks.register('checkerPass', JavaCompile) {")
            .substringBefore("// Every other compile task whose sources declare a module")

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
