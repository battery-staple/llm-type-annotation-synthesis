package pipeline.validators

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.type.Type
import java.io.File
import pipeline.cas.ChangedPath
import pipeline.stages.AnnotationScanner
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.ScannedPlacement
import pipeline.stages.SynthesizedLattice
import pipeline.util.Git

data class AstViolation(
    val category: GuardrailViolationCategory,
    val filePath: String?,
    val line: Int?,
    val message: String
)

data class AstAuditResult(
    val valid: Boolean,
    val violations: List<AstViolation>,
    val placements: List<ScannedPlacement>,
    val details: String = ""
)

/**
 * Application-stage AST gate: only type annotations and their imports may
 * change. Strips validated TYPE_USE qualifier annotations (plus Checker
 * Framework annotations) and all imports from both ASTs, then requires the
 * normalized trees to be identical.
 */
object AstGuard {

    private val suppressionNames = setOf("SuppressWarnings", "AssumeAssertion")

    private val packageInfoAllowedFqns = setOf(
        "org.checkerframework.framework.qual.DefaultQualifier",
        "org.checkerframework.framework.qual.DefaultQualifierInHierarchy",
        "org.checkerframework.framework.qual.QualifierDefaults"
    )
    private val packageInfoAllowedSimple = setOf(
        "DefaultQualifier", "DefaultQualifierInHierarchy", "QualifierDefaults"
    )

    private fun ensureLanguageLevel() {
        runCatching {
            StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        }
    }

    fun validate(
        worktree: File,
        changedPaths: List<ChangedPath>,
        lattice: SynthesizedLattice,
        allowPackageInfoAddition: Boolean = true
    ): AstAuditResult {
        ensureLanguageLevel()
        val violations = mutableListOf<AstViolation>()
        val qualifierPackages = lattice.qualifierClassNames.map { it.packageName }
            .filter { it.isNotBlank() }.toSet()
        val qualifierSimple = lattice.qualifierClassNames.map { it.simpleName }.toSet()
        val qualifierFqns = lattice.qualifierClassNames.map { it.value }.toSet()

        for (cp in changedPaths) {
            val relPath = cp.path.replace('\\', '/')
            val file = File(worktree, cp.path)

            if (FilesystemGuard.isPackageInfo(relPath)) {
                if (cp.isAddition && !allowPackageInfoAddition) {
                    violations += AstViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, relPath, null,
                        "New package-info.java is not permitted in this stage: $relPath"
                    )
                    continue
                }
                if (cp.isDeletion) {
                    violations += AstViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, relPath, null,
                        "package-info.java may not be deleted: $relPath"
                    )
                    continue
                }
                if (!file.isFile) {
                    violations += AstViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, relPath, null,
                        "package-info.java is missing from the worktree: $relPath"
                    )
                    continue
                }
                validatePackageInfo(file.readText()).forEach { problem ->
                    violations += AstViolation(
                        GuardrailViolationCategory.PACKAGE_INFO_CHANGE, relPath, null, problem
                    )
                }
                continue
            }

            if (cp.isDeletion) {
                violations += AstViolation(
                    GuardrailViolationCategory.EXECUTABLE_EDIT, relPath, null,
                    "File deletion is not a type-annotation edit: $relPath"
                )
                continue
            }
            if (!file.isFile) {
                violations += AstViolation(
                    GuardrailViolationCategory.FILE_ADDITION, relPath, null,
                    "New source file is not permitted (only package-info.java may be added): $relPath"
                )
                continue
            }

            val modifiedText = file.readText()
            val modified: CompilationUnit = try {
                StaticJavaParser.parse(modifiedText)
            } catch (e: Exception) {
                violations += AstViolation(
                    GuardrailViolationCategory.EXECUTABLE_EDIT, relPath, null,
                    "Modified file does not parse: ${e.message?.take(300)}"
                )
                continue
            }

            val originalText = readOriginal(worktree, relPath)
            if (originalText == null) {
                violations += AstViolation(
                    GuardrailViolationCategory.FILE_ADDITION, relPath, null,
                    "No HEAD baseline for modified file (untracked addition): $relPath"
                )
                continue
            }
            val original: CompilationUnit = try {
                StaticJavaParser.parse(originalText)
            } catch (e: Exception) {
                violations += AstViolation(
                    GuardrailViolationCategory.EXECUTABLE_EDIT, relPath, null,
                    "Baseline file does not parse: ${e.message?.take(300)}"
                )
                continue
            }

            checkAddedImports(original, modified, qualifierPackages, relPath, violations)
            checkAddedAnnotations(
                original, modified, qualifierSimple, qualifierFqns, relPath, violations
            )

            val strippedOriginal = stripForComparison(original, qualifierSimple, qualifierFqns)
            val strippedModified = stripForComparison(modified, qualifierSimple, qualifierFqns)
            if (normalize(strippedOriginal.toString()) != normalize(strippedModified.toString())) {
                violations += AstViolation(
                    GuardrailViolationCategory.EXECUTABLE_EDIT, relPath, null,
                    "Non-annotation executable change detected after stripping qualifier " +
                        "annotations and imports: $relPath"
                )
            }
        }

        val placements = runCatching {
            val changedSet = changedPaths.map { it.path.replace('\\', '/') }.toSet()
            AnnotationScanner.scan(worktree, qualifierSimple)
                .filter { it.filePath in changedSet }
        }.getOrDefault(emptyList())

        val details = buildString {
            append("checked ${changedPaths.size} file(s), ")
            append("${violations.size} violation(s), ")
            append("${placements.size} qualifier placement(s) in changed files")
        }
        return AstAuditResult(violations.isEmpty(), violations, placements, details)
    }

    /**
     * Validates a package-info.java body. Returns problems; empty means valid.
     * Only the package declaration, imports, and the explicit Checker
     * Framework defaulting annotations are allowed.
     */
    fun validatePackageInfo(text: String): List<String> {
        ensureLanguageLevel()
        val problems = mutableListOf<String>()
        val cu: CompilationUnit = try {
            StaticJavaParser.parse(text)
        } catch (e: Exception) {
            return listOf("package-info.java does not parse: ${e.message?.take(300)}")
        }

        if (!cu.packageDeclaration.isPresent) {
            problems += "package-info.java must declare a package"
        }
        if (cu.types.isNotEmpty()) {
            problems += "package-info.java must not declare types " +
                "(found: ${cu.types.joinToString { it.nameAsString }}); " +
                "no fields, methods, initializers, or nested types are allowed"
        }

        val imports = cu.imports.map { it.name.asString() }
        imports.filterNot { it.startsWith("org.checkerframework.") }.forEach { name ->
            problems += "package-info.java imports only org.checkerframework.* (found: $name)"
        }

        val annotations = cu.packageDeclaration.map { it.annotations }.orElse(null)
        if (annotations == null) {
            problems += "package-info.java annotations could not be read"
        } else {
            annotations.forEach { annotation ->
                val name = annotation.nameAsString
                val simple = name.substringAfterLast('.')
                val allowed = when {
                    '.' in name -> name in packageInfoAllowedFqns
                    else -> simple in packageInfoAllowedSimple &&
                        imports.any { imp -> imp.startsWith("org.checkerframework.") && imp.endsWith(".$simple") }
                }
                if (!allowed) {
                    problems += "package-info.java allows only DefaultQualifier, " +
                        "DefaultQualifierInHierarchy, or QualifierDefaults from " +
                        "org.checkerframework.framework.qual (found: $name)"
                }
            }
        }
        return problems
    }

    // --- internal helpers ---

    private fun readOriginal(worktree: File, relPath: String): String? {
        val result = Git.run(worktree, "show", "HEAD:$relPath")
        if (result.exitCode != 0) return null
        return result.stdout
    }

    private fun checkAddedImports(
        original: CompilationUnit,
        modified: CompilationUnit,
        qualifierPackages: Set<String>,
        relPath: String,
        violations: MutableList<AstViolation>
    ) {
        val originalImports = original.imports.map { importKey(it) }.toSet()
        modified.imports.forEach { import ->
            if (importKey(import) in originalImports) return@forEach
            val name = import.name.asString()
            val allowed = name.startsWith("org.checkerframework.") ||
                qualifierPackages.any { pkg -> name == pkg || name.startsWith("$pkg.") }
            if (!allowed) {
                val line = import.range.map { it.begin.line }.orElse(null)
                violations += AstViolation(
                    GuardrailViolationCategory.EXECUTABLE_EDIT, relPath, line,
                    "Unauthorized import '$name' (only lattice qualifier packages and " +
                        "org.checkerframework.* are allowed)"
                )
            }
        }
    }

    private fun importKey(import: ImportDeclaration): String =
        (if (import.isStatic) "static:" else "") + import.nameAsString +
            (if (import.isAsterisk) ".*" else "")

    private fun checkAddedAnnotations(
        original: CompilationUnit,
        modified: CompilationUnit,
        qualifierSimple: Set<String>,
        qualifierFqns: Set<String>,
        relPath: String,
        violations: MutableList<AstViolation>
    ) {
        val remaining = original.findAll(AnnotationExpr::class.java)
            .groupingBy { normalize(it.toString()) }.eachCount().toMutableMap()
        val added = mutableListOf<AnnotationExpr>()
        modified.findAll(AnnotationExpr::class.java).forEach { annotation ->
            val key = normalize(annotation.toString())
            val count = remaining.getOrDefault(key, 0)
            if (count > 0) remaining[key] = count - 1 else added += annotation
        }

        added.forEach { annotation ->
            val name = annotation.nameAsString
            val simple = name.substringAfterLast('.')
            val allowlisted = name in qualifierFqns || simple in qualifierSimple ||
                name.startsWith("org.checkerframework.")
            if (!allowlisted) {
                val line = annotation.range.map { it.begin.line }.orElse(null)
                val category = if (simple in suppressionNames) {
                    GuardrailViolationCategory.SUPPRESSION
                } else {
                    val parent = annotation.parentNode.orElse(null)
                    if (parent != null && parent !is Type) {
                        // Declaration-position annotations alter signatures, not types.
                        GuardrailViolationCategory.SIGNATURE_TAMPERING
                    } else {
                        GuardrailViolationCategory.EXECUTABLE_EDIT
                    }
                }
                violations += AstViolation(
                    category, relPath, line,
                    "Added annotation '$name' is not a synthesized lattice qualifier " +
                        "(parent: ${annotation.parentNode.map { it.javaClass.simpleName }.orElse("unknown")})"
                )
            }
        }
    }

    private fun stripForComparison(
        cu: CompilationUnit,
        qualifierSimple: Set<String>,
        qualifierFqns: Set<String>
    ): CompilationUnit {
        val copy = cu.clone()
        copy.imports.clear()
        copy.findAll(AnnotationExpr::class.java)
            .filter { isStrippable(it, qualifierSimple, qualifierFqns) }
            .forEach { it.remove() }
        return copy
    }

    private fun isStrippable(
        annotation: AnnotationExpr,
        qualifierSimple: Set<String>,
        qualifierFqns: Set<String>
    ): Boolean {
        val name = annotation.nameAsString
        val simple = name.substringAfterLast('.')
        // Only validated lattice TYPE_USE qualifiers (by simple or fully
        // qualified name) and Checker Framework annotations are stripped.
        // Arbitrary declaration annotations are intentionally left in place so
        // the structural comparison rejects them.
        return name in qualifierFqns || simple in qualifierSimple ||
            name.startsWith("org.checkerframework.")
    }

    internal fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()
}
