package pipeline.runners

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.w3c.dom.Element
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.ports.BuildResult
import pipeline.ports.BuildRunner
import pipeline.ports.CheckerDiagnostic
import pipeline.ports.CheckerResult
import pipeline.ports.TestOutcome
import pipeline.ports.TestRunResult
import pipeline.ports.TestStatus
import pipeline.stages.CheckerCategory
import pipeline.stages.CheckerConfig
import pipeline.stages.Fqcn
import pipeline.stages.GuardrailsMode
import pipeline.stages.TestCoordinate
import pipeline.util.Processes

/**
 * Executes `./gradlew` in the target worktree. Checker runs use a dedicated
 * `checkerPass` task injected via `--init-script`; the processor is never
 * attached to normal `compileJava`/`test` passes.
 */
class GradleBuildRunner(
    private val config: ProjectConfig,
    private val build: BuildSection,
    private val sourceCompatibility: String,
    private val targetCompatibility: String,
    private val concurrency: Semaphore = Semaphore(2)
) : BuildRunner {

    override suspend fun compile(
        worktreeDir: File,
        checker: CheckerConfig?,
        extraClasspath: List<File>
    ): BuildResult {
        val env = baseEnv().toMutableMap()
        val args = mutableListOf(
            build.wrapperExecutable,
            prefixed(build.compileTask),
            "--console=plain", "--stacktrace", "--no-watch-fs"
        )
        if (checker != null) addCheckerInit(worktreeDir, checker, extraClasspath, args, env)
        val result = runGradle(worktreeDir, args, env)
        return BuildResult(
            success = result.exitCode == 0 && !result.timedOut,
            exitCode = result.exitCode,
            output = result.output,
            timedOut = result.timedOut
        )
    }

    override suspend fun runTests(
        worktreeDir: File,
        testFilter: TestCoordinate?,
        checker: CheckerConfig?,
        extraClasspath: List<File>
    ): TestRunResult {
        val env = baseEnv().toMutableMap()
        val cleanTask = "clean" + build.testTask.replaceFirstChar { it.uppercaseChar() }
        val args = mutableListOf(
            build.wrapperExecutable,
            prefixed(cleanTask),
            prefixed(build.testTask),
            "--console=plain", "--stacktrace", "--no-watch-fs"
        )
        if (testFilter != null) {
            args += "--tests"
            args += testFilter.gradleFilter
        }
        if (checker != null) addCheckerInit(worktreeDir, checker, extraClasspath, args, env)
        val result = runGradle(worktreeDir, args, env)
        val output = result.output
        val xmlFiles = findTestXml(worktreeDir)
        val outcomes = xmlFiles.flatMap { parseTestXml(it) }
        val testsFound = outcomes.size
        val compileFailed = isTestCompileFailure(result.exitCode, xmlFiles.isEmpty(), output)
        return TestRunResult(
            success = result.exitCode == 0 && !result.timedOut && xmlFiles.isNotEmpty(),
            exitCode = result.exitCode,
            outcomes = outcomes,
            xmlPaths = xmlFiles,
            compileFailed = compileFailed,
            testsFound = testsFound,
            output = output,
            timedOut = result.timedOut
        )
    }

    override suspend fun runChecker(
        worktreeDir: File,
        checkerHome: File,
        customQualsJar: File,
        processor: String,
        quals: List<Fqcn>,
        checkerCategory: CheckerCategory,
        guardrailsMode: GuardrailsMode
    ): CheckerResult {
        val script = renderInitScript(worktreeDir)
        val env = baseEnv().toMutableMap()
        // checkerHome is recorded for provenance; the init script resolves the
        // framework jars from CHECKERFRAMEWORK_HOME.
        env.putAll(
            checkerEnv(
                processor = processor,
                quals = quals,
                checkerCategory = checkerCategory,
                guardrailsMode = guardrailsMode,
                customQualsJar = customQualsJar,
                extraClasspath = emptyList(),
                checkerHome = checkerHome
            )
        )
        val args = listOf(
            build.wrapperExecutable,
            prefixed(build.checkerTask),
            "--init-script", script.absolutePath,
            "--console=plain", "--stacktrace", "--no-watch-fs"
        )
        val result = runGradle(worktreeDir, args, env)
        val output = result.output
        val logDir = worktreeDir.absoluteFile.parentFile?.resolve(".pipeline-logs") ?: worktreeDir.resolve(".pipeline-logs")
        logDir.mkdirs()
        val diagnosticsLog = File(logDir, "checker-${System.currentTimeMillis()}.log")
        runCatching { diagnosticsLog.writeText(output) }

        val diagnostics = parseCheckerDiagnostics(output)
        val errorCount = diagnostics.count { it.severity == "error" }
        val warningCount = diagnostics.count { it.severity == "warning" }
        val hasBracketCategory = diagnostics.any { it.errorCategory.isNotBlank() }
        val outputMentionsTask = output.contains("checkerPass") &&
            !output.contains("Task 'checkerPass' not found")
        val simpleName = processor.substringAfterLast('.')
        val outputMentionsProcessor = output.contains(simpleName) || output.contains("Checker Framework")
        val checkerInvoked = hasBracketCategory || outputMentionsTask || outputMentionsProcessor
        // A crashed checker (e.g. a malformed qualifier hierarchy) prints no
        // file diagnostics, so without this it would look like a clean run with
        // zero errors.
        val crashed = outputShowsCheckerCrash(output)
        return CheckerResult(
            success = !result.timedOut && checkerInvoked && !crashed,
            exitCode = result.exitCode,
            checkerInvoked = checkerInvoked,
            errorCount = errorCount,
            warningCount = warningCount,
            diagnostics = diagnostics,
            diagnosticsLog = diagnosticsLog,
            timedOut = result.timedOut
        )
    }

    // --- Helpers ---------------------------------------------------------

    private fun prefixed(task: String): String = qualifiedTask(build, task)

    /**
     * Injects the init script for a compile/test invocation. The env carries the
     * synthesized lattice, not a static config value: the init script registers
     * `checkerPass` in every such build, and plugins that eagerly realize all
     * `JavaCompile` tasks (JavaCC's `JavaToJavaccDependencyAction`) configure it
     * even when only `compileJava` or `test` was requested.
     */
    private fun addCheckerInit(
        worktreeDir: File,
        checker: CheckerConfig,
        extraClasspath: List<File>,
        args: MutableList<String>,
        env: MutableMap<String, String>
    ) {
        val script = renderInitScript(worktreeDir)
        args += listOf("--init-script", script.absolutePath)
        env.putAll(
            checkerEnv(
                processor = checker.processor,
                quals = checker.qualifierClassNames,
                checkerCategory = checker.checkerCategory,
                guardrailsMode = config.guardrails.mode,
                customQualsJar = checker.customQualsJar,
                extraClasspath = extraClasspath
            )
        )
    }

    private fun baseEnv(): Map<String, String> = buildMap {
        if (config.environment.javaHome.isNotBlank()) put("JAVA_HOME", config.environment.javaHome)
        if (config.environment.gradleUserHome.isNotBlank()) put("GRADLE_USER_HOME", config.environment.gradleUserHome)
    }

    private fun checkerEnv(
        processor: String,
        quals: List<Fqcn>,
        checkerCategory: CheckerCategory,
        guardrailsMode: GuardrailsMode,
        customQualsJar: File,
        extraClasspath: List<File>,
        checkerHome: File? = null
    ): Map<String, String> {
        val categoryId = when (checkerCategory) {
            CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY -> "custom_qualifier_hierarchy"
            CheckerCategory.BUILTIN_CHECKER -> "builtin_checker"
        }
        return buildMap {
            put("CHECKERFRAMEWORK_HOME", (checkerHome ?: File(config.environment.checkerFrameworkHome)).absolutePath)
            put("CUSTOM_QUAL_JAR_PATH", customQualsJar.absolutePath)
            put("CF_PROCESSOR", processor)
            put("CF_AQUALS", quals.joinToString(",") { it.value })
            put("CF_CHECKER_CATEGORY", categoryId)
            put("CF_SOURCE_COMPATIBILITY", sourceCompatibility)
            put("CF_TARGET_COMPATIBILITY", targetCompatibility)
            put("GUARDRAILS_MODE", guardrailsMode.name.lowercase())
            if (extraClasspath.isNotEmpty()) {
                put("CF_EXTRA_CLASSPATH", extraClasspath.joinToString(File.pathSeparator) { it.absolutePath })
            }
        }
    }

    /** Copies the bundled init script outside the worktree so it is never part of a diff. */
    private fun renderInitScript(worktreeDir: File): File {
        val anchor = worktreeDir.absoluteFile.parentFile ?: worktreeDir.absoluteFile
        return CheckerInitScript.writeTo(anchor.resolve(".pipeline-init"))
    }

    private suspend fun runGradle(
        worktreeDir: File,
        args: List<String>,
        env: Map<String, String>
    ): pipeline.util.ProcResult = concurrency.withPermit { withContext(Dispatchers.IO) {
        val timeoutSeconds = maxOf(60L, config.agent.timeoutMs / 1000L)
        Processes.run(args, worktreeDir, environment = env, timeoutSeconds = timeoutSeconds)
    } }

    private fun findTestXml(worktreeDir: File): List<File> =
        worktreeDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.absolutePath.contains("test-results") }
            .sortedBy { it.absolutePath }
            .toList()

    private fun parseTestXml(file: File): List<TestOutcome> {
        val outcomes = mutableListOf<TestOutcome>()
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(file)
        }.getOrNull() ?: return emptyList()
        val cases = doc.getElementsByTagName("testcase")
        for (i in 0 until cases.length) {
            val node = cases.item(i) as? Element ?: continue
            val suiteName = (node.parentNode as? Element)?.getAttribute("name").orEmpty()
            val className = node.getAttribute("classname").ifEmpty { suiteName }
            val method = node.getAttribute("name")
            if (className.isBlank() || method.isBlank()) continue
            val fqcn = runCatching { Fqcn(className) }.getOrNull() ?: continue
            val timeMs = node.getAttribute("time").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            var status = TestStatus.PASSED
            var failureMessage: String? = null
            val children = node.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                when (child.tagName) {
                    "failure" -> {
                        status = TestStatus.FAILED
                        failureMessage = child.getAttribute("message").ifEmpty { child.textContent.trim() }
                    }
                    "error" -> {
                        status = TestStatus.ERROR
                        failureMessage = child.getAttribute("message").ifEmpty { child.textContent.trim() }
                    }
                    "skipped" -> status = TestStatus.SKIPPED
                }
            }
            outcomes.add(
                TestOutcome(
                    testClass = fqcn,
                    testMethod = method,
                    status = status,
                    durationMs = timeMs,
                    failureMessage = failureMessage?.take(4000)
                )
            )
        }
        return outcomes
    }

    private val checkerLinePattern =
        Regex("""^(.+\.java):(\d+):\s*(error|warning):\s*(?:\[([^\]]+)\]\s*)?(.*)$""")

    private fun parseCheckerDiagnostics(output: String): List<CheckerDiagnostic> {
        val diagnostics = mutableListOf<CheckerDiagnostic>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val match = checkerLinePattern.matchEntire(line) ?: return@forEach
            val (path, row, severity, category, message) = match.destructured
            diagnostics.add(
                CheckerDiagnostic(
                    filePath = path,
                    lineNumber = row.toIntOrNull() ?: 0,
                    columnNumber = null,
                    severity = severity,
                    errorCategory = category,
                    message = message.trim()
                )
            )
        }
        // Gradle echoes failing diagnostics in the task summary and again in the
        // build failure section; count each unique diagnostic once.
        return diagnostics.distinctBy {
            listOf(it.filePath, it.lineNumber, it.severity, it.errorCategory, it.message)
        }
    }
}

private val checkerCrashPattern =
    Regex("""TypeSystemError|BugInCF|The Checker Framework crashed|\bUserError\b|directSuperMap""")

/** True when the Checker Framework itself failed, as opposed to reporting type errors. */
internal fun outputShowsCheckerCrash(output: String): Boolean = checkerCrashPattern.containsMatchIn(output)

/**
 * True when a failed test invocation failed because the sources did not compile.
 *
 * Explicit compile-failure markers always win: a failed compile stops the test
 * task before it can run. Without them, missing JUnit XML usually means the
 * build broke before any test ran, except when Gradle reports that the
 * `--tests` filter matched nothing (for example a coordinate naming an
 * abstract class). That is a no-tests-found outcome, which callers detect from
 * `testsFound == 0`; calling it a compile failure would mislabel the discard.
 */
internal fun isTestCompileFailure(exitCode: Int, noXml: Boolean, output: String): Boolean {
    if (exitCode == 0) return false
    val mentionsCompileFailure = output.contains("compileTestJava FAILED") ||
        output.contains("Compilation failed") ||
        output.contains("cannot find symbol")
    if (mentionsCompileFailure) return true
    if (output.contains("No tests found for given includes")) return false
    return noXml
}
