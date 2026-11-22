package pipeline.ports

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.stages.AgentTranscriptEvent
import pipeline.stages.CheckerCategory
import pipeline.stages.CommitClassification
import pipeline.stages.EvalVerdict
import pipeline.stages.Fqcn
import pipeline.stages.GuardrailsMode
import pipeline.stages.RunId
import pipeline.stages.TestCoordinate
import pipeline.stages.TokenUsage

// Port 1: Single-Shot LLM Client (Triage Classification and Extraction)
@Serializable
data class TriageClassification(
    val isBugfix: Boolean,
    val bugfixScore: Double,
    val category: CommitClassification,
    val rationale: String,
    val regressionTest: TestCoordinate?
)

data class SingleShotLlmResponse<T>(
    val value: T,
    val usage: TokenUsage,
    val latencyMs: Long
)

interface SingleShotLlmClient {
    suspend fun classifyCommit(
        prompt: String,
        model: String,
        temperature: Double = 0.0
    ): SingleShotLlmResponse<TriageClassification>
}

// Port 2: Multi-Turn Agent Runner
data class AgentRunResult(
    val exitCode: Int,
    val stepsCounted: Int,
    val transcript: List<AgentTranscriptEvent>,
    val timedOut: Boolean
)

interface AgentRunner {
    /**
     * The optional [onEvent] callback is invoked per parsed turn/event so a caller can
     * audit and roll back an edit before the next turn. If it is not supplied, the
     * caller must perform the same audit post-hoc on the terminal transcript and
     * roll back rejected edits before accepting the stage output.
     */
    suspend fun runAgent(
        prompt: String,
        cwd: File,
        agentRole: String,
        model: String,
        maxSteps: Int,
        timeoutSeconds: Long,
        seed: Long,
        temperature: Double,
        environment: Map<String, String> = emptyMap(),
        onEvent: (suspend (AgentTranscriptEvent) -> Unit)? = null
    ): AgentRunResult
}

// Port 3: Build & Test Tooling (Gradle / Future Maven)
data class BuildResult(val success: Boolean, val exitCode: Int, val output: String, val timedOut: Boolean = false)

@Serializable
enum class TestStatus {
    @SerialName("passed") PASSED,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED,
    @SerialName("error") ERROR
}

@Serializable
data class TestOutcome(
    val testClass: Fqcn,
    val testMethod: String,
    val status: TestStatus,
    val durationMs: Long,
    val failureMessage: String? = null
)

data class TestRunResult(
    val success: Boolean,
    val exitCode: Int,
    val outcomes: List<TestOutcome>,
    val xmlPaths: List<File>,
    val compileFailed: Boolean,
    val testsFound: Int,
    val output: String,
    val timedOut: Boolean = false
)

@Serializable
data class CheckerDiagnostic(
    val filePath: String,
    val lineNumber: Int,
    val columnNumber: Int? = null,
    val severity: String,
    val errorCategory: String,
    val message: String
)

data class CheckerResult(
    val success: Boolean, // invocation completed and diagnostics were parsed; NOT "zero errors"
    val exitCode: Int,
    val checkerInvoked: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val diagnostics: List<CheckerDiagnostic>,
    val diagnosticsLog: File,
    val timedOut: Boolean = false
)

interface BuildRunner {
    suspend fun compile(
        worktreeDir: File,
        customQualsJar: File? = null,
        extraClasspath: List<File> = emptyList()
    ): BuildResult

    suspend fun runTests(
        worktreeDir: File,
        testFilter: TestCoordinate? = null,
        customQualsJar: File? = null,
        extraClasspath: List<File> = emptyList()
    ): TestRunResult

    suspend fun runChecker(
        worktreeDir: File,
        checkerHome: File,
        customQualsJar: File,
        processor: String,
        quals: List<Fqcn>,
        checkerCategory: CheckerCategory,
        guardrailsMode: GuardrailsMode
    ): CheckerResult
}

// Port 4: In-Process Compiler & Packaging
data class JavacDiagnostic(
    val kind: String,
    val lineNumber: Long?,
    val columnNumber: Long?,
    val message: String
)

data class JavacResult(
    val success: Boolean,
    val exitCode: Int,
    val diagnostics: List<JavacDiagnostic>
)

interface JavacCompiler {
    fun compileAndPackageJar(
        sourceFiles: List<File>,
        outputJar: File,
        classpath: List<File> = emptyList()
    ): JavacResult
}

// Port 5: Persistence (injected into RunContext and used by every stage)
interface DatabasePort {
    suspend fun recordRunStart(run: EvaluationRunRecord)
    suspend fun recordStep(step: StepTrajectoryRecord)
    suspend fun recordStageTelemetry(projection: TelemetryProjection)
    suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict)
    suspend fun finishRun(runId: RunId, status: String)
    suspend fun readVerdict(runId: RunId): EvalVerdict?
    suspend fun updateCommit(update: CommitTriageUpdate)
}
