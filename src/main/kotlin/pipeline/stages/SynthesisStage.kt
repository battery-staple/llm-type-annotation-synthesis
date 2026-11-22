package pipeline.stages

import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pipeline.cas.ArtifactNames
import pipeline.cas.CacheKeys
import pipeline.cas.CasEngine
import pipeline.cas.StageArtifactWriter
import pipeline.cas.createJarFromDirectory
import pipeline.db.CompilationEventRecord
import pipeline.db.QualifierRecord
import pipeline.db.SynthesisRunRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.AgentRunner
import pipeline.ports.JavacCompiler
import pipeline.util.FileOps
import pipeline.util.Hashing
import pipeline.util.PipelineJson

private val logger = KotlinLogging.logger {}

/**
 * Type lattice synthesis stage (Level 0: pure marker qualifier hierarchy, zero helpers).
 *
 * Depends on [pipeline.validators] only through the shared [StageSupport] helpers
 * ([QualifierValidator], [QualifierDiscovery], [PlacementGuideParser]); it does not
 * call the AST guards. The checker/Agent harnesses are injected through ports.
 */
class SynthesisStage(
    private val agentRunner: AgentRunner,
    private val javac: JavacCompiler,
) : PipelineStage<SynthesisInput, SynthesizedLattice> {

    override val stageName: StageName = StageName.SYNTHESIS

    override fun computeCacheKey(ctx: RunContext, input: SynthesisInput): CasKey {
        val bug = input.bugContext
        val template = runCatching { PromptTemplates.load("synthesis.md") }.getOrDefault("")
        val stageInput = mapOf(
            "benchmark_id" to bug.benchmarkId.canonical,
            "commit" to bug.commitHash.toString(),
            "parent" to bug.parentHash.toString(),
            "commit_diff_sha" to Hashing.sha256(bug.commitDiff),
            "description_sha" to Hashing.sha256(bug.bugDescription),
        )
        val configSlice = mapOf(
            "freedom_level" to freedomLevelName(ctx.config.synthesis.freedomLevel),
            "allowed_checkers" to allowedCheckersName(ctx.config.synthesis.allowedCheckers),
            "model_synthesis" to ctx.config.model.synthesisModel,
            "checker_processor" to ctx.config.checker.processor,
            "checker_category" to checkerCategoryName(ctx.config.checker.category),
            "checker_quals" to ctx.config.checker.quals,
            "agent_steps_synthesis" to ctx.config.agent.steps.synthesis,
            "agent_timeout_ms" to ctx.config.agent.timeoutMs,
            "internet_access" to ctx.config.repair.internetAccess,
        )
        return CacheKeys.compute(ctx, stageName, stageInput, emptyList(), configSlice, template, includeSampleIdentity = !isDeterministic)
    }

    override suspend fun execute(ctx: RunContext, input: SynthesisInput): StageResult<SynthesizedLattice> {
        val startedMs = System.currentTimeMillis()
        val key = computeCacheKey(ctx, input)
        val template = runCatching { PromptTemplates.load("synthesis.md") }.getOrElse {
            return StageResult.TransientFailure(
                RuntimeException("synthesis prompt template missing: ${it.message}", it),
                0,
                emptyList(),
            )
        }
        val stagingDir = CasEngine(ctx.casDir).createStagingDir(stageName)
        val writer = StageArtifactWriter(
            stageName,
            stagingDir,
            sampleIndex = ctx.sampleIndex,
            sampleTemperature = ctx.sampleTemperature,
        )

        val workspace = ctx.agentWorkspaceDir.resolve("synthesis_${ctx.runId}")
        FileOps.deleteRecursively(workspace)
        workspace.mkdirs()
        val sourcesWorkspace = workspace.resolve(ArtifactNames.CUSTOM_QUALS_SRC_DIR)
        sourcesWorkspace.mkdirs()
        val agentLibs = workspace.resolve(".agent-libs")
        Files.createDirectories(agentLibs.toPath())
        val checkerQualJar = File(ctx.config.environment.checkerFrameworkHome, "checker/dist/checker-qual.jar")
        if (checkerQualJar.isFile) {
            Files.copy(
                checkerQualJar.toPath(),
                agentLibs.resolve("checker-qual.jar").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
        } else {
            logger.warn { "checker-qual.jar not found at ${checkerQualJar.absolutePath}; agent compiles without it on the classpath" }
        }
        val environment = buildMap {
            if (checkerQualJar.isFile) put("CHECKER_QUAL_JAR", agentLibs.resolve("checker-qual.jar").absolutePath)
            if (!ctx.config.repair.internetAccess) put("PIPELINE_AGENT_NO_NETWORK", "1")
        }

        val bug = input.bugContext
        val basePrompt = PromptTemplates.render(
            template,
            mapOf(
                "benchmark_id" to bug.benchmarkId.canonical,
                "commit_hash" to bug.commitHash.toString(),
                "parent_hash" to bug.parentHash.toString(),
                "description" to bug.bugDescription,
                "commit_diff" to bug.commitDiff,
                "freedom_level" to freedomLevelName(ctx.config.synthesis.freedomLevel),
                "allowed_checkers" to allowedCheckersName(ctx.config.synthesis.allowedCheckers),
            ),
        )

        val fullTranscript = mutableListOf<AgentTranscriptEvent>()
        val compilationEvents = mutableListOf<CompilationEventRecord>()
        var stepsTaken = 0
        var attempt = 0
        var prompt = basePrompt
        var lastProblems: List<String> = listOf("synthesis agent has not run yet")
        var accepted = false
        val timeoutSeconds = ctx.config.agent.timeoutMs / 1000
        while (attempt < MAX_ATTEMPTS && !accepted) {
            attempt++
            val runResult = agentRunner.runAgent(
                prompt,
                workspace,
                "synthesis-agent",
                ctx.config.model.synthesisModel,
                ctx.config.agent.steps.synthesis,
                timeoutSeconds,
                ctx.sampleIndex.toLong(),
                ctx.sampleTemperature,
                environment,
            )
            stepsTaken += runResult.stepsCounted
            fullTranscript += runResult.transcript
            agentStartupFailure(runResult)?.let { throw StageHaltException(it) }
            haltIfBenchmarkRepoDirty(ctx, "synthesis")
            val problems = buildList {
                agentFailureMessage(runResult)?.let { add(it) }
                addAll(
                    validateAttempt(
                        workspace,
                        sourcesWorkspace,
                        checkerQualJar,
                        stagingDir,
                        attempt,
                        ctx,
                        compilationEvents
                    )
                )
            }
            if (problems.isEmpty()) {
                accepted = true
                lastProblems = emptyList()
            } else {
                lastProblems = problems
                logger.warn { "synthesis attempt $attempt failed validation: ${problems.joinToString("; ")}" }
                prompt = basePrompt +
                    "\n\n## Corrective feedback (attempt $attempt failed validation; workspace files persist, fix them in place):\n" +
                    problems.joinToString("\n") { "- $it" }
            }
        }
        if (!accepted) {
            return StageResult.TransientFailure(
                RuntimeException("synthesis failed after $attempt attempts: ${lastProblems.joinToString("; ")}"),
                stepsTaken,
                fullTranscript.toList(),
            )
        }

        val discovered = QualifierDiscovery.typeUseQualifiers(sourcesWorkspace)
        val builtinAllowed = ctx.config.synthesis.allowedCheckers == AllowedCheckers.ALL ||
            ctx.config.synthesis.allowedCheckers == AllowedCheckers.BUILTIN_ONLY
        // If custom qualifiers were found but only builtin checkers are allowed, the
        // custom hierarchy is ignored and the builtin path is taken.
        val processor: String
        val category: CheckerCategory
        val qualifierNames: List<Fqcn>
        if (discovered.isNotEmpty() && ctx.config.synthesis.allowedCheckers != AllowedCheckers.BUILTIN_ONLY) {
            processor = ctx.config.checker.processor
            category = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY
            qualifierNames = discovered.map { it.fqcn }
        } else if (builtinAllowed) {
            processor = BUILTIN_PROCESSOR
            category = CheckerCategory.BUILTIN_CHECKER
            qualifierNames = emptyList()
            val stagingJar = stagingDir.resolve(ArtifactNames.CUSTOM_QUALS_JAR)
            val emptySrc = workspace.resolve(".empty-quals-src")
            emptySrc.mkdirs()
            createJarFromDirectory(emptySrc, stagingJar)
        } else {
            return StageResult.TransientFailure(
                RuntimeException("no TYPE_USE qualifiers discovered and synthesis.allowed_checkers forbids the builtin fallback"),
                stepsTaken,
                fullTranscript.toList(),
            )
        }

        val guideFile = workspace.resolve(ArtifactNames.PLACEMENT_GUIDE)
        val guide = PlacementGuideParser.parse(guideFile.readText())

        val stagingJar = stagingDir.resolve(ArtifactNames.CUSTOM_QUALS_JAR)
        val stagedSrc = stagingDir.resolve(ArtifactNames.CUSTOM_QUALS_SRC_DIR)
        if (sourcesWorkspace.isDirectory && sourcesWorkspace.walkTopDown().any { it.isFile }) {
            FileOps.copyTree(sourcesWorkspace, stagedSrc)
        } else {
            stagedSrc.mkdirs()
        }
        writer.registerDirectory(ArtifactNames.CUSTOM_QUALS_SRC_DIR)
        val stagedGuide = writer.copyIn(ArtifactNames.PLACEMENT_GUIDE, guideFile)
        writer.register(ArtifactNames.CUSTOM_QUALS_JAR, stagingJar)

        val durationMs = System.currentTimeMillis() - startedMs
        val (promptTokens, completionTokens, totalTokens) = tokenSums(fullTranscript)
        val transcriptFile = writer.writeText(
            ArtifactNames.SYNTHESIS_TRANSCRIPT,
            PipelineJson.json.encodeToString(ListSerializer(AgentTranscriptEvent.serializer()), fullTranscript),
        )
        val metricsFile = writer.writeText(
            ArtifactNames.SYNTHESIS_METRICS,
            PipelineJson.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("attempts", JsonPrimitive(attempt))
                    put("duration_ms", JsonPrimitive(durationMs))
                    put("prompt_tokens", JsonPrimitive(promptTokens))
                    put("completion_tokens", JsonPrimitive(completionTokens))
                    put("total_tokens", JsonPrimitive(totalTokens))
                    put("turns", JsonPrimitive(fullTranscript.maxOfOrNull { it.turnIndex } ?: 0))
                    put("agent_steps", JsonPrimitive(stepsTaken))
                    put("qualifiers_count", JsonPrimitive(qualifierNames.size))
                    put("helpers_count", JsonPrimitive(0))
                },
            ),
        )
        writer.writeText(
            SYNTHESIS_METADATA,
            PipelineJson.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("processor", JsonPrimitive(processor))
                    put("checker_category", JsonPrimitive(checkerCategoryName(category)))
                    put(
                        "qualifier_class_names",
                        JsonArray(qualifierNames.map { JsonPrimitive(it.value) }),
                    )
                    put("qualifiers_count", JsonPrimitive(qualifierNames.size))
                    put("helpers_count", JsonPrimitive(0))
                    put("policy_rule_count", JsonPrimitive(guide.totalRules))
                    put(
                        "rule_counts_by_category",
                        buildJsonObject {
                            guide.countsByCategory.forEach { (ruleCategory, count) ->
                                put(ruleCategory.name.lowercase(), JsonPrimitive(count))
                            }
                        },
                    )
                },
            ),
        )
        val manifest = writer.buildManifest(key)

        val qualifierInfos = if (category == CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY) discovered else emptyList()
        val projection = TelemetryProjection(
            synthesisRun = SynthesisRunRecord(
                TelemetryIds.id(),
                ctx.runId,
                ctx.stepId,
                attempt,
                stagingJar.absolutePath,
                stagedGuide.absolutePath,
                qualifierNames.size,
                0,
                guide.totalRules,
                processor,
                category,
                transcriptFile.absolutePath,
                metricsFile.absolutePath,
            ),
            qualifiers = qualifierInfos.map { info ->
                QualifierRecord(
                    TelemetryIds.id(),
                    ctx.runId,
                    ctx.stepId,
                    "@${info.simpleName}",
                    info.targetType,
                    info.subtypesOf.joinToString(",").takeIf { it.isNotEmpty() },
                    info.isTop,
                    info.isBottom,
                )
            },
            compilationEvents = compilationEvents.toList(),
            agentTurns = AgentTelemetry.turns(ctx, StageName.SYNTHESIS, fullTranscript),
            llmInvocations = AgentTelemetry.llmInvocations(
                ctx,
                StageName.SYNTHESIS,
                fullTranscript,
                ctx.config.model.synthesisModel,
                durationMs,
            ),
        )
        ctx.database.recordStageTelemetry(projection)

        logger.info {
            "synthesis complete: processor=$processor category=${checkerCategoryName(category)} " +
                "qualifiers=${qualifierNames.size} rules=${guide.totalRules} attempts=$attempt"
        }
        return StageResult.Success(
            SynthesizedLattice(
                stagingJar,
                stagedGuide,
                stagedSrc,
                qualifierNames,
                qualifierNames.size,
                processor,
                category,
                helpersCount = 0,
                policyRuleCount = guide.totalRules,
                ruleCountsByCategory = guide.countsByCategory,
            ),
            key,
            manifest,
            stagingDir,
        )
    }

    /**
     * Validates one agent attempt. A missing source tree is only acceptable when the
     * builtin-checker fallback is allowed; otherwise it is a retryable problem.
     * Compiles present sources directly into the staging bundle jar.
     */
    private fun validateAttempt(
        workspace: File,
        sourcesWorkspace: File,
        checkerQualJar: File,
        stagingDir: File,
        attempt: Int,
        ctx: RunContext,
        compilationEvents: MutableList<CompilationEventRecord>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val builtinAllowed = ctx.config.synthesis.allowedCheckers == AllowedCheckers.ALL ||
            ctx.config.synthesis.allowedCheckers == AllowedCheckers.BUILTIN_ONLY
        val javaFiles = if (sourcesWorkspace.isDirectory) {
            sourcesWorkspace.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        } else {
            emptyList()
        }
        if (javaFiles.isEmpty()) {
            if (!builtinAllowed) {
                problems += "custom_quals_src/ contains no Java sources and synthesis.allowed_checkers forbids the builtin fallback"
            }
        } else {
            problems += QualifierValidator.validatePureMarkerSources(sourcesWorkspace)
            val stagingJar = stagingDir.resolve(ArtifactNames.CUSTOM_QUALS_JAR)
            val classpath = if (checkerQualJar.isFile) listOf(checkerQualJar) else emptyList()
            val result = runCatching { javac.compileAndPackageJar(javaFiles, stagingJar, classpath) }.getOrElse { error ->
                compilationEvents += CompilationEventRecord(
                    TelemetryIds.id(),
                    ctx.runId,
                    ctx.stepId,
                    null,
                    "synthesis",
                    attempt,
                    1,
                    "javac invocation threw: ${error.message}".take(4000),
                )
                problems += "javac invocation failed: ${error.message}"
                null
            }
            if (result != null) {
                compilationEvents += CompilationEventRecord(
                    TelemetryIds.id(),
                    ctx.runId,
                    ctx.stepId,
                    null,
                    "synthesis",
                    attempt,
                    result.exitCode,
                    result.diagnostics.joinToString("\n") { diagnostic ->
                        "${diagnostic.kind}:${diagnostic.lineNumber}:${diagnostic.columnNumber}: ${diagnostic.message}"
                    }.take(4000),
                )
                if (!result.success) {
                    problems += "javac compilation failed: " +
                        result.diagnostics.joinToString("; ") { it.message }.take(2000)
                }
            }
        }
        val guideFile = workspace.resolve(ArtifactNames.PLACEMENT_GUIDE)
        if (!guideFile.isFile) {
            problems += "placement_guide.md is missing from the workspace root"
        } else {
            val guide = runCatching { PlacementGuideParser.parse(guideFile.readText()) }.getOrElse {
                problems += "placement guide parse error: ${it.message}"
                null
            }
            if (guide != null) problems += PlacementGuideParser.validateStructure(guide)
        }
        return problems
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): SynthesizedLattice? {
        val jar = bundleDir.resolve(ArtifactNames.CUSTOM_QUALS_JAR)
        val guide = bundleDir.resolve(ArtifactNames.PLACEMENT_GUIDE)
        val src = bundleDir.resolve(ArtifactNames.CUSTOM_QUALS_SRC_DIR)
        val metaFile = bundleDir.resolve(SYNTHESIS_METADATA)
        if (!jar.isFile || !guide.isFile || !src.exists() || !metaFile.isFile) return null
        return runCatching {
            val meta = PipelineJson.json.parseToJsonElement(metaFile.readText()).jsonObject
            val processor = meta.getValue("processor").jsonPrimitive.content
            val category = if (meta.getValue("checker_category").jsonPrimitive.content == "builtin_checker") {
                CheckerCategory.BUILTIN_CHECKER
            } else {
                CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY
            }
            val quals = meta.getValue("qualifier_class_names").jsonArray.map { Fqcn(it.jsonPrimitive.content) }
            val ruleCounts = meta["rule_counts_by_category"]?.jsonObject?.entries?.associate { (name, value) ->
                RuleCategory.valueOf(name.uppercase()) to value.jsonPrimitive.content.toInt()
            } ?: emptyMap()
            SynthesizedLattice(
                jar,
                guide,
                src,
                quals,
                meta["qualifiers_count"]?.jsonPrimitive?.content?.toInt() ?: quals.size,
                processor,
                category,
                helpersCount = 0,
                policyRuleCount = meta["policy_rule_count"]?.jsonPrimitive?.content?.toInt() ?: 0,
                ruleCountsByCategory = ruleCounts,
            )
        }.getOrNull()
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BUILTIN_PROCESSOR = "org.checkerframework.checker.nullness.NullnessChecker"
        const val SYNTHESIS_METADATA = "synthesis_metadata.json"
    }
}

internal fun freedomLevelName(level: FreedomLevel): String = when (level) {
    FreedomLevel.LEVEL_0 -> "level0"
    FreedomLevel.LEVEL_1 -> "level1"
    FreedomLevel.LEVEL_2 -> "level2"
}

internal fun allowedCheckersName(allowed: AllowedCheckers): String = when (allowed) {
    AllowedCheckers.ALL -> "all"
    AllowedCheckers.CUSTOM_ONLY -> "custom_only"
    AllowedCheckers.BUILTIN_ONLY -> "builtin_only"
}

internal fun checkerCategoryName(category: CheckerCategory): String = when (category) {
    CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY -> "custom_qualifier_hierarchy"
    CheckerCategory.BUILTIN_CHECKER -> "builtin_checker"
}

internal fun tokenSums(transcript: List<AgentTranscriptEvent>): Triple<Int, Int, Int> {
    var prompt = 0
    var completion = 0
    var total = 0
    transcript.forEach { event ->
        val usage = event.tokenUsage ?: return@forEach
        prompt += usage.promptTokens
        completion += usage.completionTokens
        total += usage.totalTokens
    }
    return Triple(prompt, completion, total)
}
