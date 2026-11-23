package pipeline.runners

import java.io.File
import java.util.UUID
import pipeline.ports.BuildResult
import pipeline.ports.BuildRunner
import pipeline.ports.CheckerResult
import pipeline.ports.TestRunResult
import pipeline.stages.RunContext
import pipeline.stages.TestCoordinate
import pipeline.stages.Fqcn
import pipeline.stages.CheckerCategory
import pipeline.stages.CheckerConfig
import pipeline.stages.GuardrailsMode
import pipeline.db.SubprocessMetricRecord

/** Times Gradle calls and persists their outcome without affecting stage behavior. */
class BuildMetrics(private val ctx: RunContext, private val runner: BuildRunner) {
    suspend fun compile(label: String, worktreeDir: File, checker: CheckerConfig? = null,
                        extraClasspath: List<File> = emptyList()): BuildResult = measured("compile", label,
        { runner.compile(worktreeDir, checker, extraClasspath) },
        { Triple(it.success, it.exitCode, it.timedOut) to if (it.success) null else it.output })

    suspend fun tests(label: String, worktreeDir: File, testFilter: TestCoordinate? = null,
                      checker: CheckerConfig? = null, extraClasspath: List<File> = emptyList()): TestRunResult = measured("test", label,
        { runner.runTests(worktreeDir, testFilter, checker, extraClasspath) },
        { Triple(it.success, it.exitCode, it.timedOut) to if (it.success) null else it.output })

    suspend fun checker(label: String, worktreeDir: File, checkerHome: File, customQualsJar: File,
                        processor: String, quals: List<Fqcn>, checkerCategory: CheckerCategory,
                        guardrailsMode: GuardrailsMode): CheckerResult = measured("checker", label,
        { runner.runChecker(worktreeDir, checkerHome, customQualsJar, processor, quals, checkerCategory, guardrailsMode) },
        { Triple(it.success, it.exitCode, it.timedOut) to if (it.success) null else "checker invocation failed" })

    private suspend fun <T> measured(kind: String, label: String, call: suspend () -> T,
                                     describe: (T) -> Pair<Triple<Boolean, Int, Boolean>, String?>): T {
        val start = System.nanoTime()
        try {
            val result = call()
            val duration = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
            val (state, error) = describe(result)
            record(kind, label, duration, state.second, state.third, state.first, error)
            return result
        } catch (t: Throwable) {
            val duration = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
            record(kind, label, duration, null, false, false, (t.message ?: t::class.java.simpleName).take(ERROR_LIMIT))
            throw t
        }
    }

    private suspend fun record(kind: String, label: String, duration: Long, exitCode: Int?, timedOut: Boolean,
                               success: Boolean, error: String?) {
        runCatching {
            ctx.database.recordSubprocess(SubprocessMetricRecord(
                metricId = UUID.randomUUID().toString(), runId = ctx.runId, stepId = ctx.stepId,
                stage = ctx.stageName?.identifier.orEmpty(), kind = kind, tool = "gradle",
                durationMs = duration, exitCode = exitCode, timedOut = timedOut, success = success,
                errorMessage = error?.let { "$label: $it" }?.take(ERROR_LIMIT)
            ))
        }
    }

    companion object { private const val ERROR_LIMIT = 1000 }
}
