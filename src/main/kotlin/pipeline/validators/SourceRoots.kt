package pipeline.validators

/**
 * Benchmark-configurable production/test source roots.
 *
 * The legacy layout (`src/main` production, `src/test` tests) is the default
 * and is preserved verbatim when a benchmark omits the new configuration.
 * Repositories with a flat layout (for example CMSX, where production lives
 * under `src/` and tests under `test/` and `regression/src/`) declare their own
 * roots per benchmark entry in `benchmarks.yaml`.
 *
 * Matching is directory-segment based, never a bare substring:
 *
 * - A **single-segment** root is anchored at the repository root (`^src/`,
 *   `^test/`). A bare `src` root therefore does not sweep in `regression/src`
 *   or `cmsx-ui/src`, and a bare `test` root does not match CMSX production
 *   helpers under `src/cms/www/action/actions/api/test/`.
 * - A **multi-segment** root matches as complete trailing path segments at
 *   any depth (`(^|/)module/src/test(/|$)`), so legacy multi-module layouts
 *   keep working when only the defaults are configured.
 *
 * Test roots win on the rare overlap with a production root: classifying a
 * path as a test is the fail-closed direction because repair always refuses
 * to edit it and application gates it behind `allowTestEdits`. The legacy
 * `*Test.java` filename signal is retained unchanged as an additional
 * fallback.
 */
object SourceRoots {

    val DEFAULT_PRODUCTION_ROOTS: List<String> = listOf("src/main")
    val DEFAULT_TEST_ROOTS: List<String> = listOf("src/test")

    /** Normalizes roots: trims slashes, drops blanks, de-duplicates. */
    fun normalize(roots: List<String>): List<String> =
        roots.map { it.trim().replace('\\', '/').trim('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    /** Applies [defaults] when [raw] is null or empty. */
    fun withDefaults(raw: List<String>?, defaults: List<String>): List<String> =
        if (raw.isNullOrEmpty()) defaults else normalize(raw)

    /**
     * True when [path] is one of [roots] or lives below one of them.
     *
     * Single-segment roots anchor at the repository root; multi-segment roots
     * match at any depth. The end anchor (`/|$`) also lets directory paths
     * such as `module/src/test` match, which is what read-only test-tree
     * protection relies on.
     */
    fun isUnderRoots(path: String, roots: List<String>): Boolean {
        val normalized = path.replace('\\', '/')
        return normalize(roots).any { root ->
            if (root.contains('/')) {
                Regex("(^|/)${Regex.escape(root)}(/|$)").containsMatchIn(normalized)
            } else {
                normalized == root || normalized.startsWith("$root/")
            }
        }
    }

    /**
     * Directory-based test signal. Test roots win over production roots on
     * overlap so a test path can never be reclassified as editable production
     * by a broad production root.
     */
    fun isTestDir(path: String, testRoots: List<String>): Boolean =
        isUnderRoots(path, testRoots)

    /** Legacy filename fallback, preserved for backwards compatibility. */
    fun isTestFileName(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').let { Regex(".*Test\\.java$").matches(it) }

    /** Full test signal: directory roots or the legacy filename fallback. */
    fun isTestPath(path: String, testRoots: List<String>): Boolean =
        isTestDir(path, testRoots) || isTestFileName(path)

    /** Production signal: under a production root. */
    fun isProductionPath(path: String, productionRoots: List<String>): Boolean =
        isUnderRoots(path, productionRoots)

    /** `package-info.java` is only ever legitimate under a production root. */
    fun packageInfoAllowedPath(path: String, productionRoots: List<String>): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.substringAfterLast('/') == "package-info.java" &&
            isUnderRoots(normalized, productionRoots)
    }

    /**
     * Git pathspecs that mirror [isUnderRoots]: bare roots are anchored at the
     * repository root, while multi-segment roots may appear under any module.
     */
    fun gitPathspecs(roots: List<String>): List<String> =
        normalize(roots).map { root ->
            if (root.contains('/')) ":(glob)**/$root/**" else ":(glob)$root/**"
        }

    /** Human-readable comma listing for prompts. */
    fun display(roots: List<String>): String = normalize(roots).joinToString(", ")

    /** Inline-code glob listing for prompts, joined with " and ". */
    fun displayGlobs(roots: List<String>): String =
        normalize(roots).joinToString(" and ") { "`$it/**`" }
}
