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
import pipeline.db.DiscardCheck
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
            "builtin_checker" to ctx.config.synthesis.builtinChecker,
            "model_synthesis" to ctx.config.model.synthesisModel,
            "checker_processor" to ctx.config.checker.processor,
            "checker_category" to checkerCategoryName(ctx.config.checker.category),
            "checker_quals" to RETIRED_CHECKER_QUALS_KEY,
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
        val builtinChecker = BuiltinCheckerCatalog.require(ctx.config.synthesis.builtinChecker)
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
                "custom_processor" to ctx.config.checker.processor,
                "builtin_checker" to builtinChecker.processor,
                "builtin_checker_name" to builtinChecker.displayName,
            ),
        )

        val fullTranscript = mutableListOf<AgentTranscriptEvent>()
        val compilationEvents = mutableListOf<CompilationEventRecord>()
        var stepsTaken = 0
        var attempt = 0
        var prompt = basePrompt
        var lastProblems: List<String> = listOf("synthesis agent has not run yet")
        var accepted = false
        var acceptedDecision: SynthesisDecision? = null
        val decisionPolicy = SynthesisDecisionPolicy(
            allowedCheckers = ctx.config.synthesis.allowedCheckers,
            customProcessor = ctx.config.checker.processor,
            builtinProcessor = builtinChecker.processor,
        )
        val timeoutSeconds = ctx.config.agent.timeoutMs / 1000
        while (attempt < MAX_ATTEMPTS && !accepted) {
            attempt++
            val attemptStartedMs = System.currentTimeMillis()
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
            agentStartupFailure(runResult)?.let { failure ->
                DiscardedAttemptRecorder.record(
                    ctx, StageName.SYNTHESIS, attempt, attemptStartedMs, prompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.synthesis,
                    DiscardCheck.OTHER, failure,
                    patchOmittedReason = "agent failed to start; no workspace artifacts were produced",
                )
                throw StageHaltException(failure)
            }
            try {
                haltIfBenchmarkRepoDirty(ctx, "synthesis")
            } catch (t: Throwable) {
                DiscardedAttemptRecorder.record(
                    ctx, StageName.SYNTHESIS, attempt, attemptStartedMs, prompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.synthesis,
                    DiscardCheck.OTHER, t.message,
                    patchOmittedReason = "benchmark checkout contamination detected; workspace artifacts were discarded",
                    diagnostics = t.stackTraceToString(),
                )
                throw t
            }
            val decisionResult = runCatching {
                SynthesisDecisionParser.parse(workspace.resolve(SynthesisDecision.FILE_NAME))
            }
            val decision = decisionResult.getOrNull()
            val decisionProblems = buildList {
                decisionResult.exceptionOrNull()?.let { add(it.message ?: "invalid synthesis decision") }
                decision?.let { addAll(SynthesisDecisionValidator.validate(it, decisionPolicy)) }
            }
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
                        compilationEvents,
                        decision,
                        decisionProblems,
                    )
                )
            }
            if (problems.isEmpty()) {
                accepted = true
                acceptedDecision = decision
                lastProblems = emptyList()
            } else {
                lastProblems = problems
                logger.warn { "synthesis attempt $attempt failed validation: ${problems.joinToString("; ")}" }
                val feedback =
                    "\n\n## Corrective feedback (attempt $attempt failed validation; workspace files persist, fix them in place):\n" +
                        problems.joinToString("\n") { "- $it" }
                // The rejected session saw the previous prompt; the corrective
                // feedback is stored separately in the record's `feedback` field.
                val rejectedPrompt = prompt
                prompt = basePrompt + feedback
                val check = when {
                    problems.any { it.startsWith("javac compilation failed") || it.startsWith("javac invocation failed") } ->
                        DiscardCheck.COMPILE
                    problems.any { it.contains("checker canary", ignoreCase = true) } -> DiscardCheck.CHECKER
                    else -> DiscardCheck.OTHER
                }
                DiscardedAttemptRecorder.record(
                    ctx, StageName.SYNTHESIS, attempt, attemptStartedMs, rejectedPrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.synthesis,
                    check, feedback,
                    patchOmittedReason = "synthesis writes into a non-Git workspace; qualifier sources, " +
                        "placement guide and decision file remain there and are not a worktree diff",
                    diagnostics = buildString {
                        append(problems.joinToString("\n"))
                        compilationEvents.filter { it.turnIndex == attempt }
                            .forEach { append("\n${it.stage} exit=${it.exitCode}:\n${it.diagnosticsText.orEmpty()}") }
                    },
                )
            }
        }
        if (!accepted) {
            return StageResult.TransientFailure(
                RuntimeException("synthesis failed after $attempt agent sessions: ${lastProblems.joinToString("; ")}"),
                stepsTaken,
                fullTranscript.toList(),
            )
        }
        val decision = acceptedDecision
            ?: return StageResult.TransientFailure(
                RuntimeException("synthesis accepted without a parsed strategy decision"),
                stepsTaken,
                fullTranscript.toList(),
            )

        val discovered = if (decision.checkerCategory == CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY) {
            QualifierDiscovery.typeUseQualifiers(sourcesWorkspace)
        } else {
            emptyList()
        }
        if (decision.checkerCategory == CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY && discovered.isEmpty()) {
            return StageResult.TransientFailure(
                RuntimeException("custom_qualifier_hierarchy decision was accepted without TYPE_USE qualifiers"),
                stepsTaken,
                fullTranscript.toList(),
            )
        }
        val processor = decision.processor
        val category = decision.checkerCategory
        val qualifierNames = discovered.map { it.fqcn }
        if (category == CheckerCategory.BUILTIN_CHECKER) {
            val stagingJar = stagingDir.resolve(ArtifactNames.CUSTOM_QUALS_JAR)
            val emptySrc = workspace.resolve(".empty-quals-src")
            emptySrc.mkdirs()
            createJarFromDirectory(emptySrc, stagingJar)
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
        writer.copyIn(ArtifactNames.SYNTHESIS_DECISION, workspace.resolve(SynthesisDecision.FILE_NAME))
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
                    put("allowed_checkers", JsonPrimitive(allowedCheckersName(ctx.config.synthesis.allowedCheckers)))
                    put("fallback_occurred", JsonPrimitive(false))
                    put(
                        "decision",
                        buildJsonObject {
                            put("checker_category", JsonPrimitive(checkerCategoryName(category)))
                            put("processor", JsonPrimitive(processor))
                            put("rationale", JsonPrimitive(decision.rationale))
                            put("source", JsonPrimitive("agent"))
                        },
                    )
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
                decisionSource = "agent",
                decisionRationale = decision.rationale.take(2000),
                fallbackOccurred = false,
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
     * Validates one agent attempt against the explicit strategy decision.
     *
     * A custom decision requires valid TYPE_USE qualifier sources and compiles
     * them into the staging bundle jar. A built-in decision requires no custom
     * sources; the empty marker jar is created after acceptance. A missing or
     * malformed decision is always a retryable problem, never a fallback.
     */
    private fun validateAttempt(
        workspace: File,
        sourcesWorkspace: File,
        checkerQualJar: File,
        stagingDir: File,
        attempt: Int,
        ctx: RunContext,
        compilationEvents: MutableList<CompilationEventRecord>,
        decision: SynthesisDecision?,
        decisionProblems: List<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        problems += decisionProblems
        val javaFiles = if (sourcesWorkspace.isDirectory) {
            sourcesWorkspace.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        } else {
            emptyList()
        }
        when (decision?.checkerCategory) {
            CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY -> {
                if (javaFiles.isEmpty()) {
                    problems += "custom_qualifier_hierarchy decision requires at least one Java source " +
                        "under custom_quals_src/"
                } else {
                    problems += QualifierValidator.validatePureMarkerSources(sourcesWorkspace)
                    problems += QualifierValidator.validateTypeUseLattice(sourcesWorkspace)
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
                            sessionIndex = attempt,
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
                            sessionIndex = attempt,
                        )
                        if (!result.success) {
                            problems += "javac compilation failed: " +
                                result.diagnostics.joinToString("; ") { it.message }.take(2000)
                        } else {
                            // The jar compiles, but the Checker may still crash while
                            // building its hierarchy (e.g. a root without @SubtypeOf({})).
                            // Run a harness-side canary before accepting the attempt.
                            val qualifiers = QualifierDiscovery.typeUseQualifiers(sourcesWorkspace).map { it.fqcn }
                            val canary = runCatching {
                                CheckerCanary.run(
                                    checkerHome = File(ctx.config.environment.checkerFrameworkHome),
                                    customQualsJar = stagingJar,
                                    checkerQualJar = checkerQualJar,
                                    processor = decision?.processor ?: ctx.config.checker.processor,
                                    qualifiers = qualifiers,
                                    javaHome = ctx.config.environment.javaHome,
                                )
                            }.getOrElse { error ->
                                CheckerCanaryResult(
                                    invoked = false,
                                    exitCode = -1,
                                    output = "",
                                    problem = "checker canary could not start: invocation threw: ${error.message}",
                                    harnessFailure = true,
                                )
                            }
                            compilationEvents += CompilationEventRecord(
                                TelemetryIds.id(),
                                ctx.runId,
                                ctx.stepId,
                                null,
                                "synthesis_canary",
                                attempt,
                                canary.exitCode,
                                buildString {
                                    append("checker canary invoked=${canary.invoked} exit=${canary.exitCode} ")
                                    append("processor=${decision?.processor ?: ctx.config.checker.processor}")
                                    append("\nquals=${qualifiers.joinToString(",") { it.value }}")
                                    if (canary.problem != null) append("\nproblem=${canary.problem}")
                                    else append("\n${canary.output.take(1500)}")
                                }.take(4000),
                                sessionIndex = attempt,
                            )
                            if (canary.harnessFailure) {
                                // A broken canary harness is deterministic for every
                                // custom-hierarchy sample in the batch. Re-prompting the
                                // agent (and the orchestrator's stage retries) cannot fix
                                // the environment, so halt the run with an actionable error.
                                val message = "checker canary harness failure: ${canary.problem ?: canary.output.take(500)}"
                                logger.error { message }
                                throw StageHaltException(message)
                            }
                            canary.problem?.let { problems += it }
                        }
                    }
                }
            }

            CheckerCategory.BUILTIN_CHECKER -> {
                if (javaFiles.isNotEmpty()) {
                    problems += "builtin_checker decision must not include custom qualifier sources; " +
                        "remove the ${javaFiles.size} Java file(s) under custom_quals_src/"
                }
            }

            null -> Unit // The missing/invalid decision is already reported above.
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
