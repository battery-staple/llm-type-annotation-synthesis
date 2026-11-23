package pipeline.stages

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.MarkerAnnotationExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import pipeline.config.BuildSection
import pipeline.db.AgentTurnRecord
import pipeline.db.AnnotationPlacementRecord
import pipeline.db.LlmInvocationRecord
import pipeline.ports.AgentRunResult
import pipeline.util.Git
import pipeline.util.PipelineJson

/**
 * Every [BuildSection] field the build runner reads, for inclusion in a
 * stage cache key.
 *
 * Hashing only the "obvious" task is not enough: `runTests` compiles first and
 * derives `cleanTest` from `testTask`, `runChecker` honours `checkerTask`, and
 * every task is project-qualified by `subproject`. A stage that shells out to
 * the runner must key all of these or it can reuse a bundle produced by a
 * different build configuration.
 */
internal fun buildConfigSlice(build: BuildSection): Map<String, Any?> = mapOf(
    "build_tool" to build.tool,
    "gradle_wrapper" to build.wrapperExecutable,
    "build_subproject" to build.subproject,
    "compile_task" to build.compileTask,
    "test_task" to build.testTask,
    "checker_task" to build.checkerTask,
)

/**
 * Cache-key value for the retired `checker.quals` config key. It was always
 * `[]` and is no longer read: every Gradle invocation now takes its qualifiers
 * from the synthesized lattice. Pinning the old slot to its only value keeps
 * existing CAS keys (including LLM-stage bundles) valid.
 */
internal val RETIRED_CHECKER_QUALS_KEY: List<String> = emptyList()

/** Outcome-affecting wall-clock budget used by every Gradle invocation. */
internal fun buildTimeoutConfig(ctx: RunContext): Map<String, Any?> =
    mapOf("build_timeout_ms" to ctx.config.agent.timeoutMs)

object PromptTemplates {
    fun load(name: String): String {
        val resource = PromptTemplates::class.java.getResourceAsStream("/templates/prompts/$name")
            ?: error("Prompt template not found on classpath: templates/prompts/$name")
        return resource.bufferedReader().use { it.readText() }
    }

    fun render(template: String, values: Map<String, String>): String {
        var result = template
        values.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}

object TelemetryIds {
    fun id(): String = UUID.randomUUID().toString()
}

/**
 * Human-readable reason an agent invocation failed. The runner records CLI
 * stderr as a transcript MESSAGE on nonzero exit; surface it so a bad model ID
 * or auth failure is not reported as a missing output file.
 */
fun agentFailureMessage(result: AgentRunResult): String? {
    if (!result.timedOut && result.exitCode == 0) return null
    val messages = result.transcript.asReversed().filter {
        it.eventType == AgentEventType.MESSAGE && !it.content.isNullOrBlank()
    }
    val detail = messages.firstOrNull { it.content!!.startsWith("stderr:") }?.content
        ?: messages.firstOrNull()?.content
    return buildString {
        append(if (result.timedOut) "agent timed out" else "agent exited with code ${result.exitCode}")
        if (detail != null) append(": ").append(detail.take(2000))
    }
}

/**
 * A nonzero exit with no completed agent step means the CLI never started the
 * model loop (bad model name, auth failure, missing executable). Retrying the
 * same input cannot help, so stages halt instead of burning three attempts.
 */
fun agentStartupFailure(result: AgentRunResult): String? =
    agentFailureMessage(result)?.takeIf { !result.timedOut && result.stepsCounted == 0 }

/**
 * Agents are not OS-sandboxed: they can reach the benchmark checkout even when
 * their working directory is a scratch export. Detect and roll back any such
 * edit after every agent invocation; a contaminated benchmark invalidates the
 * sample and must not be allowed to continue.
 */
fun haltIfBenchmarkRepoDirty(ctx: RunContext, stage: String) {
    val repo = ctx.repoDir
    if (!repo.isDirectory) return
    BenchmarkCheckoutGuard.check(repo, ctx.contaminationScope, stage)
}

/** Serializes contamination detection/rollback and poisons a checkout for its run. */
internal object BenchmarkCheckoutGuard {
    private data class State(val lock: Any = Any(), var contaminated: Boolean = false)
    private val states = ConcurrentHashMap<String, State>()

    fun reset(repo: File, scope: String) {
        val key = key(repo, scope)
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state.lock) { state.contaminated = false }
    }

    fun check(repo: File, scope: String, stage: String) {
        val key = key(repo, scope)
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state.lock) {
            if (state.contaminated) throw StageHaltException("$stage stopped: benchmark checkout contamination was already detected")
            val status = try { Git.statusPorcelain(repo) } catch (t: Throwable) {
                state.contaminated = true
                throw StageHaltException("$stage could not verify benchmark checkout cleanliness: ${t.message}")
            }
            if (status.isBlank()) return
            state.contaminated = true
            runCatching { Git.run(repo, "checkout", "--", ".") }
            runCatching { Git.run(repo, "clean", "-fd") }
            throw StageHaltException(
                "$stage modified the benchmark repository; the checkout was rolled back. Status was:\n" + status.take(2000)
            )
        }
    }

    private fun key(repo: File, scope: String): String =
        "${runCatching { repo.canonicalPath }.getOrDefault(repo.absolutePath)}::$scope"
}

data class QualifierInfo(
    val fqcn: Fqcn,
    val packageName: String,
    val simpleName: String,
    val subtypesOf: List<String>,
    val isTop: Boolean,
    val isBottom: Boolean,
    val typeUse: Boolean,
    val targetType: String = "java.lang.Object"
)

object QualifierDiscovery {
    fun discover(sourceDir: File): List<QualifierInfo> {
        if (!sourceDir.isDirectory) return emptyList()
        val discovered = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .flatMap { file -> discoverInFile(file).asSequence() }
            .sortedBy { it.fqcn.value }
            .toList()
        val parentNames = discovered.flatMap { it.subtypesOf }.toSet()
        return discovered.map { info ->
            info.copy(isBottom = info.subtypesOf.isNotEmpty() && info.simpleName !in parentNames)
        }
    }

    private fun discoverInFile(file: File): List<QualifierInfo> {
        val cu: CompilationUnit = StaticJavaParser.parse(file)
        val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return cu.findAll(AnnotationDeclaration::class.java).mapNotNull { declaration ->
            val target = declaration.annotations.firstOrNull { it.nameAsString.endsWith("Target") }
            val typeUse = target?.toString()?.contains("TYPE_USE") == true
            val subtypeAnnotation = declaration.annotations.firstOrNull { it.nameAsString.endsWith("SubtypeOf") }
            val parents = subtypeAnnotation?.let { parseSubtypeOf(it) } ?: emptyList()
            val isTop = declaration.annotations.any { it.nameAsString.endsWith("DefaultQualifierInHierarchy") } ||
                (subtypeAnnotation != null && parents.isEmpty())
            val simple = declaration.nameAsString
            QualifierInfo(
                fqcn = Fqcn(if (pkg.isEmpty()) simple else "$pkg.$simple"),
                packageName = pkg,
                simpleName = simple,
                subtypesOf = parents,
                isTop = isTop,
                isBottom = false,
                typeUse = typeUse
            )
        }
    }

    private fun parseSubtypeOf(annotation: AnnotationExpr): List<String> {
        val text = when (annotation) {
            is SingleMemberAnnotationExpr -> annotation.memberValue.toString()
            is NormalAnnotationExpr -> annotation.pairs.firstOrNull { it.nameAsString == "value" }?.value?.toString() ?: ""
            is MarkerAnnotationExpr -> ""
            else -> ""
        }
        return Regex("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.class")
            .findAll(text)
            .map { it.groupValues[1].substringAfterLast('.') }
            .toList()
    }

    /** All TYPE_USE annotations, including the root/default qualifier, for -Aquals. */
    fun typeUseQualifiers(sourceDir: File): List<QualifierInfo> =
        discover(sourceDir).filter { it.typeUse }

    fun packagePrefixes(sourceDir: File): Set<String> =
        discover(sourceDir).filter { it.typeUse }.map { it.packageName }.filter { it.isNotBlank() }.toSet()
}

object QualifierValidator {
    /**
     * Level 0 invariant: every top-level type must be an annotation declaration
     * with no elements. Any class, interface, record, enum, or utility method is
     * rejected.
     */
    fun validatePureMarkerSources(sourceDir: File): List<String> {
        if (!sourceDir.isDirectory) return listOf("custom_quals_src/ is missing")
        val problems = mutableListOf<String>()
        val files = sourceDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        if (files.isEmpty()) problems += "custom_quals_src/ contains no Java sources"
        files.forEach { file ->
            val cu = runCatching { StaticJavaParser.parse(file) }.getOrElse {
                problems += "${file.name}: parse error: ${it.message}"
                return@forEach
            }
            if (cu.types.isEmpty()) problems += "${file.name}: contains no type declarations"
            cu.types.forEach { type ->
                if (type !is AnnotationDeclaration) {
                    problems += "${file.name}: top-level ${type.javaClass.simpleName} '${type.nameAsString}' is not an annotation declaration"
                } else {
                    if (type.members.isNotEmpty()) {
                        problems += "${file.name}: annotation '${type.nameAsString}' declares members; Level 0 requires pure markers"
                    }
                }
            }
        }
        return problems
    }

    fun helperCount(sourceDir: File): Int {
        if (!sourceDir.isDirectory) return 0
        return sourceDir.walkTopDown().filter { it.isFile && it.extension == "java" }.sumOf { file ->
            runCatching {
                StaticJavaParser.parse(file).types.count { it !is AnnotationDeclaration }
            }.getOrDefault(0)
        }
    }

    /**
     * Structural validation for a custom marker hierarchy. Compilation only
     * proves the annotations are legal Java; this proves they form the single
     * root / single bottom strict lattice the Level 0 contract requires.
     */
    fun validateTypeUseLattice(sourceDir: File): List<String> {
        if (!sourceDir.isDirectory) return listOf("custom_quals_src/ is missing")
        val all = QualifierDiscovery.discover(sourceDir)
        if (all.isEmpty()) return listOf("custom_quals_src/ contains no annotation declarations")

        val problems = mutableListOf<String>()
        all.filterNot { it.typeUse }.forEach {
            problems += "@${it.simpleName} is missing @Target(ElementType.TYPE_USE)"
        }
        val qualifiers = all.filter { it.typeUse }
        if (qualifiers.isEmpty()) return problems + "no TYPE_USE qualifiers discovered"

        val bySimpleName = qualifiers.groupBy { it.simpleName }
        bySimpleName.filterValues { it.size > 1 }.keys.forEach {
            problems += "duplicate qualifier simple name '@$it'; qualifier names must be unique"
        }
        val unique = qualifiers.filter { bySimpleName.getValue(it.simpleName).size == 1 }
        if (unique.isEmpty()) return problems

        unique.forEach { qualifier ->
            qualifier.subtypesOf.forEach { parent ->
                if (parent !in bySimpleName) {
                    problems += "@${qualifier.simpleName} declares @SubtypeOf parent '$parent' " +
                        "that is not a declared qualifier"
                }
            }
        }
        findCycle(unique, unique.associateBy { it.simpleName })?.let { cycle ->
            problems += "qualifier hierarchy contains a @SubtypeOf cycle: ${cycle.joinToString(" -> ")}"
        }

        val roots = unique.filter { it.isTop }
        when {
            roots.isEmpty() -> problems += "no root qualifier; exactly one qualifier must be " +
                "annotated @DefaultQualifierInHierarchy or @SubtypeOf({})"
            roots.size > 1 -> problems += "multiple root qualifiers: " +
                roots.joinToString(", ") { "@${it.simpleName}" } + "; exactly one is required"
        }
        roots.filter { it.subtypesOf.isNotEmpty() }.forEach {
            problems += "root qualifier @${it.simpleName} must not declare @SubtypeOf parents"
        }

        val parentNames = qualifiers.flatMap { it.subtypesOf }.toSet()
        val bottoms = unique.filter { it.subtypesOf.isNotEmpty() && it.simpleName !in parentNames }
        when {
            bottoms.isEmpty() -> problems += "no bottom qualifier; a strict hierarchy needs exactly one"
            bottoms.size > 1 -> problems += "multiple bottom qualifiers: " +
                bottoms.joinToString(", ") { "@${it.simpleName}" } + "; exactly one is required"
        }

        if (roots.size == 1) {
            val root = roots.single().simpleName
            val byName = unique.associateBy { it.simpleName }
            unique.forEach { qualifier ->
                if (!reachesRoot(qualifier, byName, root)) {
                    problems += "@${qualifier.simpleName} does not reach the root qualifier @$root"
                }
            }
        }
        return problems
    }

    private fun reachesRoot(
        start: QualifierInfo,
        byName: Map<String, QualifierInfo>,
        root: String,
    ): Boolean {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack += start.simpleName
        while (stack.isNotEmpty()) {
            val name = stack.removeLast()
            if (name == root) return true
            if (!seen.add(name)) continue
            byName[name]?.subtypesOf?.forEach { parent -> stack += parent }
        }
        return false
    }

    /** Returns one cycle as a child-to-parent path, or null when the graph is acyclic. */
    private fun findCycle(
        qualifiers: List<QualifierInfo>,
        byName: Map<String, QualifierInfo>,
    ): List<String>? {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun walk(name: String, path: MutableList<String>): List<String>? {
            if (name in visiting) {
                return path.dropWhile { it != name } + name
            }
            if (name in visited) return null
            visiting += name
            path += name
            byName[name]?.subtypesOf.orEmpty().forEach { parent ->
                if (parent in byName) {
                    walk(parent, path)?.let { return it }
                }
            }
            path.removeAt(path.lastIndex)
            visiting -= name
            visited += name
            return null
        }

        qualifiers.forEach { qualifier ->
            walk(qualifier.simpleName, mutableListOf())?.let { return it }
        }
        return null
    }
}

object PlacementGuideParser {
    fun parse(text: String): DeclarativePlacementGuide {
        val rules = mutableListOf<PlacementRule>()
        var current: RuleCategory? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#")) {
                current = when {
                    line.contains("semantics", ignoreCase = true) -> RuleCategory.SEMANTICS
                    line.contains("default", ignoreCase = true) -> RuleCategory.DEFAULT
                    line.contains("source", ignoreCase = true) || line.contains("introducer", ignoreCase = true) -> RuleCategory.SOURCE
                    line.contains("sink", ignoreCase = true) || line.contains("consumer", ignoreCase = true) -> RuleCategory.SINK
                    line.contains("sanitiz", ignoreCase = true) || line.contains("coercion", ignoreCase = true) -> RuleCategory.SANITIZER
                    else -> null
                }
            } else if (current != null && (line.startsWith("- ") || line.startsWith("* "))) {
                val ruleText = line.removePrefix("- ").removePrefix("* ").trim()
                if (ruleText.isNotEmpty()) rules += PlacementRule(current!!, ruleText)
            }
        }
        return DeclarativePlacementGuide(rules)
    }

    fun validateStructure(guide: DeclarativePlacementGuide): List<String> {
        val problems = mutableListOf<String>()
        RuleCategory.entries.forEach { category ->
            if (guide.rules.none { it.category == category }) {
                problems += "placement guide has no rules in category '${category.name.lowercase()}'"
            }
        }
        return problems
    }
}

data class ScannedPlacement(
    val filePath: String,
    val lineNumber: Int,
    val symbol: String,
    val qualifier: String
)

object AnnotationScanner {
    fun scan(root: File, qualifierSimpleNames: Set<String>): List<ScannedPlacement> {
        if (qualifierSimpleNames.isEmpty()) return emptyList()
        val results = mutableListOf<ScannedPlacement>()
        root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
            val cu = runCatching { StaticJavaParser.parse(file) }.getOrNull() ?: return@forEach
            cu.findAll(AnnotationExpr::class.java).forEach { annotation ->
                val name = annotation.nameAsString.substringAfterLast('.')
                if (name !in qualifierSimpleNames) return@forEach
                val parent = annotation.parentNode.orElse(null) ?: return@forEach
                val symbol = when {
                    parent is com.github.javaparser.ast.body.Parameter ->
                        "parameter:${parent.nameAsString}"
                    parent is com.github.javaparser.ast.body.VariableDeclarator ->
                        "variable:${parent.nameAsString}"
                    parent is com.github.javaparser.ast.body.FieldDeclaration ->
                        "field"
                    parent is com.github.javaparser.ast.body.MethodDeclaration ->
                        "return:${parent.nameAsString}"
                    else -> parent.javaClass.simpleName
                }
                val line = annotation.range.map { it.begin.line }.orElse(0)
                val relative = file.relativeTo(root).invariantSeparatorsPath
                results += ScannedPlacement(relative, line, symbol, "@$name")
            }
        }
        return results
    }
}

object AgentTelemetry {
    fun turns(ctx: RunContext, stage: StageName, transcript: List<AgentTranscriptEvent>): List<AgentTurnRecord> =
        transcript.filter { it.eventType == AgentEventType.TOOL_CALL || it.eventType == AgentEventType.MESSAGE }
            .map { event ->
                AgentTurnRecord(
                    turnId = TelemetryIds.id(),
                    runId = ctx.runId,
                    stepId = ctx.stepId,
                    stage = stage.identifier,
                    turnIndex = event.turnIndex,
                    actionType = if (event.eventType == AgentEventType.TOOL_CALL) "tool_call" else "message",
                    toolName = event.toolName,
                    toolArguments = event.toolArguments?.let {
                        kotlinx.serialization.json.JsonObject(it).toString()
                    },
                    toolResultSummary = event.toolResult?.take(4000)
                )
            }

    fun llmInvocations(
        ctx: RunContext,
        stage: StageName,
        transcript: List<AgentTranscriptEvent>,
        model: String,
        latencyMs: Long
    ): List<LlmInvocationRecord> {
        val finishes = transcript.filter { it.eventType == AgentEventType.STEP_FINISH && it.tokenUsage != null }
        if (finishes.isEmpty()) return emptyList()
        return finishes.map { event ->
            val usage = event.tokenUsage!!
            LlmInvocationRecord(
                callId = TelemetryIds.id(),
                runId = ctx.runId,
                stepId = ctx.stepId,
                repoId = null,
                stage = stage.identifier,
                turnIndex = event.turnIndex,
                modelName = model,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens,
                latencyMs = latencyMs
            )
        }
    }
}

fun annotationsOn(node: NodeWithAnnotations<*>): Set<String> =
    node.annotations.map { it.nameAsString.substringAfterLast('.') }.toSet()

fun formatCheckerErrors(diagnosticsLog: File): String = if (diagnosticsLog.isFile) diagnosticsLog.readText() else ""
