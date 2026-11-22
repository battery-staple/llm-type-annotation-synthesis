package pipeline.validators

import java.io.File
import pipeline.cas.ChangedPath
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.StageName

data class GuardViolation(
    val category: GuardrailViolationCategory,
    val filePath: String?,
    val message: String,
    val diffSnippet: String? = null
)

data class PathAuditResult(
    val valid: Boolean,
    val violations: List<GuardViolation>,
    val changedPaths: List<ChangedPath>
)

/**
 * Filesystem boundary defense shared by the application and repair stages.
 *
 * Validates repository-relative paths extracted from a staged Git diff
 * (`git add -N` + `git diff --cached`). Never parses file contents.
 *
 * Production/test roots are benchmark-configurable ([SourceRoots]); the
 * default overloads preserve the legacy `src/main` / `src/test` behavior for
 * benchmarks that omit the new configuration.
 */
object FilesystemGuard {

    private val buildScriptPatterns = listOf(
        Regex("(^|/)build\\.gradle[^/]*$"),
        Regex("(^|/)settings\\.gradle[^/]*$"),
        Regex("(^|/)pom\\.xml$"),
        Regex("(^|/)gradle\\.properties$")
    )
    private val astubPattern = Regex(".*\\.astub$")

    fun isPackageInfo(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').let { it == "package-info.java" }

    /** package-info.java is only ever legitimate under a production root. */
    fun packageInfoAllowedPath(path: String): Boolean =
        packageInfoAllowedPath(path, SourceRoots.DEFAULT_PRODUCTION_ROOTS)

    fun packageInfoAllowedPath(path: String, productionRoots: List<String>): Boolean =
        SourceRoots.packageInfoAllowedPath(path, productionRoots)

    /** Legacy entry point: audits against the default `src/main` / `src/test` roots. */
    fun audit(
        worktree: File,
        changedPaths: List<ChangedPath>,
        stage: StageName,
        allowPackageInfoAddition: Boolean,
        allowTestEdits: Boolean
    ): PathAuditResult = audit(
        worktree, changedPaths, stage, allowPackageInfoAddition, allowTestEdits,
        SourceRoots.DEFAULT_PRODUCTION_ROOTS, SourceRoots.DEFAULT_TEST_ROOTS
    )

    fun audit(
        worktree: File,
        changedPaths: List<ChangedPath>,
        stage: StageName,
        allowPackageInfoAddition: Boolean,
        allowTestEdits: Boolean,
        productionRoots: List<String>,
        testRoots: List<String>
    ): PathAuditResult {
        val violations = mutableListOf<GuardViolation>()
        val isRepair = (stage == StageName.REPAIR)
        val base = worktree.canonicalFile

        for (cp in changedPaths) {
            val raw = cp.path
            val path = normalize(raw)

            // 1. Absolute paths and `..` traversal never reach the filesystem.
            if (isAbsolutePath(raw) || hasDotDotSegment(path)) {
                violations += GuardViolation(
                    GuardrailViolationCategory.PATH_ESCAPE, raw,
                    "Path escapes the worktree (absolute path or '..' traversal): $raw"
                )
                continue
            }

            // 2. Canonical containment: catches symlink escapes even when the
            // lexical path looks innocent.
            val target = File(worktree, raw).canonicalFile
            val basePath = base.path + File.separator
            if (target != base && !target.path.startsWith(basePath)) {
                violations += GuardViolation(
                    GuardrailViolationCategory.PATH_ESCAPE, raw,
                    "Resolved path escapes the worktree (possible symlink escape): $raw"
                )
                continue
            }

            // 3. Build scripts and stub files are never editable by agents.
            if (buildScriptPatterns.any { it.containsMatchIn(path) } || astubPattern.matches(path)) {
                violations += GuardViolation(
                    GuardrailViolationCategory.BUILD_TAMPERING, raw,
                    "Build artifact is not editable by agents: $raw"
                )
                continue
            }

            val pkgInfo = isPackageInfo(path)
            // Directory roots rule; test roots win on overlap so a broad
            // production root can never make a test source editable. The
            // legacy `*Test.java` filename signal is retained as a fallback.
            val isTest = SourceRoots.isTestPath(path, testRoots)

            // 4. package-info.java: banned in repair outright; in application it
            // must live under a production root and content is checked by AstGuard.
            if (pkgInfo) {
                if (!packageInfoAllowedPath(path, productionRoots)) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, raw,
                        "package-info.java is only permitted under ${SourceRoots.display(productionRoots)}: $raw"
                    )
                    continue
                }
                if (isRepair) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, raw,
                        "package-info.java is frozen in repair: $raw"
                    )
                    continue
                }
                if (cp.isDeletion || cp.isRename) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.FILESYSTEM, raw,
                        "package-info.java may not be deleted or renamed: $raw"
                    )
                    continue
                }
                if (cp.isAddition && !allowPackageInfoAddition) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.FILE_ADDITION, raw,
                        "New package-info.java is not permitted in this stage: $raw"
                    )
                    continue
                }
                continue
            }

            // 5. Test sources: read-only in repair; gated by flag elsewhere.
            if (isTest) {
                if (isRepair || !allowTestEdits) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.TEST_TAMPERING, raw,
                        "Test sources are read-only in this stage: $raw"
                    )
                    continue
                }
            }

            // 6. Production confinement.
            val underProd = SourceRoots.isUnderRoots(path, productionRoots)
            val underTestDir = SourceRoots.isTestDir(path, testRoots)
            if (isRepair) {
                if (!underProd) {
                    violations += GuardViolation(
                        if (isTest) GuardrailViolationCategory.TEST_TAMPERING
                        else GuardrailViolationCategory.FILESYSTEM,
                        raw, "Repair edits are confined to production roots (${SourceRoots.display(productionRoots)}): $raw"
                    )
                    continue
                }
            } else {
                if (!underProd && !underTestDir) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.FILESYSTEM, raw,
                        "Path is outside sanctioned production/test roots (${SourceRoots.display(productionRoots)}|${SourceRoots.display(testRoots)}): $raw"
                    )
                    continue
                }
            }

            // 7. File operations: only in-place modifications pass. The single
            // permitted addition (a new production-root package-info.java in
            // application) was handled above.
            when {
                cp.isAddition -> violations += GuardViolation(
                    GuardrailViolationCategory.FILE_ADDITION, raw,
                    "File additions are rejected in this stage: $raw"
                )
                cp.isDeletion -> violations += GuardViolation(
                    GuardrailViolationCategory.FILESYSTEM, raw,
                    "File deletions are rejected in this stage: $raw"
                )
                cp.isRename -> violations += GuardViolation(
                    GuardrailViolationCategory.FILESYSTEM, raw,
                    "File renames are rejected in this stage: $raw"
                )
            }
        }

        return PathAuditResult(violations.isEmpty(), violations, changedPaths)
    }

    /**
     * Parses a unified patch text and returns the touched real paths.
     * Understands `diff --git`, `---`/`+++`, `rename from`/`rename to`, and
     * `new file`/`deleted file` markers. `/dev/null` sentinels are ignored and
     * absolute or `..`-traversing paths are rejected (excluded).
     */
    fun auditPatchPaths(patchText: String): List<String> {
        val touched = linkedSetOf<String>()
        patchText.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("diff --git ") -> {
                    val parts = splitQuoted(line.removePrefix("diff --git ")).filter { it.isNotBlank() }
                    if (parts.size >= 2) {
                        stripGitPrefix(parts[parts.size - 2])?.let { touched += it }
                        stripGitPrefix(parts[parts.size - 1])?.let { touched += it }
                    }
                }
                line.startsWith("--- ") -> {
                    headerPath(line.removePrefix("--- ").trim())?.let { touched += it }
                }
                line.startsWith("+++ ") -> {
                    headerPath(line.removePrefix("+++ ").trim())?.let { touched += it }
                }
                line.startsWith("rename from ") -> {
                    cleanPath(line.removePrefix("rename from ").trim())?.let { touched += it }
                }
                line.startsWith("rename to ") -> {
                    cleanPath(line.removePrefix("rename to ").trim())?.let { touched += it }
                }
            }
        }
        return touched.toList()
    }

    private fun stripGitPrefix(token: String): String? {
        val t = token.trim()
        if (t == "/dev/null") return null
        val stripped = when {
            t.startsWith("a/") || t.startsWith("b/") -> t.substring(2)
            t.startsWith("\"a/") || t.startsWith("\"b/") -> t.substring(3).trimEnd('"')
            else -> t.trim('"')
        }
        return cleanPath(stripped)
    }

    /**
     * Splits a Git header line on unquoted spaces. Git C-quotes paths that
     * contain spaces (for example `diff --git "a/foo bar" "b/foo bar"`), so a
     * naive `split(' ')` would corrupt both paths.
     */
    private fun splitQuoted(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        line.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && inQuotes -> {
                    current.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inQuotes = !inQuotes
                }
                ch == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun headerPath(token: String): String? {
        var t = token.trim()
        if (t == "/dev/null") return null
        // Timestamps may follow the path separated by a tab.
        t = t.split('\t').first().trim().trim('"')
        return stripGitPrefix(t)
    }

    /** Returns null for absolute paths, `..` traversals, and blanks. */
    private fun cleanPath(path: String): String? {
        val p = normalize(path.trim().trim('"'))
        if (p.isBlank()) return null
        if (isAbsolutePath(p) || hasDotDotSegment(p)) return null
        return p
    }

    private fun normalize(path: String): String = path.replace('\\', '/')

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)

    private fun hasDotDotSegment(path: String): Boolean =
        path.split('/').any { it == ".." }
}
