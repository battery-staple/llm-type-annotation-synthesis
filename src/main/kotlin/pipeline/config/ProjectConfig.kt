package pipeline.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pipeline.stages.AllowedCheckers
import pipeline.stages.CheckerCategory
import pipeline.stages.FreedomLevel
import pipeline.stages.GuardrailsMode
import pipeline.stages.LocalAnnotationPolicy
import pipeline.stages.VerificationScope

@Serializable
data class ProjectSection(
    val name: String = "cmsx",
    @SerialName("repo_url") val repoUrl: String = "",
    @SerialName("root_dir") val rootDir: String = "eval-repos/cmsx",
    @SerialName("default_branch") val defaultBranch: String = "main"
)

@Serializable
data class TriageSection(
    val mode: String = "manifest",
    @SerialName("manifest_file") val manifestFile: String = "benchmarks.yaml"
)

@Serializable
data class EnvironmentSection(
    @SerialName("java_home") val javaHome: String = "",
    @SerialName("source_compatibility") val sourceCompatibility: String = "17",
    @SerialName("target_compatibility") val targetCompatibility: String? = null,
    @SerialName("checker_framework_home") val checkerFrameworkHome: String = "vendor/checker-framework-3.42.0",
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
data class SampleSection(
    val seed: Int = 0,
    val temperature: Double = 0.0
)

@Serializable
data class SynthesisSection(
    @SerialName("freedom_level") val freedomLevel: FreedomLevel = FreedomLevel.LEVEL_0,
    @SerialName("allowed_checkers") val allowedCheckers: AllowedCheckers = AllowedCheckers.ALL
)

@Serializable
data class CheckerSection(
    val processor: String = "org.checkerframework.common.subtyping.SubtypingChecker",
    val category: CheckerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
    val quals: List<String> = emptyList()
)

@Serializable
data class RepairSection(
    @SerialName("local_variable_annotations") val localVariableAnnotations: LocalAnnotationPolicy = LocalAnnotationPolicy.FROZEN,
    @SerialName("internet_access") val internetAccess: Boolean = false
)

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
    @SerialName("checker_task") val checkerTask: String = "checkerPass",
    @SerialName("single_test_cmd") val singleTestCmd: String = "./gradlew test --tests {test_class}.{test_method}"
)

@Serializable
data class LoggingSection(
    val level: String = "INFO",
    val file: String = "logs/pipeline.log",
    @SerialName("json_format") val jsonFormat: Boolean = true,
    @SerialName("per_run_file") val perRunFile: Boolean = true
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

@Serializable
data class ProjectConfig(
    val project: ProjectSection = ProjectSection(),
    val triage: TriageSection = TriageSection(),
    val environment: EnvironmentSection = EnvironmentSection(),
    val guardrails: GuardrailsSection = GuardrailsSection(),
    val model: ModelSection = ModelSection(),
    val agent: AgentSection = AgentSection(),
    val sample: SampleSection = SampleSection(),
    val synthesis: SynthesisSection = SynthesisSection(),
    val checker: CheckerSection = CheckerSection(),
    val repair: RepairSection = RepairSection(),
    val prompt: PromptSection = PromptSection(),
    val build: BuildSection = BuildSection(),
    val logging: LoggingSection = LoggingSection(),
    val cache: CacheSection = CacheSection(),
    val verification: VerificationSection = VerificationSection()
) {
    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        fun load(file: File): ProjectConfig {
            require(file.isFile) { "Configuration file not found: ${file.absolutePath}" }
            val config = yaml.decodeFromString(ProjectConfig.serializer(), file.readText())
            require(config.verification.baseline == "none") {
                "verification.baseline='${config.verification.baseline}' is not supported in the MVP; only 'none' is accepted"
            }
            require(config.triage.mode in setOf("manifest", "crawler")) {
                "triage.mode='${config.triage.mode}' is invalid"
            }
            return config
        }
    }
}
