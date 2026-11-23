package pipeline.stages

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pipeline.util.PipelineJson

/**
 * Explicit strategy choice produced by the synthesis agent before it writes
 * any qualifier sources.
 *
 * The artifact exists so the pipeline never has to infer an experimental arm
 * from whether files happened to appear. A missing or malformed decision is a
 * retryable validation failure, not a silent switch to a built-in checker.
 */
@Serializable
data class SynthesisDecision(
    @SerialName("checker_category") val checkerCategory: CheckerCategory,
    val processor: String,
    val rationale: String = "",
) {
    companion object {
        const val FILE_NAME = "synthesis_decision.json"
    }
}

object SynthesisDecisionParser {
    fun parse(file: File): SynthesisDecision {
        require(file.isFile) { "${SynthesisDecision.FILE_NAME} is missing from the workspace root" }
        return try {
            PipelineJson.json.decodeFromString(SynthesisDecision.serializer(), file.readText())
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "${SynthesisDecision.FILE_NAME} parse error: ${error.message}",
                error,
            )
        }
    }
}

/**
 * The policy a decision must satisfy. Category and processor are both checked
 * so an agent cannot select a built-in checker with `custom_only`, or name a
 * processor the pipeline is not configured to run.
 */
data class SynthesisDecisionPolicy(
    val allowedCheckers: AllowedCheckers,
    val customProcessor: String,
    val builtinProcessor: String,
)

object SynthesisDecisionValidator {
    fun validate(decision: SynthesisDecision, policy: SynthesisDecisionPolicy): List<String> {
        val problems = mutableListOf<String>()
        when (decision.checkerCategory) {
            CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY -> {
                if (policy.allowedCheckers == AllowedCheckers.BUILTIN_ONLY) {
                    problems += "checker_category 'custom_qualifier_hierarchy' is not allowed when " +
                        "synthesis.allowed_checkers=builtin_only"
                }
                if (decision.processor != policy.customProcessor) {
                    problems += "custom_qualifier_hierarchy decision must name processor " +
                        "'${policy.customProcessor}', got '${decision.processor}'"
                }
                if (BuiltinCheckerCatalog.get(policy.customProcessor) != null) {
                    problems += "checker.processor '${policy.customProcessor}' is a built-in checker and " +
                        "cannot run a custom_qualifier_hierarchy"
                }
            }

            CheckerCategory.BUILTIN_CHECKER -> {
                if (policy.allowedCheckers == AllowedCheckers.CUSTOM_ONLY) {
                    problems += "checker_category 'builtin_checker' is not allowed when " +
                        "synthesis.allowed_checkers=custom_only"
                }
                if (BuiltinCheckerCatalog.get(decision.processor) == null) {
                    problems += "unsupported built-in processor '${decision.processor}'; supported: " +
                        BuiltinCheckerCatalog.supportedProcessors.sorted().joinToString(", ")
                }
                if (decision.processor != policy.builtinProcessor) {
                    problems += "builtin_checker decision must name the configured built-in processor " +
                        "'${policy.builtinProcessor}', got '${decision.processor}'"
                }
            }
        }
        if (decision.rationale.isBlank()) {
            problems += "${SynthesisDecision.FILE_NAME} must include a non-empty rationale"
        }
        return problems
    }
}
