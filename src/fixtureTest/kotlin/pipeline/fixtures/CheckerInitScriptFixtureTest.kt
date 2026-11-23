package pipeline.fixtures

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import pipeline.config.BuildSection
import pipeline.config.EnvironmentSection
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.DatabasePort
import pipeline.runners.CheckerInitScript
import pipeline.runners.JavacRunner
import pipeline.runners.parseCheckerDiagnostics
import pipeline.stages.CheckerCategory
import pipeline.stages.CheckerConfig
import pipeline.stages.EvalVerdict
import pipeline.stages.Fqcn
import pipeline.stages.PreflightStage
import pipeline.stages.RunContext
import pipeline.stages.RunId
import pipeline.stages.writeCheckerInitScript
import pipeline.stages.writeRunCompileScript
import pipeline.util.FileOps

/**
 * Runs the real `checker.init.gradle` with Gradle TestKit on small generated
 * projects, one per build condition that broke the harness on a third-party
 * repository. Each test fails against the init script from before its fix
 * (commit 9cc12d7 for bugs 1-4).
 *
 * Every checker assertion plants ill-typed returns and requires each to be
 * reported: "the build succeeds" also holds when the checker silently did not
 * run. Projects that compile with `-Werror` supply their own checker-qual where
 * the test targets checkerPass, so that the pre-fix compile (bug 3) does not
 * fail first and hide the checkerPass behavior under test.
 *
 * The fixtures are offline: no repositories, no test framework, no toolchain.
 */
class CheckerInitScriptFixtureTest {

    // --- Bug 2: module projects --------------------------------------------

    /** Also covers bug 3 in module mode (checker-qual must be readable) and `-Xlint:-exports`. */
    @Test
    fun `module project compiles with its descriptor and the checker reports the planted error`() = fixture { p ->
        p.strictGroovyBuild()
        p.moduleInfo("module demo.app { exports demo.app; }")
        p.packageInfo()
        p.account(planted = 1)
        p.testSupport()

        val compile = p.run("compileJava", "compileTestJava")
        assertSucceeded(compile, ":compileJava", ":compileTestJava")
        assertTrue(p.dir.resolve("build/classes/java/main/module-info.class").isFile, "the descriptor must be kept")

        p.assertPlantedErrorsReported(p.run("checkerPass"))
    }

    @Test
    fun `module that requires checker-qual itself still compiles and checks`() = fixture { p ->
        p.groovyBuild(
            """
            dependencies { compileOnly files('${CHECKER_QUAL.invariantSeparatorsPath}') }
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs += ['-Xlint:all,-requires-automatic', '-Werror']
            }
            """
        )
        p.moduleInfo("module demo.app { exports demo.app; requires static org.checkerframework.checker.qual; }")
        p.packageInfo()
        p.account(planted = 1)
        p.testSupport()

        assertSucceeded(p.run("compileJava", "compileTestJava"), ":compileJava", ":compileTestJava")
        p.assertPlantedErrorsReported(p.run("checkerPass"))
    }

    @Test
    fun `a harness jar that is an explicit module is rejected with a clear message`() = fixture { p ->
        p.groovyBuild("")
        p.moduleInfo("module demo.app { exports demo.app; }")
        p.account(planted = 0)

        val result = p.run("compileJava", env = mapOf("CF_EXTRA_CLASSPATH" to explicitModuleJar(p.dir.parentFile).path))
        assertEquals(TaskOutcome.FAILED, result.task(":compileJava")?.outcome, result.output)
        assertTrue("is an explicit module" in result.output, result.output)
    }

    // --- Bug 3: checker-qual on the main compile classpath -------------------

    @Test
    fun `strict project with a checker-qual package-info compiles against the harness checker-qual`() = fixture { p ->
        p.strictGroovyBuild()
        p.packageInfo()
        p.account(planted = 1)
        p.testSupport()

        assertSucceeded(p.run("compileJava", "compileTestJava"), ":compileJava", ":compileTestJava")
        val checker = p.run("checkerPass")
        p.assertPlantedErrorsReported(checker)
        assertFalse("warnings found and -Werror specified" in checker.output, checker.output)
    }

    /** Agrona: qualifiers alone make javac warn "Cannot find annotation method 'value()' in type 'SubtypeOf'". */
    @Test
    fun `strict project without a package-info compiles against the harness checker-qual`() = fixture { p ->
        p.strictGroovyBuild()
        p.account(planted = 0)

        val compile = p.run("compileJava")
        assertSucceeded(compile, ":compileJava")
        assertFalse("Cannot find annotation method" in compile.output, compile.output)
    }

    // --- Bug 4: checkerPass gets the checker's compiler configuration --------

    /**
     * With inherited `-Werror`, the warning "No processor claimed any of these
     * annotations" about the package-info's `@DefaultQualifier` failed
     * checkerPass and the planted error was never reported. The package-info is
     * what makes the inherited flags observable.
     */
    @Test
    fun `inherited -Werror and -Xlint do not reach checkerPass`() = fixture { p ->
        p.strictGroovyBuild(ownCheckerQual = true)
        p.packageInfo()
        p.account(planted = 1)

        assertSucceeded(p.run("compileJava"), ":compileJava")
        val checker = p.run("checkerPass")
        p.assertPlantedErrorsReported(checker)
        assertFalse("warnings found and -Werror specified" in checker.output, checker.output)
        assertFalse("No processor claimed" in checker.output, checker.output)
    }

    /** `-proc:none` in compilerArgs silently disabled the checker: the build "succeeded" with no errors. */
    @Test
    fun `proc none and other diagnostics flags from compilerArgs are dropped`() = fixture { p ->
        p.groovyBuild(
            """
            dependencies { compileOnly files('${CHECKER_QUAL.invariantSeparatorsPath}') }
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs = ['--release', '11', '-proc:none', '-Werror', '-Xlint:all', '-parameters']
            }
            """
        )
        p.account(planted = 2)

        assertSucceeded(p.run("compileJava"), ":compileJava")
        p.assertPlantedErrorsReported(p.run("checkerPass"))
    }

    /** Flags that decide how sources compile are kept: the classes are Java 11 and keep parameter names. */
    @Test
    fun `--release and -parameters from compilerArgs are kept`() = fixture { p ->
        p.groovyBuild(
            """
            dependencies { compileOnly files('${CHECKER_QUAL.invariantSeparatorsPath}') }
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs = ['--release', '11', '-proc:none', '-Werror', '-Xlint:all', '-parameters']
            }
            """
        )
        p.account(planted = 0)

        // CF_SOURCE_COMPATIBILITY is 17; Java 11 class files (major 55) mean the project's --release won.
        val checker = p.run("checkerPass")
        assertSucceeded(checker, ":checkerPass")
        val account = p.dir.resolve("build/checker-classes/demo/app/Account.class")
        assertEquals(55, classFileMajor(account))
        assertTrue("MethodParameters" in String(account.readBytes(), Charsets.ISO_8859_1), "-parameters was dropped")
    }

    /** A project doFirst runs at execution time; the sanitizer must still have the last word. */
    @Test
    fun `proc none added by a project doFirst does not disable the checker`() = fixture { p ->
        p.groovyBuild("tasks.withType(JavaCompile).configureEach { doFirst { options.compilerArgs += ['-proc:none'] } }")
        p.account(planted = 1)

        p.assertPlantedErrorsReported(p.run("checkerPass"))
    }

    /**
     * Error Prone adds -Xplugin:ErrorProne through an argument provider; it is
     * dropped. A provider's --add-exports decides whether sources compile; it is
     * kept. The plugin provider stands in for Error Prone and is attached only
     * to checkerPass, because compileJava has no Error Prone on its path here.
     */
    @Test
    fun `project argument providers pass through the allowlist`() = fixture { p ->
        p.groovyBuild(
            """
            class Fixed implements org.gradle.process.CommandLineArgumentProvider {
                @org.gradle.api.tasks.Input List<String> values
                Iterable<String> asArguments() { values }
            }
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgumentProviders.add(new Fixed(values: ['--add-exports', 'java.base/sun.nio.ch=ALL-UNNAMED']))
            }
            tasks.withType(JavaCompile).matching { it.name == 'checkerPass' }.configureEach {
                options.compilerArgumentProviders.add(new Fixed(values: ['-Xplugin:ErrorProne']))
            }
            """
        )
        p.account(planted = 1)
        p.source(
            "src/main/java/demo/app/Internal.java",
            "package demo.app;\nfinal class Internal {\n    static boolean direct(java.nio.ByteBuffer b) { return b instanceof sun.nio.ch.DirectBuffer; }\n}\n",
        )

        assertSucceeded(p.run("compileJava"), ":compileJava")
        val checker = p.run("checkerPass")
        p.assertPlantedErrorsReported(checker)
        assertFalse("plug-in not found" in checker.output, checker.output)
        assertFalse("is not visible" in checker.output, checker.output)
    }

    // --- Immutable or late-assigned compilerArgs (RoaringBitmap) -------------

    /** Pre-fix: "Could not create task ':checkerPass'. > java.lang.UnsupportedOperationException". */
    @Test
    fun `an immutable compilerArgs list does not break checkerPass`() = fixture { p ->
        p.kotlinBuild("tasks.withType<JavaCompile>().configureEach { options.compilerArgs = listOf(\"-Xlint:unchecked\") }")
        p.account(planted = 1)

        assertSucceeded(p.run("compileJava"), ":compileJava")
        p.assertPlantedErrorsReported(p.run("checkerPass"))
    }

    /** Pre-fix: the reassignment wiped -processor and the checker silently did not run. */
    @Test
    fun `compilerArgs reassigned after evaluation do not remove the checker`() = fixture { p ->
        p.groovyBuild("afterEvaluate { tasks.withType(JavaCompile).configureEach { options.compilerArgs = ['-Xlint:unchecked'] } }")
        p.account(planted = 1)

        p.assertPlantedErrorsReported(p.run("checkerPass"))
    }

    // --- Configuration-time safety -------------------------------------------

    /**
     * Plugins such as JavaCC realize every JavaCompile task while configuring,
     * checkerPass included. Its -Aquals validation must wait until checkerPass
     * executes, or builds that only compile fail.
     */
    @Test
    fun `realizing checkerPass during configuration does not validate the lattice`() = fixture { p ->
        p.groovyBuild("afterEvaluate { tasks.withType(JavaCompile).each { it.options.compilerArgs } }")
        p.account(planted = 0)

        assertSucceeded(p.run("compileJava", env = mapOf("CF_AQUALS" to "")), ":compileJava")
    }

    // --- Agent helper ----------------------------------------------------------

    /**
     * The application agent's run-compile.sh must compile qualifier-annotated
     * main and test sources (pre-fix it ran `./gradlew compileJava` without the
     * init script, so qualifier imports did not resolve) and must not run the
     * checker.
     */
    @Test
    fun `run-compile helper compiles annotated sources and never runs the checker`() = fixture { workspace ->
        workspace.groovyBuild("")
        workspace.account(planted = 1)
        workspace.testSupport()
        val agentLibs = workspace.dir.resolve(".agent-libs").apply { mkdirs() }
        PROBE_JAR.copyTo(agentLibs.resolve("custom-quals.jar"))
        val ctx = helperContext(workspace.dir)
        writeCheckerInitScript(workspace.dir)
        writeRunCompileScript(workspace.dir, PROBE_CHECKER, ctx)

        val home = TESTKIT_DIR.resolve("run-compile-home").apply { mkdirs() }
        home.resolve("gradle.properties").writeText("org.gradle.daemon=false\n")
        val process = ProcessBuilder("bash", workspace.dir.resolve("run-compile.sh").path)
            .directory(workspace.dir)
            .redirectErrorStream(true)
            .apply {
                environment().keys.removeIf { it in HARNESS_ENV_NAMES || it.startsWith("CF_") }
                environment()["JAVA_HOME"] = System.getProperty("java.home")
                environment()["GRADLE_USER_HOME"] = home.path
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertTrue(workspace.dir.resolve("build/classes/java/main/demo/app/Account.class").isFile, output)
        assertTrue(workspace.dir.resolve("build/classes/java/test/demo/app/AccountSupport.class").isFile, output)
        assertFalse(workspace.dir.resolve("build/checker-classes").exists(), "the helper must not run the checker")
        assertFalse(":checkerPass" in output, output)
    }

    // --- Fixture support -------------------------------------------------------

    private class Project(val dir: File) {
        private var plantedLines: List<Int> = emptyList()

        fun source(path: String, text: String) {
            dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
        }

        fun groovyBuild(body: String) {
            source("settings.gradle", "rootProject.name = 'fixture'\n")
            source("build.gradle", "plugins { id 'java' }\n${body.trimIndent()}\n")
        }

        fun kotlinBuild(body: String) {
            source("settings.gradle.kts", "rootProject.name = \"fixture\"\n")
            source("build.gradle.kts", "plugins { java }\n${body.trimIndent()}\n")
        }

        /** `-Xlint:all -Werror` on every compile task, as Agrona configures it. */
        fun strictGroovyBuild(ownCheckerQual: Boolean = false) = groovyBuild(
            (if (ownCheckerQual) "dependencies { compileOnly files('${CHECKER_QUAL.invariantSeparatorsPath}') }\n" else "") +
                "tasks.withType(JavaCompile).configureEach { options.compilerArgs += ['-Xlint:all', '-Werror'] }"
        )

        fun moduleInfo(text: String) = source("src/main/java/module-info.java", "$text\n")

        fun packageInfo() = source(
            "src/main/java/demo/app/package-info.java",
            """
            @DefaultQualifier(value = ProbeTop.class, locations = TypeUseLocation.FIELD)
            package demo.app;

            import lltas.probe.ProbeTop;
            import org.checkerframework.framework.qual.DefaultQualifier;
            import org.checkerframework.framework.qual.TypeUseLocation;
            """.trimIndent() + "\n",
        )

        /** A public API carrying a qualifier, plus [planted] ill-typed returns. */
        fun account(planted: Int) {
            val lines = mutableListOf(
                "package demo.app;",
                "",
                "import lltas.probe.ProbeBottom;",
                "import lltas.probe.ProbeTop;",
                "",
                "public class Account {",
                "    public Account() {}",
                "",
                "    public @ProbeTop String id(@ProbeTop String value) {",
                "        return value;",
                "    }",
            )
            val planted = (0 until planted).map { i ->
                lines += "    static @ProbeBottom Object planted$i() { return new Object(); }"
                lines.size
            }
            lines += "}"
            plantedLines = planted
            source("src/main/java/demo/app/Account.java", lines.joinToString("\n") + "\n")
        }

        /** Test sources that use the qualifiers, so compileTestJava proves they resolve there too. */
        fun testSupport() = source(
            "src/test/java/demo/app/AccountSupport.java",
            "package demo.app;\n\nimport lltas.probe.ProbeTop;\n\nfinal class AccountSupport {\n" +
                "    static @ProbeTop String id(@ProbeTop String value) { return new Account().id(value); }\n}\n",
        )

        fun run(vararg tasks: String, env: Map<String, String> = emptyMap()): BuildResult {
            val runner = GradleRunner.create()
                .withProjectDir(dir)
                .withTestKitDir(TESTKIT_DIR)
                .withEnvironment(fixtureEnvironment() + env)
                .withArguments(listOf("--init-script", INIT_SCRIPT.path, "--console=plain", "--stacktrace") + tasks)
            return try {
                runner.build()
            } catch (failure: UnexpectedBuildFailure) {
                failure.buildResult
            }
        }

        /** Every planted return, and nothing else, is reported as an error. */
        fun assertPlantedErrorsReported(result: BuildResult) {
            val errors = parseCheckerDiagnostics(result.output).filter { it.severity == "error" }
            val reported = errors.filter { it.filePath.endsWith("/Account.java") && it.errorCategory == "return" }
                .map { it.lineNumber }.sorted()
            assertEquals(plantedLines, reported, "planted errors at $plantedLines, got:\n${result.output}")
            assertEquals(plantedLines.size, errors.size, "unexpected extra errors:\n${result.output}")
            assertTrue(result.output.contains(":checkerPass"), "checkerPass must have run:\n${result.output}")
        }
    }

    private fun assertSucceeded(result: BuildResult, vararg tasks: String) {
        tasks.forEach { task ->
            assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, "$task:\n${result.output}")
        }
    }

    private fun fixture(block: (Project) -> Unit) {
        val root = Files.createTempDirectory("init-script-fixture").toFile()
        try {
            block(Project(root.resolve("project").apply { mkdirs() }))
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    private fun classFileMajor(file: File): Int {
        val bytes = file.readBytes()
        return ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
    }

    /** A jar with a `module-info.class`, built with the system compiler. */
    private fun explicitModuleJar(dir: File): File {
        val src = dir.resolve("explicit-src").apply { mkdirs() }
        val out = dir.resolve("explicit-classes").apply { mkdirs() }
        val info = src.resolve("module-info.java").apply { writeText("module explicit.mod { exports explicit; }\n") }
        val type = src.resolve("explicit/Thing.java").apply { parentFile.mkdirs(); writeText("package explicit; public class Thing {}\n") }
        val compiler = ToolProvider.getSystemJavaCompiler()
        check(compiler.run(null, null, null, "--release", "11", "-d", out.path, info.path, type.path) == 0)
        val jar = dir.resolve("explicit.jar")
        JarOutputStream(jar.outputStream()).use { zip ->
            out.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(JarEntry(file.relativeTo(out).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun helperContext(workspace: File) = RunContext(
        config = ProjectConfig(environment = EnvironmentSection(checkerFrameworkHome = CF_HOME.path)),
        build = BuildSection(wrapperExecutable = File(GRADLE_HOME, "bin/gradle").path),
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("run-compile-fixture"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = workspace,
        casDir = workspace,
        worktreeDir = workspace,
        agentWorkspaceDir = workspace.parentFile,
        database = NoopDatabase,
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

    companion object {
        private val CF_HOME = File(requireNotNull(System.getProperty("lltas.checkerFrameworkHome")))
        private val CHECKER_QUAL = CF_HOME.resolve("checker/dist/checker-qual.jar")
        private val TESTKIT_DIR = File(requireNotNull(System.getProperty("lltas.testKitDir")))
        private val GRADLE_HOME = File(requireNotNull(System.getProperty("lltas.gradleHome")))
        private val HARNESS_ENV_NAMES = setOf("CHECKERFRAMEWORK_HOME", "CUSTOM_QUAL_JAR_PATH", "GUARDRAILS_MODE")

        private val HARNESS_DIR: File by lazy { Files.createTempDirectory("init-script-harness").toFile() }

        /** The bundled template, outside every fixture project, as the harness renders it. */
        private val INIT_SCRIPT: File by lazy { CheckerInitScript.writeTo(HARNESS_DIR.resolve("init")) }

        /** The preflight probe lattice, compiled the way production compiles qualifiers (--release 8). */
        private val PROBE_JAR: File by lazy {
            val src = HARNESS_DIR.resolve("probe-src/lltas/probe").apply { mkdirs() }
            val sources = listOf("ProbeTop.java", "ProbeBottom.java").map { name ->
                src.resolve(name).apply { writeText(PreflightStage.template(name)) }
            }
            val jar = HARNESS_DIR.resolve("probe-quals.jar")
            val result = JavacRunner().compileAndPackageJar(sources, jar, listOf(CHECKER_QUAL))
            check(result.success) { "probe lattice: ${result.diagnostics}" }
            jar
        }

        private val PROBE_CHECKER: CheckerConfig by lazy {
            CheckerConfig(
                customQualsJar = PROBE_JAR,
                processor = PreflightStage.SUBTYPING_CHECKER,
                checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
                qualifierClassNames = listOf(Fqcn("lltas.probe.ProbeTop"), Fqcn("lltas.probe.ProbeBottom")),
            )
        }

        /** The ambient environment without harness settings, plus the harness's own. */
        private fun fixtureEnvironment(): Map<String, String> =
            System.getenv().filterKeys { it !in HARNESS_ENV_NAMES && !it.startsWith("CF_") } + mapOf(
                "CHECKERFRAMEWORK_HOME" to CF_HOME.path,
                "CUSTOM_QUAL_JAR_PATH" to PROBE_JAR.path,
                "CF_PROCESSOR" to PreflightStage.SUBTYPING_CHECKER,
                "CF_AQUALS" to "lltas.probe.ProbeTop,lltas.probe.ProbeBottom",
                "CF_CHECKER_CATEGORY" to "custom_qualifier_hierarchy",
                "CF_SOURCE_COMPATIBILITY" to "17",
                "CF_TARGET_COMPATIBILITY" to "17",
                "GUARDRAILS_MODE" to "lenient",
                // checkerPass forks with a 4g heap by default.
                "CF_FORK_MAX_HEAP" to "1g",
            )
    }
}
