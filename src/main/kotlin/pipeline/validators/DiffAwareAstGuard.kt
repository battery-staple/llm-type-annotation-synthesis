package pipeline.validators

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.CompactConstructorDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.AssertStmt
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.type.TypeParameter
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pipeline.cas.ChangedPath
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.LocalAnnotationPolicy
import pipeline.util.Git

data class RepairViolation(
    val category: GuardrailViolationCategory,
    val guardType: String,
    val filePath: String?,
    val line: Int?,
    val message: String
)

data class RepairAuditResult(
    val valid: Boolean,
    val violations: List<RepairViolation>,
    val auditJson: String
)

/**
 * Repair-stage anti-cheat guard. Compares the modified tree against the
 * annotated baseline referenced by [baselineRef] (the repair worktree HEAD:
 * clean C-1 plus `annotations.patch`), never against clean C-1.
 *
 * Formatting, whitespace, and import ordering differences are ignored.
 */
object DiffAwareAstGuard {

    private val suppressionNames = setOf("SuppressWarnings", "AssumeAssertion")
    private val buildScriptPatterns = listOf(
        Regex("(^|/)build\\.gradle[^/]*$"),
        Regex("(^|/)settings\\.gradle[^/]*$"),
        Regex("(^|/)pom\\.xml$"),
        Regex("(^|/)gradle\\.properties$")
    )
    private val astubPattern = Regex(".*\\.astub$")

    private fun ensureLanguageLevel() {
        runCatching {
            StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        }
    }

    /** Legacy entry point: validates against the default `src/main` / `src/test` roots. */
    fun validate(
        worktree: File,
        changedPaths: List<ChangedPath>,
        baselineRef: String,
        localAnnotationPolicy: LocalAnnotationPolicy
    ): RepairAuditResult = validate(
        worktree, changedPaths, baselineRef, localAnnotationPolicy,
        SourceRoots.DEFAULT_PRODUCTION_ROOTS, SourceRoots.DEFAULT_TEST_ROOTS
    )

    fun validate(
        worktree: File,
        changedPaths: List<ChangedPath>,
        baselineRef: String,
        localAnnotationPolicy: LocalAnnotationPolicy,
        productionRoots: List<String>,
        testRoots: List<String>
    ): RepairAuditResult {
        ensureLanguageLevel()
        val violations = mutableListOf<RepairViolation>()
        val filesChecked = mutableListOf<String>()

        for (cp in changedPaths) {
            val relPath = cp.path.replace('\\', '/')
            filesChecked += relPath
            val file = File(worktree, cp.path)

            // Guard 6 (filesystem surface): test sources are read-only in
            // repair, package-info is frozen, and only in-place modifications
            // under the production roots are admissible. The full path audit
            // remains FilesystemGuard's job; these violations feed the same
            // audit log.
            if (FilesystemGuard.isPackageInfo(relPath)) {
                violations += RepairViolation(
                    GuardrailViolationCategory.PACKAGE_INFO_CHANGE, "filesystem_guard",
                    relPath, null, "package-info.java is frozen in repair: $relPath"
                )
                continue
            }
            if (SourceRoots.isTestPath(relPath, testRoots)) {
                violations += RepairViolation(
                    GuardrailViolationCategory.TEST_TAMPERING, "filesystem_guard",
                    relPath, null, "Test sources are read-only in repair: $relPath"
                )
                continue
            }
            if (buildScriptPatterns.any { it.containsMatchIn(relPath) } || astubPattern.matches(relPath)) {
                violations += RepairViolation(
                    GuardrailViolationCategory.BUILD_TAMPERING, "filesystem_guard",
                    relPath, null, "Build artifact is not editable in repair: $relPath"
                )
                continue
            }
            if (!SourceRoots.isUnderRoots(relPath, productionRoots)) {
                violations += RepairViolation(
                    GuardrailViolationCategory.FILESYSTEM, "filesystem_guard",
                    relPath, null, "Repair edits are confined to production roots (${SourceRoots.display(productionRoots)}): $relPath"
                )
                continue
            }
            if (cp.isDeletion) {
                violations += RepairViolation(
                    GuardrailViolationCategory.FILESYSTEM, "filesystem_guard",
                    relPath, null, "File deletions are rejected in repair: $relPath"
                )
                continue
            }
            if (cp.isRename) {
                violations += RepairViolation(
                    GuardrailViolationCategory.FILESYSTEM, "filesystem_guard",
                    relPath, null, "File renames are rejected in repair: $relPath"
                )
                continue
            }

            val baselineText = readBaseline(worktree, baselineRef, relPath)
            if (baselineText == null) {
                violations += RepairViolation(
                    GuardrailViolationCategory.FILE_ADDITION, "filesystem_guard",
                    relPath, null, "File additions are rejected in repair (no annotated baseline): $relPath"
                )
                continue
            }
            if (!file.isFile) {
                violations += RepairViolation(
                    GuardrailViolationCategory.FILESYSTEM, "filesystem_guard",
                    relPath, null, "Modified file is missing from the worktree: $relPath"
                )
                continue
            }

            val baseline = parse(baselineText, relPath, violations, "annotated baseline") ?: continue
            val modified = parse(file.readText(), relPath, violations, "modified tree") ?: continue

            guardDeclarationAndSuppressionLock(baseline, modified, relPath, violations)
            guardAssumeAssertion(baseline, modified, relPath, violations)
            guardUtilityBypass(baseline, modified, relPath, violations)
            guardDeclarationDigest(baseline, modified, relPath, violations)
            if (localAnnotationPolicy == LocalAnnotationPolicy.FROZEN) {
                guardLocalAnnotations(baseline, modified, relPath, violations)
            }
        }

        val categories = violations.map { it.category.name }.distinct().sorted()
        val auditJson = buildJsonObject {
            put("valid", violations.isEmpty())
            put("baseline_ref", JsonPrimitive(baselineRef))
            put("local_annotation_policy", JsonPrimitive(localAnnotationPolicy.name.lowercase()))
            put("violation_count", violations.size)
            put("categories", buildJsonArray { categories.forEach { add(JsonPrimitive(it)) } })
            put("files_checked", buildJsonArray { filesChecked.forEach { add(JsonPrimitive(it)) } })
            put("violations", buildJsonArray {
                violations.forEach { v ->
                    add(buildJsonObject {
                        put("category", JsonPrimitive(v.category.name.lowercase()))
                        put("guard_type", JsonPrimitive(v.guardType))
                        v.filePath?.let { put("file", JsonPrimitive(it)) }
                        v.line?.let { put("line", it) }
                        put("message", JsonPrimitive(v.message))
                    })
                }
            })
        }.toString()

        return RepairAuditResult(violations.isEmpty(), violations, auditJson)
    }

    // --- baseline / parsing ---

    private fun readBaseline(worktree: File, baselineRef: String, relPath: String): String? {
        val result = Git.run(worktree, "show", "$baselineRef:$relPath")
        if (result.exitCode != 0) return null
        return result.stdout
    }

    private fun parse(
        text: String,
        relPath: String,
        violations: MutableList<RepairViolation>,
        which: String
    ): CompilationUnit? {
        return try {
            StaticJavaParser.parse(text)
        } catch (e: Exception) {
            violations += RepairViolation(
                GuardrailViolationCategory.SIGNATURE_TAMPERING, "signature_guard",
                relPath, null, "File in $which does not parse: ${e.message?.take(300)}"
            )
            null
        }
    }

    // --- Guard 1: declaration & suppression lock ---

    private fun guardDeclarationAndSuppressionLock(
        baseline: CompilationUnit,
        modified: CompilationUnit,
        relPath: String,
        violations: MutableList<RepairViolation>
    ) {
        val base = declarationAnnotations(baseline)
        val mod = declarationAnnotations(modified)
        for (key in base.keys.intersect(mod.keys)) {
            val removed = multisetMinus(base.getValue(key), mod.getValue(key))
            val added = multisetMinus(mod.getValue(key), base.getValue(key))
            (removed.map { it to "removed" } + added.map { it to "added" }).forEach { (annotation, direction) ->
                val simple = annotation.substringAfterLast('.')
                    .substringBefore('(').trim()
                val isSuppression = simple in suppressionNames ||
                    annotation.contains("SuppressWarnings") ||
                    annotation.contains("AssumeAssertion")
                val category = if (isSuppression) {
                    GuardrailViolationCategory.SUPPRESSION
                } else {
                    GuardrailViolationCategory.SIGNATURE_TAMPERING
                }
                val guardType = if (isSuppression) "suppression_guard" else "signature_guard"
                violations += RepairViolation(
                    category, guardType, relPath, null,
                    "Declaration annotation $direction on $key: $annotation"
                )
            }
        }
    }

    /**
     * Maps each declaration identity to the multiset of its normalized
     * annotation spellings. Covers types, methods, constructors, fields, enum
     * constants, parameters (including record components, which JavaParser
     * models as [Parameter]), and type parameters.
     */
    private fun declarationAnnotations(cu: CompilationUnit): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        fun add(key: String, annotations: List<AnnotationExpr>) {
            result.getOrPut(key) { mutableListOf() } += annotations.map { normalize(it.toString()) }
        }

        val typePath = mutableMapOf<Node, String>()
        fun pathOf(type: TypeDeclaration<*>): String {
            return typePath.getOrPut(type) {
                val parent = generateSequence(type.parentNode.orElse(null)) { it.parentNode.orElse(null) }
                    .filterIsInstance<TypeDeclaration<*>>().firstOrNull()
                val prefix = if (parent != null) pathOf(parent) + "." else ""
                prefix + type.nameAsString
            }
        }

        cu.findAll(TypeDeclaration::class.java).forEach { type ->
            @Suppress("UNCHECKED_CAST")
            add("type:${pathOf(type as TypeDeclaration<*>)}:${kindOf(type)}", type.annotations)
        }
        cu.findAll(EnumConstantDeclaration::class.java).forEach { constant ->
            val owner = constant.parentNode.orElse(null)
            val ownerName = (owner as? TypeDeclaration<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                pathOf(it as TypeDeclaration<*>)
            } ?: "<unknown>"
            add("enumconst:$ownerName:${constant.nameAsString}", constant.annotations)
        }
        cu.findAll(MethodDeclaration::class.java).forEach { method ->
            add("method:${ownerPath(method)}:${method.nameAsString}${paramTypes(method)}", method.annotations)
        }
        cu.findAll(ConstructorDeclaration::class.java).forEach { ctor ->
            add("ctor:${ownerPath(ctor)}:${ctor.nameAsString}${paramTypes(ctor)}", ctor.annotations)
        }
        cu.findAll(CompactConstructorDeclaration::class.java).forEach { ctor ->
            add("ctor:${ownerPath(ctor)}:${ctor.nameAsString}()", ctor.annotations)
        }
        cu.findAll(FieldDeclaration::class.java).forEach { field ->
            field.variables.forEach { variable ->
                add("field:${ownerPath(field)}:${variable.nameAsString}", field.annotations)
            }
        }
        cu.findAll(Parameter::class.java).forEach { parameter ->
            // Record components are modelled as Parameter nodes; the owner key
            // below covers both cases.
            add("param:${parameterOwnerKey(parameter)}:${parameter.nameAsString}", parameter.annotations)
        }
        cu.findAll(TypeParameter::class.java).forEach { typeParam ->
            add("typeparam:${typeParameterOwnerKey(typeParam)}:${typeParam.nameAsString}", typeParam.annotations)
        }
        return result
    }

    private fun kindOf(type: TypeDeclaration<*>): String = when (type) {
        is EnumDeclaration -> "enum"
        is com.github.javaparser.ast.body.AnnotationDeclaration -> "annotation"
        is com.github.javaparser.ast.body.RecordDeclaration -> "record"
        is ClassOrInterfaceDeclaration -> if (type.isInterface) "interface" else "class"
        else -> "type"
    }

    private fun ownerPath(node: Node): String {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is TypeDeclaration<*>) {
                @Suppress("UNCHECKED_CAST")
                return qualifiedPath(current as TypeDeclaration<*>)
            }
            if (current is EnumConstantDeclaration) return "enumconst:${current.nameAsString}"
            current = current.parentNode.orElse(null)
        }
        return "<top>"
    }

    private fun qualifiedPath(type: TypeDeclaration<*>): String {
        val parent = generateSequence(type.parentNode.orElse(null)) { it.parentNode.orElse(null) }
            .filterIsInstance<TypeDeclaration<*>>().firstOrNull()
        @Suppress("UNCHECKED_CAST")
        val prefix = if (parent != null) qualifiedPath(parent as TypeDeclaration<*>) + "." else ""
        return prefix + type.nameAsString
    }

    private fun paramTypes(callable: CallableDeclaration<*>): String =
        callable.parameters.joinToString(",", "(", ")") { normalize(it.type.asString()) }

    private fun parameterOwnerKey(parameter: Parameter): String {
        val parent = parameter.parentNode.orElse(null)
        return when (parent) {
            is MethodDeclaration -> "method:${ownerPath(parent)}:${parent.nameAsString}${paramTypes(parent)}"
            is ConstructorDeclaration -> "ctor:${ownerPath(parent)}:${parent.nameAsString}${paramTypes(parent)}"
            is CompactConstructorDeclaration -> "ctor:${ownerPath(parent)}:${parent.nameAsString}()"
            is com.github.javaparser.ast.body.RecordDeclaration ->
                "record:${qualifiedPath(parent as TypeDeclaration<*>)}"
            else -> "params:${ownerPath(parameter)}"
        }
    }

    private fun typeParameterOwnerKey(typeParam: TypeParameter): String {
        val parent = typeParam.parentNode.orElse(null)
        return when (parent) {
            is MethodDeclaration -> "method:${ownerPath(parent)}:${parent.nameAsString}${paramTypes(parent)}"
            is ConstructorDeclaration -> "ctor:${ownerPath(parent)}:${parent.nameAsString}${paramTypes(parent)}"
            is TypeDeclaration<*> -> {
                @Suppress("UNCHECKED_CAST")
                "type:${qualifiedPath(parent as TypeDeclaration<*>)}"
            }
            else -> "typeparams:${ownerPath(typeParam)}"
        }
    }

    // --- Guard 2: @AssumeAssertion backdoor ban ---

    private fun guardAssumeAssertion(
        baseline: CompilationUnit,
        modified: CompilationUnit,
        relPath: String,
        violations: MutableList<RepairViolation>
    ) {
        fun count(cu: CompilationUnit): Int =
            cu.findAll(AssertStmt::class.java).count { it.toString().contains("AssumeAssertion") }
        if (count(modified) > count(baseline)) {
            violations += RepairViolation(
                GuardrailViolationCategory.BACKDOOR, "backdoor_guard",
                relPath, null,
                "New @AssumeAssertion token in an assert statement (baseline had fewer)"
            )
        }
    }

    // --- Guard 3: utility bypass ban ---

    private fun guardUtilityBypass(
        baseline: CompilationUnit,
        modified: CompilationUnit,
        relPath: String,
        violations: MutableList<RepairViolation>
    ) {
        fun riskyCalls(cu: CompilationUnit): List<String> {
            val calls = cu.findAll(MethodCallExpr::class.java)
                .filter { isRiskyCall(it) }.map { normalize(it.toString()) }
            val refs = cu.findAll(MethodReferenceExpr::class.java)
                .filter { isRiskyReference(it) }.map { normalize(it.toString()) }
            return calls + refs
        }
        val added = multisetMinus(riskyCalls(modified), riskyCalls(baseline))
        added.forEach { call ->
            violations += RepairViolation(
                GuardrailViolationCategory.CAST, "cast_guard",
                relPath, null, "Added utility-bypass call or reference: $call"
            )
        }
    }

    private fun isRiskyCall(call: MethodCallExpr): Boolean {
        if (call.nameAsString == "castNonNull") return true
        val scope = scopeSimpleName(call.scope.orElse(null))
        return scope == "NullnessUtil"
    }

    private fun isRiskyReference(ref: MethodReferenceExpr): Boolean {
        if (ref.identifier == "castNonNull") return true
        return scopeSimpleName(ref.scope) == "NullnessUtil"
    }

    private fun scopeSimpleName(scope: Expression?): String? {
        if (scope == null) return null
        return scope.toString().split('.').lastOrNull()?.substringBefore('<')?.trim()
    }

    // --- Guard 4: full declaration lock ---

    private fun guardDeclarationDigest(
        baseline: CompilationUnit,
        modified: CompilationUnit,
        relPath: String,
        violations: MutableList<RepairViolation>
    ) {
        val baseDigest = declarationDigest(baseline)
        val modDigest = declarationDigest(modified)
        if (baseDigest != modDigest) {
            violations += RepairViolation(
                GuardrailViolationCategory.SIGNATURE_TAMPERING, "signature_guard",
                relPath, null,
                "Declaration digest differs from the annotated baseline " +
                    "(header, signature, throws, type-parameter, field-initializer, " +
                    "or static/instance-initializer change)"
            )
        }
    }

    /**
     * Normalized declaration digest. Method and constructor bodies are removed;
     * field initializers and static/instance initializer blocks are kept.
     * Imports are stripped so import edits and ordering never affect the
     * digest. Anonymous class headers remain (their method bodies are removed
     * like any other).
     */
    internal fun declarationDigest(cu: CompilationUnit): String {
        val copy = cu.clone()
        copy.imports.clear()
        copy.findAll(MethodDeclaration::class.java).forEach { method ->
            if (method.body.isPresent) method.setBody(BlockStmt())
        }
        copy.findAll(ConstructorDeclaration::class.java).forEach { ctor ->
            ctor.setBody(BlockStmt())
        }
        copy.findAll(CompactConstructorDeclaration::class.java).forEach { ctor ->
            ctor.setBody(BlockStmt())
        }
        return normalize(copy.toString())
    }

    // --- Guard 5: local-variable policy ---

    private fun guardLocalAnnotations(
        baseline: CompilationUnit,
        modified: CompilationUnit,
        relPath: String,
        violations: MutableList<RepairViolation>
    ) {
        val base = localAnnotationsByOwner(baseline)
        val mod = localAnnotationsByOwner(modified)
        for (key in base.keys.union(mod.keys)) {
            val baseLocals = base[key] ?: emptyList()
            val modLocals = mod[key] ?: emptyList()
            if (baseLocals != modLocals) {
                violations += RepairViolation(
                    GuardrailViolationCategory.LOCAL_ANNOTATION, "local_guard",
                    relPath, null,
                    "Local-variable annotation change under frozen policy in $key"
                )
            }
        }
    }

    /**
     * Ordered per-owner multiset of normalized local-variable annotation sets.
     * A local is a [VariableDeclarationExpr] (statement position); field
     * declarators (parented to [FieldDeclaration]) are excluded.
     */
    private fun localAnnotationsByOwner(cu: CompilationUnit): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        cu.findAll(VariableDeclarationExpr::class.java).forEach { declaration ->
            val owner = enclosingExecutableKey(declaration) ?: return@forEach
            val annotations = declaration.annotations.map { normalize(it.toString()) }.sorted()
            declaration.variables.forEach { _ ->
                result.getOrPut(owner) { mutableListOf() } += annotations.joinToString("|")
            }
        }
        return result
    }

    private fun enclosingExecutableKey(node: Node): String? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is MethodDeclaration ->
                    return "method:${ownerPath(current)}:${current.nameAsString}${paramTypes(current)}"
                is ConstructorDeclaration ->
                    return "ctor:${ownerPath(current)}:${current.nameAsString}${paramTypes(current)}"
                is CompactConstructorDeclaration ->
                    return "ctor:${ownerPath(current)}:${current.nameAsString}()"
                is InitializerDeclaration -> {
                    val parent = current.parentNode.orElse(null)
                    val owner = if (parent is TypeDeclaration<*>) {
                        @Suppress("UNCHECKED_CAST")
                        qualifiedPath(parent as TypeDeclaration<*>)
                    } else "<top>"
                    val siblings = (parent as? TypeDeclaration<*>)?.members
                        ?.filterIsInstance<InitializerDeclaration>()?.indexOf(current) ?: 0
                    return "init:$owner#$siblings"
                }
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    // --- small utilities ---

    private fun multisetMinus(first: List<String>, second: List<String>): List<String> {
        val remaining = second.groupingBy { it }.eachCount().toMutableMap()
        val diff = mutableListOf<String>()
        first.forEach { item ->
            val count = remaining.getOrDefault(item, 0)
            if (count > 0) remaining[item] = count - 1 else diff += item
        }
        return diff
    }

    internal fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()
}
