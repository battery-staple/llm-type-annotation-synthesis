package pipeline.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import pipeline.stages.AllowedCheckers
import pipeline.stages.BuiltinCheckerCatalog
import pipeline.stages.CheckerCategory
import pipeline.stages.FreedomLevel
import pipeline.stages.GuardrailsMode
import pipeline.stages.LocalAnnotationPolicy
import pipeline.stages.TriageMode
import pipeline.stages.VerificationScope
import pipeline.util.Env

@Serializable
data class TriageSection(
    val mode: TriageMode = TriageMode.MANIFEST,
    @SerialName("manifest_file") val manifestFile: String = "benchmarks.yaml",
    @SerialName("repos_file") val reposFile: String = "repos.yaml",
    val depth: Int = 20,
    val limit: Int? = null,
    val jobs: Int = 1,
    @SerialName("bugfix_threshold") val bugfixThreshold: Double = 0.75
) {
    init {
        require(jobs >= 1) { "triage.jobs must be at least 1" }
        require(limit == null || limit >= 0) { "triage.limit must be non-negative" }
    }
}

@Serializable
data class ConcurrencySection(val agents: Int = 8, val builds: Int = 2) {
    init {
        require(agents >= 1) { "concurrency.agents must be at least 1" }
        require(builds >= 1) { "concurrency.builds must be at least 1" }
    }
}

/**
 * Whole-stage transient retry budget used by the orchestrator for every stage.
 * Attempts are 1-indexed, so `attempts = 1` disables retries. `backoffMs` is the
 * base for exponential delay: base << attempt (1000 -> 2s, 4s, 8s, ...).
 */
@Serializable
data class StageRetrySection(
    val attempts: Int = 3,
    @SerialName("backoff_ms") val backoffMs: Long = 1_000
) {
    init {
        require(attempts >= 1) { "stage.attempts must be at least 1" }
        require(backoffMs >= 0) { "stage.backoff_ms must be non-negative" }
    }
}

@Serializable
data class EnvironmentSection(
    @SerialName("java_home") val javaHome: String = "",
    @SerialName("source_compatibility") val sourceCompatibility: String = "17",
    @SerialName("target_compatibility") val targetCompatibility: String? = null,
    @SerialName("checker_framework_home") val checkerFrameworkHome: String = "",
    @SerialName("gradle_user_home") val gradleUserHome: String = ""
) {
    val effectiveTargetCompatibility: String get() = targetCompatibility ?: sourceCompatibility
}

@Serializable
data class GuardrailsSection(val mode: GuardrailsMode = GuardrailsMode.LENIENT)

@Serializable
data class ModelSection(
    val default: String = "opencode-go/deepseek-v4.1-flash",
    val triage: String? = null,
    val synthesis: String? = null,
    val application: String? = null,
    val repair: String? = null
) {
    val triageModel: String get() = triage ?: default
    val synthesisModel: String get() = synthesis ?: default
    val applicationModel: String get() = application ?: default
    val repairModel: String get() = repair ?: default
}

@Serializable
data class AgentStepsSection(
    val synthesis: Int = 25,
    val application: Int = 20,
    val repair: Int = 50
)

@Serializable
data class AgentSection(
    val steps: AgentStepsSection = AgentStepsSection(),
    @SerialName("timeout_ms") val timeoutMs: Long = 1_800_000,
    @SerialName("max_checker_invocations") val maxCheckerInvocations: Int = 20
)

@Serializable
data class SampleSection(val temperature: Double = 0.0)

@Serializable
data class PathsSection(
    @SerialName("eval_repos_dir") val evalReposDir: String = "eval-repos",
    val database: String = "data/pipeline.db"
) {
    fun evalRepos(): File = File(evalReposDir)
    fun databaseFile(): File = File(database)
}

@Serializable
data class SynthesisSection(
    @SerialName("freedom_level") val freedomLevel: FreedomLevel = FreedomLevel.LEVEL_0,
    @SerialName("allowed_checkers") val allowedCheckers: AllowedCheckers = AllowedCheckers.ALL,
    /**
     * The single built-in Checker Framework processor an agent may select when
     * a built-in strategy is allowed. The choice is explicit per run, not a
     * fallback inferred from missing qualifier sources.
     */
    @SerialName("builtin_checker") val builtinChecker: String = "org.checkerframework.checker.nullness.NullnessChecker",
    /** Number of agent sessions (feedback rounds) inside one stage execution. */
    @SerialName("retry_sessions") val retrySessions: Int = 3
) {
    init {
        require(retrySessions >= 1) { "synthesis.retry_sessions must be at least 1" }
    }
}

/** Application-stage settings. It has no other build-independent policy today. */
@Serializable
data class ApplicationSection(
    /** Number of agent sessions (feedback rounds) inside one stage execution. */
    @SerialName("retry_sessions") val retrySessions: Int = 3
) {
    init {
        require(retrySessions >= 1) { "application.retry_sessions must be at least 1" }
    }
}

@Serializable
data class CheckerSection(
    val processor: String = "org.checkerframework.common.subtyping.SubtypingChecker",
    val category: CheckerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY
    // No `quals` key: qualifiers are per-run synthesis output, never static config.
    // Every Gradle invocation takes them from the SynthesizedLattice.
)

@Serializable
data class RepairSection(
    @SerialName("local_variable_annotations") val localVariableAnnotations: LocalAnnotationPolicy = LocalAnnotationPolicy.FROZEN,
    @SerialName("internet_access") val internetAccess: Boolean = false,
    /** Turn budget; still bounded by agent.max_checker_invocations at runtime. */
    @SerialName("retry_turns") val retryTurns: Int = 4
) {
    init {
        require(retryTurns >= 1) { "repair.retry_turns must be at least 1" }
    }
}

@Serializable
data class PromptSection(
    @SerialName("tell_non_triviality_error") val tellNonTrivialityError: Boolean = false
)

@Serializable
data class BuildSection(
    val tool: String = "gradle",
    @SerialName("wrapper_executable") val wrapperExecutable: String = "./gradlew",
    val subproject: String = "",
    @SerialName("compile_task") val compileTask: String = "compileJava",
    @SerialName("test_task") val testTask: String = "test",
    @SerialName("checker_task") val checkerTask: String = "checkerPass"
)

@Serializable
data class CacheSection(
    @SerialName("stages_dir") val stagesDir: String = ".cache/stages",
    val enabled: Boolean = true
)

@Serializable
data class VerificationSection(
    val scope: VerificationScope = VerificationScope.FULL_SUITE,
    val baseline: String = "none"
)

/**
 * Build-integration preflight (Dockerization plan, section 7.3). When enabled
 * it runs between ingestion and triage; a repository outside the supported
 * envelope ends with lifecycle status `unsupported` before any LLM call. Off by
 * default: `pipeline run --preflight` (or `enabled: true`) turns it on, and
 * `pipeline preflight` always runs it.
 */
@Serializable
data class PreflightSection(
    val enabled: Boolean = false,
    /**
     * Check P7: run the full test task at clean C-1 under the build timeout.
     * Application and verification both run the full suite, so a suite that
     * cannot finish or already fails means the run cannot reach a verdict.
     */
    @SerialName("suite_check") val suiteCheck: Boolean = true
)

@Serializable
data class ProjectConfig(
    val triage: TriageSection = TriageSection(),
    val concurrency: ConcurrencySection = ConcurrencySection(),
    val stage: StageRetrySection = StageRetrySection(),
    val paths: PathsSection = PathsSection(),
    val environment: EnvironmentSection = EnvironmentSection(),
    val guardrails: GuardrailsSection = GuardrailsSection(),
    val model: ModelSection = ModelSection(),
    val agent: AgentSection = AgentSection(),
    val sample: SampleSection = SampleSection(),
    val synthesis: SynthesisSection = SynthesisSection(),
    val application: ApplicationSection = ApplicationSection(),
    val checker: CheckerSection = CheckerSection(),
    val repair: RepairSection = RepairSection(),
    val prompt: PromptSection = PromptSection(),
    val cache: CacheSection = CacheSection(),
    val verification: VerificationSection = VerificationSection(),
    val preflight: PreflightSection = PreflightSection(),
    @Transient val sourceFile: File = File("project_config.yaml")
) {
    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        fun load(file: File): ProjectConfig {
            val absolute = file.absoluteFile
            require(absolute.isFile) { "Configuration file not found: ${absolute.absolutePath}" }
            val decoded = yaml.decodeFromString(ProjectConfig.serializer(), absolute.readText())
            val base = absolute.parentFile ?: File(".")
            fun resolve(raw: String): String = if (raw.isBlank()) raw else {
                val path = File(raw).let { if (it.isAbsolute) it else File(base, raw) }
                path.toPath().normalize().toString()
            }
            val checkerHome = decoded.environment.checkerFrameworkHome
                .takeIf { it.isNotBlank() }
                ?: Env.get("CHECKERFRAMEWORK")?.takeIf { it.isNotBlank() }
                ?: "vendor/checker-framework-3.42.0"
            val config = decoded.copy(
                triage = decoded.triage.copy(
                    manifestFile = resolve(decoded.triage.manifestFile),
                    reposFile = resolve(decoded.triage.reposFile)
                ),
                paths = decoded.paths.copy(
                    evalReposDir = resolve(decoded.paths.evalReposDir),
                    database = resolve(decoded.paths.database)
                ),
                environment = decoded.environment.copy(
                    checkerFrameworkHome = resolve(checkerHome),
                    javaHome = resolve(decoded.environment.javaHome),
                ),
                cache = decoded.cache.copy(stagesDir = resolve(decoded.cache.stagesDir)),
                sourceFile = absolute
            )
            require(config.verification.baseline == "none") {
                "verification.baseline='${config.verification.baseline}' is not supported in the MVP; only 'none' is accepted"
            }
            require(config.synthesis.builtinChecker in BuiltinCheckerCatalog.supportedProcessors) {
                "synthesis.builtin_checker='${config.synthesis.builtinChecker}' is not supported; " +
                    "supported: ${BuiltinCheckerCatalog.supportedProcessors.sorted().joinToString(", ")}"
            }
            return config
        }
    }
}
