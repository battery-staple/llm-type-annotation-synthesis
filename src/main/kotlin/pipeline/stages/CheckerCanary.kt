package pipeline.stages

import java.io.File
import java.nio.file.Files
import pipeline.runners.outputShowsCheckerCrash
import pipeline.util.Env
import pipeline.util.Processes

/**
 * Outcome of the harness-side Checker canary.
 *
 * [invoked] distinguishes "the Checker compiler ran" from "we could not even
 * start it"; [harnessFailure] further narrows the latter to a broken harness or
 * installation (as opposed to a rejected qualifier hierarchy). Only a clean,
 * invoked run passes.
 */
data class CheckerCanaryResult(
    val invoked: Boolean,
    val exitCode: Int,
    val output: String,
    val problem: String?,
    val harnessFailure: Boolean = false,
) {
    val passed: Boolean get() = problem == null && !harnessFailure
}

/**
 * Compiles one tiny annotated source with the vendored Checker Framework.
 *
 * The purpose is not to type-check the benchmark: it is to force the selected
 * processor to build its qualifier hierarchy before the synthesized JAR is
 * accepted. A malformed hierarchy (most notably a root with no `@SubtypeOf({})`
 * edge) crashes Checker before it emits any source diagnostics, which would
 * otherwise look like a clean run with zero errors.
 *
 * The Checker Framework is launched directly with `java -jar checker.jar`, which
 * is exactly what its `checker/bin/javac` shell wrapper ends up doing. The
 * wrapper is deliberately bypassed: it re-parses its command with `eval`, so an
 * installation path containing a space (e.g. "CS 6158") splits the -jar path and
 * Java fails before Checker ever starts. ProcessBuilder receives an argument
 * list here, so paths with spaces are passed through intact.
 */
object CheckerCanary {
    const val DEFAULT_TIMEOUT_SECONDS: Long = 120L
    private const val CANARY_CLASS_NAME = "SynthesisCheckerCanary"

    // Java launcher errors: the JVM never reached CheckerMain. Checker crash
    // markers take precedence, so a genuine hierarchy failure stays classified
    // as a rejection rather than a harness problem.
    private val launcherFailurePattern = Regex(
        """Unable to access jarfile|Could not find or load main class|Invalid or corrupt jarfile|no main manifest attribute|Could not create the Java Virtual Machine|Unrecognized option:|A JNI error has occurred|ClassNotFoundException""",
        RegexOption.IGNORE_CASE,
    )

    fun run(
        checkerHome: File,
        customQualsJar: File,
        checkerQualJar: File,
        processor: String,
        qualifiers: List<Fqcn>,
        javaHome: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): CheckerCanaryResult {
        if (qualifiers.isEmpty()) {
            return CheckerCanaryResult(
                invoked = false,
                exitCode = -1,
                output = "",
                problem = "checker canary needs at least one discovered qualifier for -Aquals",
            )
        }
        val checkerJar = File(checkerHome, "checker/dist/checker.jar")
        if (!checkerJar.isFile) {
            return CheckerCanaryResult(
                invoked = false,
                exitCode = -1,
                output = "",
                problem = "checker canary could not start: checker JAR ${checkerJar.absolutePath} is missing",
                harnessFailure = true,
            )
        }
        val java = javaExecutable(javaHome)
        if (java == null) {
            return CheckerCanaryResult(
                invoked = false,
                exitCode = -1,
                output = "",
                problem = "checker canary could not start: Java executable is missing or not executable (javaHome=${javaHome ?: "<unset>"})",
                harnessFailure = true,
            )
        }

        val workDir = Files.createTempDirectory("synthesis-checker-canary-").toFile()
        return try {
            val source = workDir.resolve("$CANARY_CLASS_NAME.java")
            // Fully qualified annotation: no imports to get wrong, and the
            // annotation is enough to make Checker initialize its hierarchy.
            source.writeText(
                buildString {
                    append("// Harness-side canary; never published as part of the bundle.\n")
                    append("final class $CANARY_CLASS_NAME {\n")
                    append("    @${qualifiers.first().value} Object sample;\n")
                    append("}\n")
                },
            )
            val classesDir = workDir.resolve("classes").also { it.mkdirs() }
            val classpath = listOf(customQualsJar.absolutePath, checkerQualJar.absolutePath)
                .joinToString(File.pathSeparator)
            val command = listOf(
                java.absolutePath,
                "-jar", checkerJar.absolutePath,
                "-cp", classpath,
                "-processor", processor,
                "-Aquals=" + qualifiers.joinToString(",") { it.value },
                "-d", classesDir.absolutePath,
                source.absolutePath,
            )
            val result = try {
                Processes.run(command, workDir, timeoutSeconds = timeoutSeconds)
            } catch (t: Throwable) {
                return CheckerCanaryResult(
                    invoked = false,
                    exitCode = -1,
                    output = "",
                    problem = "checker canary could not start: invocation threw: ${t.message}",
                    harnessFailure = true,
                )
            }
            val output = result.output
            val crashed = outputShowsCheckerCrash(output)
            val harnessProblem = !crashed && launcherFailurePattern.containsMatchIn(output)
            val problem = when {
                result.timedOut -> "checker canary timed out after ${timeoutSeconds}s"
                crashed -> "checker canary crashed: ${output.take(2000)}"
                harnessProblem -> "checker canary could not start: ${output.take(2000)}"
                result.exitCode != 0 -> "checker canary exited with code ${result.exitCode}: ${output.take(2000)}"
                else -> null
            }
            CheckerCanaryResult(
                invoked = !harnessProblem,
                exitCode = result.exitCode,
                output = output,
                problem = problem,
                harnessFailure = harnessProblem,
            )
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun javaExecutable(javaHome: String?): File? {
        val home = javaHome?.takeIf { it.isNotBlank() }?.let(::File)
            ?: Env.get("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: System.getProperty("java.home")?.let(::File)
            ?: return null
        val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "bin/java.exe"
        } else {
            "bin/java"
        }
        return File(home, name).takeIf { it.isFile && it.canExecute() }
    }
}
