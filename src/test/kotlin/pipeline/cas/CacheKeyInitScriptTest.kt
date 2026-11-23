package pipeline.cas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.DatabasePort
import pipeline.stages.EvalVerdict
import pipeline.stages.RunContext
import pipeline.stages.RunId
import pipeline.stages.StageName

/**
 * `checker.init.gradle` is a resource, so the stage-code fingerprint does not
 * see it. A change to it must invalidate exactly the stages whose Gradle
 * invocations pass it, and must not throw away the LLM-backed synthesis
 * bundles or the init-script-free ingestion and triage bundles.
 */
class CacheKeyInitScriptTest {

    private val ctx = RunContext(
        config = ProjectConfig(),
        build = BuildSection(),
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("cache-key-init-script"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = File("."),
        casDir = File("."),
        worktreeDir = File("."),
        agentWorkspaceDir = File("."),
        database = NoopDatabase,
    )

    private fun key(stage: StageName, initScriptSha: String) = CacheKeys.compute(
        ctx = ctx,
        stage = stage,
        stageInput = mapOf("input" to "same"),
        upstreamKeys = emptyList(),
        configSlice = mapOf("config" to "same"),
        promptTemplate = null,
        initScriptSha = initScriptSha,
    )

    @Test
    fun `an init script change invalidates exactly the stages that build with it`() {
        val invalidated = StageName.entries.filter { key(it, "old-template") != key(it, "new-template") }.toSet()
        assertEquals(
            setOf(StageName.PREFLIGHT, StageName.APPLICATION, StageName.REPAIR, StageName.VERIFICATION),
            invalidated,
        )
    }

    @Test
    fun `the default is the bundled template's hash`() {
        val bundled = pipeline.util.Hashing.sha256(pipeline.runners.CheckerInitScript.template())
        val default = CacheKeys.compute(ctx, StageName.APPLICATION, mapOf("input" to "same"), emptyList(), mapOf("config" to "same"), null)
        assertEquals(key(StageName.APPLICATION, bundled), default)
        assertNotEquals(key(StageName.APPLICATION, "other"), default)
    }

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
}
