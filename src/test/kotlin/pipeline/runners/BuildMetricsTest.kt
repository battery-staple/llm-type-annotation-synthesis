package pipeline.runners

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.*
import pipeline.ports.*
import pipeline.stages.*

class BuildMetricsTest {
    private class Db : DatabasePort {
        val records = mutableListOf<SubprocessMetricRecord>()
        override suspend fun recordSubprocess(metric: SubprocessMetricRecord) { records += metric }
        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) = Unit
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: CommitTriageUpdate) = Unit
        override suspend fun recordRetry(event: RetryEvent) = Unit
    }

    private class Runner : BuildRunner {
        var compileResult = BuildResult(true, 0, "")
        var testResult = TestRunResult(true, 0, emptyList(), emptyList(), false, 0, "")
        var checkerResult = CheckerResult(true, 0, true, 0, 0, emptyList(), File("checker.log"))
        var throwOnCompile = false
        override suspend fun compile(worktreeDir: File, customQualsJar: File?, extraClasspath: List<File>): BuildResult {
            if (throwOnCompile) throw IllegalStateException("runner broke")
            return compileResult
        }
        override suspend fun runTests(worktreeDir: File, testFilter: TestCoordinate?, customQualsJar: File?, extraClasspath: List<File>) = testResult
        override suspend fun runChecker(worktreeDir: File, checkerHome: File, customQualsJar: File, processor: String,
                                        quals: List<Fqcn>, checkerCategory: CheckerCategory, guardrailsMode: GuardrailsMode) = checkerResult
    }

    private fun context(db: Db) = RunContext(
        config = ProjectConfig(), build = BuildSection(), sourceCompatibility = "17", targetCompatibility = "17",
        runId = RunId("run"), sampleIndex = 0, sampleTemperature = 0.0, repoDir = File("."), casDir = File("."),
        worktreeDir = File("."), agentWorkspaceDir = File("."), database = db, stepId = "step", stageName = StageName.APPLICATION
    )

    @Test fun `records successful compile test and checker with result metadata`() = runBlocking {
        val db = Db(); val runner = Runner(); val metrics = BuildMetrics(context(db), runner)
        metrics.compile("compile", File("."))
        metrics.tests("tests", File("."))
        metrics.checker("checker", File("."), File("."), File("quals.jar"), "processor", emptyList(),
            CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY, GuardrailsMode.LENIENT)
        assertEquals(listOf("compile", "test", "checker"), db.records.map { it.kind })
        db.records.forEach {
            assertEquals("application", it.stage); assertEquals("run", it.runId.value); assertEquals("step", it.stepId)
            assertEquals("gradle", it.tool); assertEquals(0, it.exitCode); assertFalse(it.timedOut)
            assertTrue(it.success); assertTrue(it.durationMs >= 0)
        }
    }

    @Test fun `records thrown exception and rethrows`() = runBlocking {
        val db = Db(); val runner = Runner().apply { throwOnCompile = true }
        val thrown = assertFailsWith<IllegalStateException> { BuildMetrics(context(db), runner).compile("compile", File(".")) }
        assertEquals("runner broke", thrown.message)
        assertEquals(1, db.records.size)
        assertFalse(db.records.single().success)
        assertEquals(null, db.records.single().exitCode)
        assertTrue(db.records.single().errorMessage!!.contains("runner broke"))
    }

    @Test fun `records timed out result`() = runBlocking {
        val db = Db(); val runner = Runner().apply { compileResult = BuildResult(false, -1, "timed out", true) }
        BuildMetrics(context(db), runner).compile("compile", File("."))
        assertTrue(db.records.single().timedOut)
        assertFalse(db.records.single().success)
    }
}
