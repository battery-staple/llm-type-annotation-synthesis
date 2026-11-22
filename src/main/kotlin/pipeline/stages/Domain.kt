package pipeline.stages

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.TelemetryProjection
import pipeline.ports.DatabasePort
import pipeline.util.CommitMetadata
import pipeline.validators.SourceRoots

// --- Strongly-Typed Domain Primitives & Value Classes ---

@JvmInline
@Serializable
value class Sha256(val hex: String) {
    init {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid SHA-256 digest: $hex"
        }
    }

    override fun toString(): String = hex.lowercase()
}

@JvmInline
@Serializable
value class CasKey(val hash: Sha256) {
    companion object {
        fun fromHex(hex: String): CasKey = CasKey(Sha256(hex))
    }
    override fun toString(): String = hash.toString()
}

@JvmInline
@Serializable
value class CommitHash(val hash: String) {
    init {
        require(hash.length in listOf(40, 64) && hash.all { it.isLetterOrDigit() }) {
            "Invalid Git commit hash: $hash"
        }
    }

    val abbreviated: String get() = hash.take(8)
    override fun toString(): String = hash
}

@JvmInline
@Serializable
value class RunId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class BenchmarkId(
    val repoName: String,
    val shortCommit: String,
    val alias: String? = null
) {
    val canonical: String get() = alias ?: "$repoName-$shortCommit"
    override fun toString(): String = canonical

    companion object {
        fun of(repoName: String, commit: CommitHash, alias: String? = null): BenchmarkId =
            BenchmarkId(repoName, commit.abbreviated, alias)

        fun parse(raw: String): BenchmarkId {
            val parts = raw.split("-")
            if (parts.size < 2) return BenchmarkId(raw, "head", alias = raw)
            return BenchmarkId(parts.dropLast(1).joinToString("-"), parts.last(), alias = raw)
        }
    }
}

@JvmInline
@Serializable
value class Fqcn(val value: String) {
    init {
        require(value.isNotBlank() && !value.endsWith('.')) { "Invalid fully qualified class name: $value" }
    }

    val simpleName: String get() = value.substringAfterLast('.')
    val packageName: String get() = value.substringBeforeLast('.', "")
    override fun toString(): String = value
}

@Serializable
data class TestCoordinate(
    val testClass: Fqcn,
    val testMethod: String
) {
    val gradleFilter: String get() = "${testClass.value}.$testMethod"
    override fun toString(): String = gradleFilter
}

// --- Closed Domain Enums ---

@Serializable
enum class StageName {
    @SerialName("ingestion") INGESTION,
    @SerialName("triage") TRIAGE,
    @SerialName("synthesis") SYNTHESIS,
    @SerialName("application") APPLICATION,
    @SerialName("repair") REPAIR,
    @SerialName("verification") VERIFICATION;

    val identifier: String get() = name.lowercase()
}

@Serializable
enum class VerificationScope {
    @SerialName("full_suite") FULL_SUITE,
    @SerialName("regression_only") REGRESSION_ONLY
}

@Serializable
enum class GuardrailsMode {
    @SerialName("disabled") DISABLED,
    @SerialName("lenient") LENIENT,
    @SerialName("strict") STRICT
}

@Serializable
enum class CommitClassification {
    @SerialName("bugfix") BUGFIX,
    @SerialName("non_bugfix") NON_BUGFIX
}

@Serializable
enum class AgentEventType {
    @SerialName("initial_prompt") INITIAL_PROMPT,
    @SerialName("step_start") STEP_START,
    @SerialName("tool_call") TOOL_CALL,
    @SerialName("tool_result") TOOL_RESULT,
    @SerialName("message") MESSAGE,
    @SerialName("reasoning") REASONING,
    @SerialName("step_finish") STEP_FINISH
}

@Serializable
enum class TriageMode {
    @SerialName("manifest") MANIFEST,
    @SerialName("crawler") CRAWLER
}

@Serializable
enum class DiscardReason(val wireName: String, val dbTriageStatus: String?) {
    @SerialName("no_test_found") NO_TEST_FOUND("no_test_found", "discarded_no_test"),
    @SerialName("test_compilation_failure") TEST_COMPILATION_FAILURE("test_compilation_failure", "discarded_non_compiling"),
    @SerialName("test_passes_unpatched") TEST_PASSES_UNPATCHED("test_passes_unpatched", "discarded_passes_unpatched"),
    @SerialName("build_broken_unpatched") BUILD_BROKEN_UNPATCHED("build_broken_unpatched", null),
    @SerialName("invalid_test_patch") INVALID_TEST_PATCH("invalid_test_patch", "discarded_invalid_test_patch"),
    @SerialName("regression_does_not_pass_on_fixed_commit") REGRESSION_DOES_NOT_PASS_ON_FIXED_COMMIT("regression_does_not_pass_on_fixed_commit", "discarded_regression_not_passing_on_fixed_commit"),
    @SerialName("fixed_commit_unavailable") FIXED_COMMIT_UNAVAILABLE("fixed_commit_unavailable", "discarded_fixed_commit_unavailable"),
    @SerialName("not_bugfix") NOT_BUGFIX("not_bugfix", "non_bugfix"),
    @SerialName("diff_too_large") DIFF_TOO_LARGE("diff_too_large", "discarded_diff_too_large"),
    @SerialName("merge_commit") MERGE_COMMIT("merge_commit", "discarded_merge_commit")
}

@Serializable
enum class LocalizationTier(val tierNumber: Int) {
    DIFF_HUNK(1),
    ENCLOSING_METHOD(2),
    ENCLOSING_CLASS(3),
    EXTERNAL_NOISE(4)
}

@Serializable
enum class RuleCategory {
    @SerialName("default") DEFAULT,
    @SerialName("source") SOURCE,
    @SerialName("sink") SINK,
    @SerialName("sanitizer") SANITIZER,
    @SerialName("semantics") SEMANTICS
}

@Serializable
enum class FreedomLevel {
    @SerialName("level0") LEVEL_0,
    @SerialName("level1") LEVEL_1,
    @SerialName("level2") LEVEL_2
}

@Serializable
enum class AllowedCheckers {
    @SerialName("all") ALL,
    @SerialName("custom_only") CUSTOM_ONLY,
    @SerialName("builtin_only") BUILTIN_ONLY
}

@Serializable
enum class LocalAnnotationPolicy {
    @SerialName("frozen") FROZEN,
    @SerialName("allowed") ALLOWED
}

@Serializable
enum class GuardrailViolationCategory {
    @SerialName("executable_edit") EXECUTABLE_EDIT,
    @SerialName("suppression") SUPPRESSION,
    @SerialName("signature_tampering") SIGNATURE_TAMPERING,
    @SerialName("cast") CAST,
    @SerialName("backdoor") BACKDOOR,
    @SerialName("filesystem") FILESYSTEM,
    @SerialName("test_tampering") TEST_TAMPERING,
    @SerialName("build_tampering") BUILD_TAMPERING,
    @SerialName("path_escape") PATH_ESCAPE,
    @SerialName("file_addition") FILE_ADDITION,
    @SerialName("package_info_change") PACKAGE_INFO_CHANGE,
    @SerialName("local_annotation") LOCAL_ANNOTATION
}

// --- Strongly-Typed Artifact & Manifest Models ---

@Serializable
data class ArtifactFile(
    val path: String,
    val sha256: Sha256,
    @SerialName("size_bytes") val sizeBytes: Long
)

@Serializable
data class ArtifactsManifest(
    @SerialName("stage_name") val stageName: StageName,
    @SerialName("cache_key") val cacheKey: CasKey,
    @SerialName("upstream_keys") val upstreamKeys: List<CasKey> = emptyList(),
    @SerialName("sample_index") val sampleIndex: Int = 0,
    @SerialName("sample_temperature") val sampleTemperature: Double = 0.0,
    @SerialName("created_at") val createdAt: String,
    val success: Boolean = true,
    val artifacts: List<ArtifactFile> = emptyList()
)

// --- Strongly-Typed Agent Transcript & Event Models ---

@Serializable
data class TokenUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
data class AgentTranscriptEvent(
    val timestamp: String,
    @SerialName("event_type") val eventType: AgentEventType,
    @SerialName("turn_index") val turnIndex: Int,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_arguments") val toolArguments: Map<String, JsonElement>? = null,
    @SerialName("tool_result") val toolResult: String? = null,
    val content: String? = null,
    @SerialName("token_usage") val tokenUsage: TokenUsage? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)

data class RunContext(
    val config: ProjectConfig,
    val build: BuildSection,
    val sourceCompatibility: String,
    val targetCompatibility: String,
    val runId: RunId,
    val sampleIndex: Int,
    val sampleTemperature: Double,
    val repoDir: File,
    val casDir: File,
    val worktreeDir: File,
    val agentWorkspaceDir: File,
    val database: DatabasePort,
    // Benchmark-specific source roots (benchmarks.yaml production_roots /
    // test_roots). Defaults preserve the legacy src/main + src/test layout.
    val productionRoots: List<String> = SourceRoots.DEFAULT_PRODUCTION_ROOTS,
    val testRoots: List<String> = SourceRoots.DEFAULT_TEST_ROOTS,
    // Set per stage attempt by the orchestrator. Orchestrator-only data.
    val stepId: String = "",
    val stepNumber: Int = 0,
    val attemptNumber: Int = 1,
    val stageName: StageName? = null
)

// Stage implementations pass only agentWorkspaceDir (or a dedicated subdirectory)
// to AgentRunner. repoDir, casDir, worktreeDir, and database are orchestrator-only
// and must never be exposed to an agent process, prompt, or transcript.

sealed class StageResult<out T> {
    data class Success<T>(
        val data: T,
        val casKey: CasKey,
        val artifactsManifest: ArtifactsManifest,
        val bundleDir: File? = null
    ) : StageResult<T>()

    data class Discard(
        val reason: DiscardReason,
        val details: String,
        val artifactsManifest: ArtifactsManifest? = null,
        val bundleDir: File? = null
    ) : StageResult<Nothing>()

    data class TransientFailure(
        val error: Throwable,
        val stageTurnsTaken: Int,
        val transcript: List<AgentTranscriptEvent>
    ) : StageResult<Nothing>()
}

interface PipelineStage<in TIn, TOut> {
    val stageName: StageName

    /**
     * True when [execute] is a pure function of the stage input, configuration,
     * and stage code: no LLM sampling or other seed-dependent behaviour.
     *
     * The orchestrator passes this through to [pipeline.cas.CacheKeys.compute],
     * which omits sample identity from the cache key for deterministic stages
     * so their bundles are shared across seeds. Sample-dependent stages must
     * leave this false.
     */
    val isDeterministic: Boolean get() = false

    fun computeCacheKey(ctx: RunContext, input: TIn): CasKey
    suspend fun execute(ctx: RunContext, input: TIn): StageResult<TOut>

    /**
     * Reconstructs the typed stage output from a previously published CAS bundle.
     * Returns null when the bundle cannot be revalidated or decoded; the
     * orchestrator then treats the entry as a cache miss.
     */
    fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): TOut?
}

/**
 * Raised for deterministic, non-retryable halts that are not discard outcomes
 * (for example a vacuous/trivial application). The orchestrator records the run
 * as failed rather than retrying it.
 */
class StageHaltException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// --- Typed Stage Inputs and Outputs ---

data class IngestionInput(val benchmarkId: BenchmarkId, val repoUrl: String, val commitHash: CommitHash, val parentHash: CommitHash)
data class IngestionWorktree(
    val commitHash: CommitHash,
    val parentHash: CommitHash,
    val worktreeDir: File,
    val repoDir: File = worktreeDir
)

data class BenchmarkEntry(
    val benchmarkId: BenchmarkId,
    val repoUrl: String,
    val commitHash: CommitHash,
    val parentHash: CommitHash,
    val regressionTest: TestCoordinate,
    val testPatchFile: File? = null,
    val description: String,
    // Raw manifest reference; keeps telemetry stable when repoUrl is resolved to an absolute path.
    val repoIdentity: String = repoUrl,
    val defaultBranch: String = "main",
    val build: BuildSection = BuildSection(),
    val sourceCompatibility: String = "17",
    val targetCompatibility: String = "17",
    // Benchmark-specific source roots. Defaults preserve the legacy
    // src/main (production) + src/test (tests) layout.
    val productionRoots: List<String> = SourceRoots.DEFAULT_PRODUCTION_ROOTS,
    val testRoots: List<String> = SourceRoots.DEFAULT_TEST_ROOTS
) {
    // `commitDiff` must already be filtered to production paths; test-file hunks,
    // the test patch, and test coordinates are intentionally not part of BugContext.
    fun toBugContext(commitDiff: String = ""): BugContext =
        BugContext(benchmarkId, commitHash, parentHash, commitDiff, description)
}

data class ManifestTriageInput(
    val benchmarkId: BenchmarkId,
    val worktree: IngestionWorktree,
    val regressionTest: TestCoordinate,
    val testPatchFile: File? = null
)

data class AutomatedTriageInput(
    val benchmarkId: BenchmarkId,
    val worktree: IngestionWorktree,
    val commitMetadata: CommitMetadata
)

// VerifiedSample is a triage output and a verification input ONLY. It is never
// passed to synthesis, application, or repair; those stages receive the blind
// targets below, which omit the regression test coordinate and test patch.
data class VerifiedSample(
    val benchmarkId: BenchmarkId,
    val commitHash: CommitHash,
    val parentHash: CommitHash,
    val regressionTest: TestCoordinate,
    val testPatchFile: File
)

data class BugContext(
    val benchmarkId: BenchmarkId,
    val commitHash: CommitHash,
    val parentHash: CommitHash,
    val commitDiff: String,
    val bugDescription: String
)

data class SynthesisInput(val bugContext: BugContext)

@Serializable
data class PlacementRule(
    val category: RuleCategory,
    val ruleText: String,
    val targetQualifier: Fqcn? = null
)

@Serializable
data class DeclarativePlacementGuide(
    val rules: List<PlacementRule>
) {
    val totalRules: Int get() = rules.size
    val countsByCategory: Map<RuleCategory, Int>
        get() = RuleCategory.entries.associateWith { cat -> rules.count { it.category == cat } }
}

@Serializable
enum class CheckerCategory {
    @SerialName("custom_qualifier_hierarchy") CUSTOM_QUALIFIER_HIERARCHY,
    @SerialName("builtin_checker") BUILTIN_CHECKER
}

data class SynthesizedLattice(
    val customQualsJar: File,
    val placementGuideFile: File,
    val customQualsSrcDir: File,
    val qualifierClassNames: List<Fqcn>,
    val qualifiersCount: Int,
    val processor: String,
    val checkerCategory: CheckerCategory,
    val helpersCount: Int = 0,
    val policyRuleCount: Int = 0,
    val ruleCountsByCategory: Map<RuleCategory, Int> = emptyMap()
)

data class ApplicationTarget(
    val parentHash: CommitHash,
    val worktreeDir: File
)

data class ApplicationInput(val target: ApplicationTarget, val lattice: SynthesizedLattice)

data class AnnotatedRepo(
    val annotationsPatch: File,
    val astAuditFile: File,
    val initialCheckerErrorsLog: File,
    val checkerErrorCount: Int,
    val isTrivial: Boolean
)

data class BlindRepairTarget(
    val parentHash: CommitHash,
    val worktreeDir: File // Annotated baseline: clean C-1 + annotations.patch
)

data class CheckerConfig(
    val customQualsJar: File,
    val processor: String,
    val checkerCategory: CheckerCategory,
    val qualifierClassNames: List<Fqcn>
)

fun SynthesizedLattice.toCheckerConfig(): CheckerConfig =
    CheckerConfig(customQualsJar, processor, checkerCategory, qualifierClassNames)

data class RepairInput(
    val target: BlindRepairTarget,
    val checker: CheckerConfig,
    val initialCheckerErrorsLog: File,
    val annotationsPatch: File
)

data class RepairResult(
    val repairPatch: File, // Relative to the annotated baseline, NOT clean C-1
    val finalCheckerErrorsLog: File,
    val antiCheatAuditFile: File,
    val guardrailEventsFile: File,
    val remainingTypeErrors: Int
)

data class VerificationInput(
    val sample: VerifiedSample,
    val repair: RepairResult,
    val lattice: SynthesizedLattice,
    val annotationsPatch: File, // Applied before repair.patch
    val worktreeDir: File, // Fresh C-1 worktree owned by this stage
    val verificationScope: VerificationScope = VerificationScope.FULL_SUITE
)

enum class EvalVerdict {
    FULL_SUCCESS,
    TYPE_SUCCESS_RUNTIME_FAIL,
    PARTIAL_SUCCESS_REGRESSION_ONLY,
    /**
     * Checker is clean but at least one pre-existing suite test failed.
     *
     * Named without claiming the failure was newly introduced: the pipeline
     * has no pre-patch baseline comparison for the suite, so this verdict
     * only records that the suite failed on the repaired tree.
    */
    SUITE_FAILURE,
    FIXER_INCOMPLETE,
    /**
     * Required verification evidence is genuinely absent, unusable, or
     * incomplete: no checker invocation/result, no parseable JUnit report,
     * or no tests found. A nonzero test-task exit caused by assertion
     * failures alone (with a valid parseable report) is NOT missing
     * evidence and must map to a substantive runtime/suite verdict.
     */
    VERIFICATION_MISSING;

    companion object {
        /** Resolve stored labels, including the pre-rename suite-failure label. */
        fun fromStoredName(raw: String?): EvalVerdict? {
            if (raw.equals("regression_introduced", ignoreCase = true)) return SUITE_FAILURE
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}

data class VerificationResult(
    val verdict: EvalVerdict,
    /** Null when the test report did not provide usable regression evidence. */
    val regressionTestPassed: Boolean?,
    val originalTestsPassed: Boolean?,
    val remainingCheckerErrors: Int?,
    val testResultsXml: File?,
    val suiteResultsXml: File?,
    val verificationSummaryFile: File
)

// Helper used by stages that persist typed telemetry through DatabasePort.
fun emptyTelemetry(): TelemetryProjection = TelemetryProjection()
