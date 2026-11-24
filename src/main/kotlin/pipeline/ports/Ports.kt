package pipeline.ports

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pipeline.db.CommitTriageUpdate
import pipeline.db.DiscardedAttemptRecord
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.db.RetryEvent
import pipeline.db.SubprocessMetricRecord
import pipeline.stages.AgentTranscriptEvent
import pipeline.stages.CheckerCategory
import pipeline.stages.CheckerConfig
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
    val latencyMs: Long,
    val transcript: List<AgentTranscriptEvent> = emptyList()
)

/** Why a single-shot classification could not be decoded. */
enum class SingleShotFailure {
    NO_MESSAGE,
    STEP_CAP,
    TIMEOUT,
    NO_JSON
}

/** Classification failure that still carries the model interaction for auditing. */
class SingleShotLlmException(
    message: String,
    cause: Throwable? = null,
    val transcript: List<AgentTranscriptEvent> = emptyList(),
    val reason: SingleShotFailure? = null
) : RuntimeException(message, cause)

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
    /**
     * Compiles the tree. A non-null [checker] injects the checker init script so
     * sources annotated with the synthesized qualifiers resolve them; it must be
     * the same lattice the checker pass uses, because the init script reads the
     * full checker configuration even when only `compileJava` runs.
     */
    suspend fun compile(
        worktreeDir: File,
        checker: CheckerConfig? = null,
        extraClasspath: List<File> = emptyList()
    ): BuildResult

    /** Runs tests; [checker] has the same meaning as in [compile]. */
    suspend fun runTests(
        worktreeDir: File,
        testFilter: TestCoordinate? = null,
        checker: CheckerConfig? = null,
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

/**
 * Arbitrary Gradle invocations for preflight, which runs task lists and probe
 * init scripts that the stage-specific [BuildRunner] calls do not cover.
 * [tasks] are passed to Gradle verbatim (callers qualify project paths); a
 * non-null [checker] adds the harness init script and environment exactly as
 * [BuildRunner.compile] does, and [extraEnv] overrides that environment.
 * [writableDirs] are directories outside the worktree the build writes to (a
 * probe report); a build container mounts them writable, and everything else
 * outside the worktree read-only or not at all.
 */
interface GradleTaskRunner {
    suspend fun runTasks(
        worktreeDir: File,
        tasks: List<String>,
        checker: CheckerConfig? = null,
        extraInitScripts: List<File> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        writableDirs: List<File> = emptyList()
    ): BuildResult
}

// Port 3b: Execution backend (Dockerization plan, section 3.1)

/** A host path made visible to a container at the same path. */
data class Mount(val path: File, val readOnly: Boolean = true)

/**
 * One subprocess invocation. For the local backend, [environment] is laid over
 * the pipeline's own environment as before; for a container backend it is the
 * container's whole environment, so callers pass an explicit allowlist.
 * [mounts] are ignored by the local backend, which sees the whole host.
 */
data class ExecSpec(
    val command: List<String>,
    val cwd: File,
    val environment: Map<String, String> = emptyMap(),
    val mounts: List<Mount> = emptyList(),
    /** Short label for container names and labels ("build", later "agent"). */
    val kind: String = "build",
)

/**
 * A running invocation. A plain [Process] is not enough for containers: there
 * the process is the `docker` client, killing it leaves the container running,
 * and it can exit while the container still runs. [kill] stops the whole
 * invocation, container included.
 */
interface ExecutionHandle {
    val stdout: java.io.InputStream
    val stderr: java.io.InputStream
    val stdin: java.io.OutputStream
    fun isAlive(): Boolean
    fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean
    fun exitValue(): Int
    fun kill()
}

interface ExecutionBackend {
    /** "local" or "docker"; recorded with results. */
    val name: String

    /** The runtime image's ID, or null for the local backend. */
    val imageIdentity: String?

    fun start(spec: ExecSpec): ExecutionHandle

    /** Runs to completion, killing the invocation after [timeoutSeconds]. */
    fun run(spec: ExecSpec, timeoutSeconds: Long, stdin: String? = null): pipeline.util.ProcResult =
        pipeline.util.Processes.collect(start(spec), timeoutSeconds, stdin)
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
    suspend fun recordRetry(event: RetryEvent)
    suspend fun recordSubprocess(metric: SubprocessMetricRecord)

    /** Persist a discarded agent attempt and the paths of its durable artifacts. */
    suspend fun recordDiscardedAttempt(record: DiscardedAttemptRecord)

    /**
     * Query discarded attempts by run, optionally narrowed by stage and either
     * attempt counter. Results are ordered oldest internal attempt first.
     */
    suspend fun readDiscardedAttempts(
        runId: RunId,
        stage: String? = null,
        stageAttemptNumber: Int? = null,
        internalAttemptNumber: Int? = null
    ): List<DiscardedAttemptRecord>

    /** Records one preflight outcome; a no-op for stores that do not keep them. */
    suspend fun recordPreflight(result: pipeline.db.PreflightResultRecord) {}
}
