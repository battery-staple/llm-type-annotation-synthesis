package pipeline.stages

import java.io.File
import java.nio.file.Files
import pipeline.runners.outputShowsCheckerCrash
import pipeline.util.Processes

/**
 * Outcome of the harness-side Checker canary.
 *
 * [invoked] distinguishes "the Checker compiler ran" from "we could not even
 * start it"; both are recorded, but only a clean, invoked run passes.
 */
data class CheckerCanaryResult(
    val invoked: Boolean,
    val exitCode: Int,
    val output: String,
    val problem: String?,
) {
    val passed: Boolean get() = problem == null
}

/**
 * Compiles one tiny annotated source with the vendored Checker Framework.
 *
 * The purpose is not to type-check the benchmark: it is to force the selected
 * processor to build its qualifier hierarchy before the synthesized JAR is
 * accepted. A malformed hierarchy (most notably a root with no `@SubtypeOf({})`
 * edge) crashes Checker before it emits any source diagnostics, which would
 * otherwise look like a clean run with zero errors.
 */
object CheckerCanary {
    const val DEFAULT_TIMEOUT_SECONDS: Long = 120L
    private const val CANARY_CLASS_NAME = "SynthesisCheckerCanary"

    fun run(
        checkerHome: File,
        customQualsJar: File,
        checkerQualJar: File,
        processor: String,
        qualifiers: List<Fqcn>,
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
        val checkerJavac = File(checkerHome, "checker/bin/javac")
        if (!checkerJavac.isFile || !checkerJavac.canExecute()) {
            return CheckerCanaryResult(
                invoked = false,
                exitCode = -1,
                output = "",
                problem = "checker canary could not start: ${checkerJavac.absolutePath} is missing or not executable",
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
                checkerJavac.absolutePath,
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
                    problem = "checker canary invocation threw: ${t.message}",
                )
            }
            val output = result.output
            val problem = when {
                result.timedOut -> "checker canary timed out after ${timeoutSeconds}s"
                outputShowsCheckerCrash(output) -> "checker canary crashed: ${output.take(2000)}"
                result.exitCode != 0 -> "checker canary exited with code ${result.exitCode}: ${output.take(2000)}"
                else -> null
            }
            CheckerCanaryResult(invoked = true, exitCode = result.exitCode, output = output, problem = problem)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }
}
