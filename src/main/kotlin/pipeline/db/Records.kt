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
    val metricsPath: String?
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
    val diagnosticsText: String?
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
    val toolResultSummary: String?
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
    val feedbackMessage: String
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
    val latencyMs: Long
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
