package pipeline.db

import pipeline.stages.AgentTranscriptEvent
import pipeline.stages.CheckerCategory
import pipeline.stages.CommitClassification
import pipeline.stages.EvalVerdict
import pipeline.stages.GuardrailsMode
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.RunId
import pipeline.ports.TestStatus
import pipeline.stages.VerificationScope

// --- Typed row / projection models. Stages only see these through DatabasePort. ---

data class EvaluationRunRecord(
    val runId: RunId,
    val benchmarkId: String,
    val commitHash: String,
    val repoId: String,
    val repoName: String,
    val gitUrl: String,
    val defaultBranch: String,
    val configYaml: String,
    val parentCommit: String,
    val authorDate: String,
    val commitMsg: String,
    val sampleIndex: Int,
    val sampleTemperature: Double,
    val casCacheKey: String? = null,
    val configSnapshot: String,
    val guardrailsMode: GuardrailsMode,
    val verificationScope: VerificationScope,
    val status: String = "running",
    val startedAt: String,
    val batchId: String? = null
)

data class StepTrajectoryRecord(
    val stepId: String,
    val runId: RunId,
    val stepNumber: Int,
    val attemptNumber: Int,
    val stepName: String,
    val casKey: String? = null,
    val cacheHit: Boolean = false,
    val success: Boolean,
    val artifactsManifest: String? = null,
    val diffPatch: String? = null,
    val compilerDiagnostics: String? = null,
    val agentTranscript: List<AgentTranscriptEvent>? = null,
    val startedAt: String? = null,
    val completedAt: String? = null
)

data class CommitTriageUpdate(
    val commitHash: String,
    val triageStatus: String? = null,
    val discardReason: String? = null,
    val compilerErrorLog: String? = null,
    val classification: CommitClassification? = null,
    val bugfixScore: Double? = null,
    val regressionTestsFound: Int? = null,
    val testMethodsExtracted: Int? = null,
    val regressionTestClass: String? = null,
    val regressionTestMethod: String? = null,
    val testPatch: String? = null,
    val authorDate: String? = null,
    val commitMsg: String? = null
)

data class SynthesisRunRecord(
    val synthesisRunId: String,
    val runId: RunId,
    val stepId: String,
    val attemptNumber: Int,
    val customQualsJarPath: String,
    val placementGuidePath: String,
    val qualifierCount: Int,
    val helpersCount: Int,
    val policyRuleCount: Int,
    val processor: String,
    val checkerCategory: CheckerCategory,
    val transcriptPath: String?,
    val metricsPath: String?,
    val decisionSource: String? = null,
    val decisionRationale: String? = null,
    val fallbackOccurred: Boolean? = null
)

data class QualifierRecord(
    val qualifierId: String,
    val runId: RunId,
    val stepId: String,
    val qualifierName: String,
    val targetType: String,
    val subtypesOf: String?,
    val isTop: Boolean = false,
    val isBottom: Boolean = false
)

data class HelperRecord(
    val helperId: String,
    val runId: RunId,
    val stepId: String,
    val helperName: String,
    val helperType: String,
    val signature: String,
    val isPure: Boolean = true,
    val callSitesCount: Int = 0
)

data class CompilationEventRecord(
    val eventId: String,
    val runId: RunId,
    val stepId: String,
    val checkerRunId: String?,
    val stage: String,
    val turnIndex: Int,
    val exitCode: Int,
    val diagnosticsText: String?,
    val sessionIndex: Int = 1
)

data class AgentTurnRecord(
    val turnId: String,
    val runId: RunId,
    val stepId: String,
    val stage: String,
    val turnIndex: Int,
    val actionType: String,
    val toolName: String?,
    val toolArguments: String?,
    val toolResultSummary: String?,
    val sessionIndex: Int = 1
)

data class AnnotationPlacementRecord(
    val placementId: String,
    val runId: RunId,
    val stepId: String,
    val filePath: String,
    val lineNumber: Int,
    val symbol: String,
    val qualifier: String,
    val targetTier: Int?
)

data class GuardrailEventRecord(
    val eventId: String,
    val runId: RunId,
    val stepId: String,
    val stage: String,
    val turnIndex: Int,
    val guardType: String,
    val violationCategory: GuardrailViolationCategory,
    val actionBlocked: Boolean = true,
    val filePath: String?,
    val diffSnippet: String?,
    val feedbackMessage: String,
    val sessionIndex: Int = 1
)

data class CheckerRunRecord(
    val checkerRunId: String,
    val runId: RunId,
    val stepId: String,
    val stage: String,
    val attemptNumber: Int,
    val processor: String,
    val checkerCategory: CheckerCategory,
    val quals: String,
    val exitCode: Int?,
    val success: Boolean,
    val checkerInvoked: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val diagnosticsLogPath: String?,
    val timedOut: Boolean = false
)

data class CheckerDiagnosticRecord(
    val diagnosticId: String,
    val runId: RunId,
    val stepId: String,
    val checkerRunId: String,
    val stage: String,
    val turnIndex: Int?,
    val severity: String,
    val checkerName: String,
    val errorCategory: String,
    val filePath: String,
    val lineNumber: Int,
    val enclosingMethod: String?,
    val message: String,
    val localizationTier: Int?,
    val isBaselineError: Boolean = false
)

data class TestRunRecord(
    val testRunId: String,
    val runId: RunId,
    val stepId: String,
    val stage: String,
    val suiteKind: String,
    val attemptNumber: Int,
    val scope: VerificationScope,
    val exitCode: Int,
    val compileFailed: Boolean,
    val testsFound: Int,
    val passedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val success: Boolean,
    val xmlPath: String?,
    val timedOut: Boolean = false,
    /** Failures in the raw JUnit report, before any regression-test filtering. */
    val reportedFailedCount: Int = failedCount
)

data class TestExecutionRecord(
    val executionId: String,
    val runId: RunId,
    val stepId: String,
    val testRunId: String,
    val stage: String,
    val turnIndex: Int?,
    val testSuite: String,
    val testClass: String,
    val testMethod: String,
    val isRegressionTest: Boolean,
    val status: TestStatus,
    val durationMs: Long,
    val failureMessage: String?
)

data class LlmInvocationRecord(
    val callId: String,
    val runId: RunId?,
    val stepId: String?,
    val repoId: String?,
    val stage: String,
    val turnIndex: Int?,
    val modelName: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val latencyMs: Long,
    val sessionIndex: Int = 1
)

/** One `preflight_results` row. [runId] is null for standalone `pipeline preflight`. */
data class PreflightResultRecord(
    val preflightId: String,
    val runId: RunId?,
    val benchmarkId: String,
    val commitHash: String,
    val parentCommit: String,
    val report: pipeline.stages.PreflightReport,
    val cacheHit: Boolean,
    val logPath: String?,
)

data class SubprocessMetricRecord(
    val metricId: String, val runId: RunId, val stepId: String, val stage: String,
    val kind: String, val tool: String, val durationMs: Long, val exitCode: Int?,
    val timedOut: Boolean, val success: Boolean, val errorMessage: String?
)

/** How an agent process ended, as reported by the runner and the step budget. */
enum class AgentSessionTermination {
    NORMAL_EXIT,
    TIMEOUT,
    STEP_CAP,
    AGENT_FAILURE,
    STARTUP_FAILURE
}

/** The post-session validation check that rejected a discarded attempt. */
enum class DiscardCheck {
    NO_CHANGES,
    GUARDRAILS,
    COMPILE,
    TESTS,
    CHECKER,
    OTHER
}

/**
 * Durable diagnostic record for one agent session whose output was thrown away.
 *
 * [stageAttemptNumber] is the orchestrator's whole-stage retry counter
 * (`RunContext.attemptNumber`); [internalAttemptNumber] is the stage's own
 * re-prompt loop counter. Together they distinguish, for example, application
 * attempt 2 of stage attempt 1 from attempt 2 of stage attempt 2.
 *
 * The transcript, effective prompt, patch and diagnostics live in artifact
 * files; their locations are recorded here so a diagnosis can be reproduced
 * after process restarts. [feedback] is stored inline because it is the exact
 * text injected into the following attempt's prompt.
 */
data class DiscardedAttemptRecord(
    val attemptId: String,
    val runId: RunId,
    val stepId: String,
    val stage: String,
    val stageAttemptNumber: Int,
    val internalAttemptNumber: Int,
    val startedAt: String,
    val completedAt: String,
    val termination: AgentSessionTermination,
    val discardCheck: DiscardCheck,
    val feedback: String?,
    val agentFailureMessage: String?,
    val exitCode: Int?,
    val stepsCounted: Int,
    val timedOut: Boolean,
    val promptPath: String?,
    val transcriptPath: String?,
    val patchPath: String?,
    val patchOmittedReason: String?,
    val diagnosticsPath: String?
)

data class TelemetryProjection(
    val synthesisRun: SynthesisRunRecord? = null,
    val qualifiers: List<QualifierRecord> = emptyList(),
    val helpers: List<HelperRecord> = emptyList(),
    val compilationEvents: List<CompilationEventRecord> = emptyList(),
    val agentTurns: List<AgentTurnRecord> = emptyList(),
    val annotationPlacements: List<AnnotationPlacementRecord> = emptyList(),
    val guardrailEvents: List<GuardrailEventRecord> = emptyList(),
    val checkerRuns: List<CheckerRunRecord> = emptyList(),
    val checkerDiagnostics: List<CheckerDiagnosticRecord> = emptyList(),
    val testRuns: List<TestRunRecord> = emptyList(),
    val testExecutions: List<TestExecutionRecord> = emptyList(),
    val llmInvocations: List<LlmInvocationRecord> = emptyList()
)
