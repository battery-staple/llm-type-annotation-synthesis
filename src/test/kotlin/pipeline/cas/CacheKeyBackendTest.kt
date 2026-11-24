package pipeline.cas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import pipeline.config.BuildSection
import pipeline.config.ExecutionBackendKind
import pipeline.config.ExecutionSection
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
 * Docker-mode results must never be served to a local run or the other way
 * around (Dockerization plan, section 3.9), while every existing local bundle
 * keeps its key: the backend fields exist only in Docker mode.
 */
class CacheKeyBackendTest {

    private fun ctx(execution: ExecutionSection, build: BuildSection = BuildSection()) = RunContext(
        config = ProjectConfig(execution = execution),
        build = build,
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("cache-key-backend"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = File("."),
        casDir = File("."),
        worktreeDir = File("."),
        agentWorkspaceDir = File("."),
        database = NoopDatabase,
    )

    private fun key(ctx: RunContext, stage: StageName = StageName.VERIFICATION) =
        CacheKeys.compute(ctx, stage, mapOf("input" to "same"), emptyList(), mapOf("config" to "same"), null)

    private val docker = ExecutionSection(backend = ExecutionBackendKind.DOCKER, imageId = "sha256:one")

    @Test
    fun `local keys ignore every execution setting`() {
        val plain = key(ctx(ExecutionSection()))
        assertEquals(plain, key(ctx(ExecutionSection(image = "other:1", imageId = "sha256:x", buildMemory = "2g", cpus = 1.0))))
        assertEquals(plain, key(ctx(ExecutionSection(), BuildSection(runtimeJdk = "25"))), "runtime_jdk is Docker-only")
    }

    @Test
    fun `docker keys differ from local ones and follow the image and runtime JDK`() {
        StageName.entries.forEach { stage ->
            assertNotEquals(key(ctx(ExecutionSection()), stage), key(ctx(docker), stage), "$stage")
        }
        assertNotEquals(key(ctx(docker)), key(ctx(docker.copy(imageId = "sha256:two"))))
        assertNotEquals(key(ctx(docker)), key(ctx(docker, BuildSection(runtimeJdk = "25"))))
        assertEquals(key(ctx(docker)), key(ctx(docker.copy(buildMemory = "2g", cpus = 1.0))), "resource limits do not change results")
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
            runId: pipeline.stages.RunId,
            stage: String?,
            stageAttemptNumber: Int?,
            internalAttemptNumber: Int?
        ): List<pipeline.db.DiscardedAttemptRecord> = emptyList()
    }
}
