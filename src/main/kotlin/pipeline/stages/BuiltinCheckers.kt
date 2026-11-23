package pipeline.stages

/**
 * One Checker Framework built-in checker that the pipeline can select
 * explicitly. The catalog is the single source of truth for the configured
 * `synthesis.builtin_checker`, the qualifier names advertised to the
 * application agent, and the import package the agent should use.
 */
data class BuiltinChecker(
    val processor: String,
    val displayName: String,
    val qualifierNames: List<String>,
    val importPrefix: String,
)

/**
 * Built-in checkers supported by the explicit-decision path.
 *
 * The list is intentionally small: each entry must be a single Checker
 * Framework processor whose annotations are type-use qualifiers in
 * `checker-qual.jar`, so the checker post-pass can run without custom
 * qualifier sources or `-Aquals`.
 */
object BuiltinCheckerCatalog {
    val nullness = BuiltinChecker(
        processor = "org.checkerframework.checker.nullness.NullnessChecker",
        displayName = "Nullness Checker",
        qualifierNames = listOf(
            "org.checkerframework.checker.nullness.qual.NonNull",
            "org.checkerframework.checker.nullness.qual.Nullable",
            "org.checkerframework.checker.nullness.qual.MonotonicNonNull",
        ),
        importPrefix = "org.checkerframework.checker.nullness.qual",
    )

    val signedness = BuiltinChecker(
        processor = "org.checkerframework.checker.signedness.SignednessChecker",
        displayName = "Signedness Checker",
        qualifierNames = listOf(
            "org.checkerframework.checker.signedness.qual.Signed",
            "org.checkerframework.checker.signedness.qual.Unsigned",
        ),
        importPrefix = "org.checkerframework.checker.signedness.qual",
    )

    val index = BuiltinChecker(
        processor = "org.checkerframework.checker.index.IndexChecker",
        displayName = "Index Checker",
        qualifierNames = listOf(
            "org.checkerframework.checker.index.qual.NonNegative",
            "org.checkerframework.checker.index.qual.Positive",
            "org.checkerframework.checker.index.qual.IndexFor",
            "org.checkerframework.checker.index.qual.IndexOrLow",
            "org.checkerframework.checker.index.qual.IndexOrHigh",
            "org.checkerframework.checker.index.qual.LTLengthOf",
            "org.checkerframework.checker.index.qual.LTOMLengthOf",
            "org.checkerframework.checker.index.qual.SubstringIndexFor",
        ),
        importPrefix = "org.checkerframework.checker.index.qual",
    )

    val regex = BuiltinChecker(
        processor = "org.checkerframework.checker.regex.RegexChecker",
        displayName = "Regex Checker",
        qualifierNames = listOf("org.checkerframework.checker.regex.qual.Regex"),
        importPrefix = "org.checkerframework.checker.regex.qual",
    )

    val formatString = BuiltinChecker(
        processor = "org.checkerframework.checker.formatter.FormatterChecker",
        displayName = "Format String Checker",
        qualifierNames = listOf(
            "org.checkerframework.checker.formatter.qual.Format",
            "org.checkerframework.checker.formatter.qual.InvalidFormat",
        ),
        importPrefix = "org.checkerframework.checker.formatter.qual",
    )

    val all: Map<String, BuiltinChecker> = listOf(
        nullness,
        signedness,
        index,
        regex,
        formatString,
    ).associateBy { it.processor }

    val supportedProcessors: Set<String> = all.keys

    fun get(processor: String): BuiltinChecker? = all[processor]

    fun require(processor: String): BuiltinChecker = all[processor]
        ?: error("unsupported built-in checker '${processor}'; supported: ${supportedProcessors.sorted().joinToString(", ")}")
}
