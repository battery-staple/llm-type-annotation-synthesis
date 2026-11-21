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
 */
object FilesystemGuard {

    private val srcMainRoot = Regex("(^|/)src/main/")
    private val srcTestRoot = Regex("(^|/)src/test/")
    private val testFileName = Regex(".*Test\\.java$")

    private val buildScriptPatterns = listOf(
        Regex("(^|/)build\\.gradle[^/]*$"),
        Regex("(^|/)settings\\.gradle[^/]*$"),
        Regex("(^|/)pom\\.xml$"),
        Regex("(^|/)gradle\\.properties$")
    )
    private val astubPattern = Regex(".*\\.astub$")

    fun isPackageInfo(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').let { it == "package-info.java" }

    /** package-info.java is only ever legitimate under `src/main/**/`. */
    fun packageInfoAllowedPath(path: String): Boolean =
        Regex("(^|/)src/main/(.*/)?package-info\\.java$").containsMatchIn(normalize(path))

    fun audit(
        worktree: File,
        changedPaths: List<ChangedPath>,
        stage: StageName,
        allowPackageInfoAddition: Boolean,
        allowTestEdits: Boolean
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
            val isTest = srcTestRoot.containsMatchIn(path) || testFileName.matches(path)

            // 4. package-info.java: banned in repair outright; in application it
            // must live under src/main/ and content is checked by AstGuard.
            if (pkgInfo) {
                if (!packageInfoAllowedPath(path)) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, raw,
                        "package-info.java is only permitted under src/main/: $raw"
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
            val underMain = srcMainRoot.containsMatchIn(path)
            val underTest = srcTestRoot.containsMatchIn(path)
            if (isRepair) {
                if (!underMain) {
                    violations += GuardViolation(
                        if (isTest) GuardrailViolationCategory.TEST_TAMPERING
                        else GuardrailViolationCategory.FILESYSTEM,
                        raw, "Repair edits are confined to (single/multi-module) src/main/: $raw"
                    )
                    continue
                }
            } else {
                if (!underMain && !underTest) {
                    violations += GuardViolation(
                        GuardrailViolationCategory.FILESYSTEM, raw,
                        "Path is outside sanctioned src/main|src/test roots: $raw"
                    )
                    continue
                }
            }

            // 7. File operations: only in-place modifications pass. The single
            // permitted addition (a new src/main/**/package-info.java in
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
                    val parts = line.removePrefix("diff --git ").split(' ').filter { it.isNotBlank() }
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
