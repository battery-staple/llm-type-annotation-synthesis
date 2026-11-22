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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pipeline.cas.ArtifactNames
import pipeline.cas.CacheKeys
import pipeline.cas.CasEngine
import pipeline.cas.PatchManager
import pipeline.cas.StageArtifactWriter
import pipeline.db.CheckerDiagnosticRecord
import pipeline.db.CheckerRunRecord
import pipeline.db.GuardrailEventRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.AgentRunner
import pipeline.ports.BuildRunner
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson
import pipeline.validators.DiffAwareAstGuard
import pipeline.validators.FilesystemGuard

private val logger = KotlinLogging.logger {}

/**
 * Blind bug-fixing stage. The orchestrator applies `annotations.patch` to the target
 * worktree before calling this stage; on entry the worktree may therefore be dirty.
 * This stage commits the annotated baseline so all repair diffs and guards are
 * relative to HEAD (the annotated baseline, never clean C-1).
 */
class RepairStage(
    private val agentRunner: AgentRunner,
    private val buildRunner: BuildRunner,
) : PipelineStage<RepairInput, RepairResult> {

    override val stageName: StageName = StageName.REPAIR

    override fun computeCacheKey(ctx: RunContext, input: RepairInput): CasKey {
        val checker = input.checker
        val template = runCatching { PromptTemplates.load("repair.md") }.getOrDefault("")
        val stageInput = mapOf(
            "parent" to input.target.parentHash.toString(),
            "annotations_patch_sha" to fileShaOrMissing(input.annotationsPatch),
            "initial_errors_sha" to fileShaOrMissing(input.initialCheckerErrorsLog),
        )
        val checkerSlice = mapOf(
            "custom_quals_jar_sha" to fileShaOrMissing(checker.customQualsJar),
            "processor" to checker.processor,
            "checker_category" to checkerCategoryName(checker.checkerCategory),
            "quals" to checker.qualifierClassNames.map { it.value }.sorted(),
        )
        val configSlice = mapOf(
            "model_repair" to ctx.config.model.repairModel,
            "agent_steps_repair" to ctx.config.agent.steps.repair,
            "max_checker_invocations" to ctx.config.agent.maxCheckerInvocations,
            "local_variable_annotations" to ctx.config.repair.localVariableAnnotations.name.lowercase(),
            "guardrails_mode" to ctx.config.guardrails.mode.name.lowercase(),
            "checker" to checkerSlice,
        )
        return CacheKeys.compute(ctx, stageName, stageInput, emptyList(), configSlice, template)
    }

    override suspend fun execute(ctx: RunContext, input: RepairInput): StageResult<RepairResult> {
        val startedMs = System.currentTimeMillis()
        val key = computeCacheKey(ctx, input)
        val template = runCatching { PromptTemplates.load("repair.md") }.getOrElse {
            return StageResult.TransientFailure(
                RuntimeException("repair prompt template missing: ${it.message}", it),
                0,
                emptyList(),
            )
        }
        val worktree = input.target.worktreeDir
        commitAnnotatedBaseline(worktree)

        val stagingDir = CasEngine(ctx.casDir).createStagingDir(stageName)
        val writer = StageArtifactWriter(
            stageName,
            stagingDir,
            sampleIndex = ctx.sampleIndex,
            sampleTemperature = ctx.sampleTemperature,
        )
        val workspace = ctx.agentWorkspaceDir.resolve("repair_${ctx.runId}")
        val checker = input.checker
        prepareRepairWorkspace(worktree, workspace, checker, ctx)

        val initialErrors = sanitizeCheckerLogPaths(worktree, input.initialCheckerErrorsLog.readText()).take(MAX_PROMPT_ERRORS)
        val basePrompt = PromptTemplates.render(
            template,
            mapOf(
                "initial_checker_errors" to initialErrors,
                "qualifier_names" to checker.qualifierClassNames.joinToString(", ") { it.value },
                "processor" to checker.processor,
                "custom_quals_jar_path" to ".agent-libs/custom-quals.jar",
                "max_checker_invocations" to ctx.config.agent.maxCheckerInvocations.toString(),
            ),
        )

        val fullTranscript = mutableListOf<AgentTranscriptEvent>()
        val guardrailEvents = mutableListOf<GuardrailEventRecord>()
        val checkerRuns = mutableListOf<CheckerRunRecord>()
        val checkerDiagnostics = mutableListOf<CheckerDiagnosticRecord>()
        val errorProgression = mutableListOf<Int>()
        var stepsTaken = 0
        var turn = 0
        var prompt = basePrompt
        var feedback = ""
        var lastDiagnostics = initialErrors
        var lastAuditValid = true
        var lastAuditGuardTypes: List<String> = listOf("filesystem_guard", "diff_aware_ast_guard")
        var lastAuditViolations: List<String> = emptyList()
        var checkerRunsUsed = 0
        var treeChangedSinceChecker = true
        var converged = false
        val timeoutSeconds = ctx.config.agent.timeoutMs / 1000
        val maxTurns = minOf(MAX_AGENT_TURNS, ctx.config.agent.maxCheckerInvocations)
        while (turn < maxTurns && !converged) {
            turn++
            val effectivePrompt = if (feedback.isEmpty()) prompt else "$prompt\n\n## Feedback (fix the workspace in place):\n$feedback"
            val runResult = agentRunner.runAgent(
                effectivePrompt,
                workspace,
                "repair-agent",
                ctx.config.model.repairModel,
                ctx.config.agent.steps.repair,
                timeoutSeconds,
                ctx.sampleIndex.toLong() + turn,
                ctx.sampleTemperature,
                emptyMap(),
            )
            stepsTaken += runResult.stepsCounted
            fullTranscript += runResult.transcript
            agentStartupFailure(runResult)?.let { throw StageHaltException(it) }
            haltIfBenchmarkRepoDirty(ctx, "repair")

            FileOps.mirrorTreeInto(workspace, worktree, MIRROR_EXCLUDES)
            val changed = PatchManager(worktree).changedPaths()
            if (changed.isEmpty()) {
                feedback = "No file changes were detected. The last checker diagnostics were:\n" +
                    lastDiagnostics.take(MAX_FEEDBACK_CHARS) +
                    (agentFailureMessage(runResult)?.let { "\n$it" } ?: "")
                continue
            }
            treeChangedSinceChecker = true
            val pathAudit = FilesystemGuard.audit(worktree, changed, StageName.REPAIR, false, false)
            val repairAudit = DiffAwareAstGuard.validate(
                worktree,
                changed,
                "HEAD",
                ctx.config.repair.localVariableAnnotations,
            )
            lastAuditValid = pathAudit.valid && repairAudit.valid
            lastAuditViolations = violationTexts(pathAudit.violations) + violationTexts(repairAudit.violations)
            if (!lastAuditValid) {
                appendRepairViolationEvents(ctx, guardrailEvents, turn, "filesystem_guard", pathAudit.violations)
                appendRepairViolationEvents(ctx, guardrailEvents, turn, "diff_aware_ast_guard", repairAudit.violations)
                feedback = "Anti-cheat guards rejected the edits; the tree was reset to the annotated baseline:\n" +
                    lastAuditViolations.joinToString("\n").take(MAX_FEEDBACK_CHARS)
                resetToBaseline(worktree)
                prepareRepairWorkspace(worktree, workspace, checker, ctx)
                continue
            }
            if (checkerRunsUsed >= ctx.config.agent.maxCheckerInvocations) break
            val result = runStageChecker(ctx, worktree, checker, "repair_intermediate", turn, checkerRuns, checkerDiagnostics)
            checkerRunsUsed++
            treeChangedSinceChecker = false
            errorProgression += result.errorCount
            lastDiagnostics = sanitizeCheckerLogPaths(worktree, result.diagnosticsLog.readText()).take(MAX_FEEDBACK_CHARS)
            if (result.errorCount == 0) {
                converged = true
            } else {
                feedback = "The selected checker still reports ${result.errorCount} error(s). Resolve them:\n$lastDiagnostics"
                refreshWorkspace(worktree, workspace, checker, ctx)
                prompt = basePrompt
            }
        }

        var finalChecker: pipeline.ports.CheckerResult? = null
        var finalCheckerRecord = checkerRuns.lastOrNull()
        if ((finalCheckerRecord == null || treeChangedSinceChecker) && checkerRunsUsed < maxOf(1, ctx.config.agent.maxCheckerInvocations)) {
            finalChecker = runStageChecker(ctx, worktree, checker, "repair_final", null, checkerRuns, checkerDiagnostics)
            finalCheckerRecord = checkerRuns.lastOrNull()
            checkerRunsUsed++
            treeChangedSinceChecker = false
            if (finalChecker.errorCount == 0) converged = true
        } else if (finalCheckerRecord != null && treeChangedSinceChecker) {
            logger.warn { "checker invocation budget exhausted; reusing last intermediate result as final evidence" }
        }
        val remainingTypeErrors = finalCheckerRecord?.errorCount ?: -1

        val patchFile = PatchManager(worktree).writePatch(stagingDir.resolve(ArtifactNames.REPAIR_PATCH))
        writer.register(ArtifactNames.REPAIR_PATCH, patchFile)
        val finalLogPath = finalChecker?.diagnosticsLog
            ?: finalCheckerRecord?.diagnosticsLogPath?.let { File(it) }
        val finalLog = if (finalLogPath != null && finalLogPath.isFile) {
            writer.copyIn(ArtifactNames.FINAL_CHECKER_ERRORS, finalLogPath)
        } else {
            writer.writeText(ArtifactNames.FINAL_CHECKER_ERRORS, "no checker run was performed within budget\n")
        }
        val antiCheatJson = buildJsonObject {
            put("valid", JsonPrimitive(lastAuditValid))
            put(
                "guard_types",
                JsonArray(lastAuditGuardTypes.map { JsonPrimitive(it) }),
            )
            put(
                "violations",
                JsonArray(lastAuditViolations.map { JsonPrimitive(it.take(2000)) }),
            )
        }
        val antiCheatFile = writer.writeText(
            ArtifactNames.ANTI_CHEAT_AUDIT,
            PipelineJson.json.encodeToString(JsonObject.serializer(), antiCheatJson),
        )
        val guardrailEventsJson = JsonArray(
            guardrailEvents.map { event ->
                buildJsonObject {
                    put("event_id", JsonPrimitive(event.eventId))
                    put("stage", JsonPrimitive(event.stage))
                    put("turn_index", JsonPrimitive(event.turnIndex))
                    put("guard_type", JsonPrimitive(event.guardType))
                    put("violation_category", JsonPrimitive(event.violationCategory.name.lowercase()))
                    put("file_path", event.filePath?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
                    put("feedback_message", JsonPrimitive(event.feedbackMessage.take(2000)))
                }
            },
        )
        val guardrailEventsFile = writer.writeText(
            ArtifactNames.GUARDRAIL_EVENTS,
            PipelineJson.json.encodeToString(JsonArray.serializer(), guardrailEventsJson),
        )
        val transcriptFile = writer.writeText(
            ArtifactNames.FIXER_TRANSCRIPT,
            PipelineJson.json.encodeToString(ListSerializer(AgentTranscriptEvent.serializer()), fullTranscript),
        )
        val durationMs = System.currentTimeMillis() - startedMs
        val (promptTokens, completionTokens, totalTokens) = tokenSums(fullTranscript)
        val metricsFile = writer.writeText(
            ArtifactNames.FIXER_METRICS,
            PipelineJson.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("turns", JsonPrimitive(turn))
                    put("checker_invocations", JsonPrimitive(checkerRunsUsed))
                    put(
                        "error_progression",
                        JsonArray(errorProgression.map { JsonPrimitive(it) }),
                    )
                    put("remaining_type_errors", JsonPrimitive(remainingTypeErrors))
                    put("prompt_tokens", JsonPrimitive(promptTokens))
                    put("completion_tokens", JsonPrimitive(completionTokens))
                    put("total_tokens", JsonPrimitive(totalTokens))
                    put("agent_steps", JsonPrimitive(stepsTaken))
                    put("duration_ms", JsonPrimitive(durationMs))
                },
            ),
        )
        val manifest = writer.buildManifest(key)

        val projection = TelemetryProjection(
            agentTurns = AgentTelemetry.turns(ctx, StageName.REPAIR, fullTranscript),
            guardrailEvents = guardrailEvents.toList(),
            checkerRuns = checkerRuns.toList(),
            checkerDiagnostics = checkerDiagnostics.toList(),
            llmInvocations = AgentTelemetry.llmInvocations(
                ctx,
                StageName.REPAIR,
                fullTranscript,
                ctx.config.model.repairModel,
                durationMs,
            ),
        )
        ctx.database.recordStageTelemetry(projection)

        logger.info {
            "repair complete: turns=$turn checker_invocations=$checkerRunsUsed " +
                "remaining_errors=$remainingTypeErrors transcript=${transcriptFile.name} metrics=${metricsFile.name}"
        }
        return StageResult.Success(
            RepairResult(
                patchFile,
                finalLog,
                antiCheatFile,
                guardrailEventsFile,
                remainingTypeErrors,
            ),
            key,
            manifest,
            stagingDir,
        )
    }

    private suspend fun runStageChecker(
        ctx: RunContext,
        worktree: File,
        checker: CheckerConfig,
        stage: String,
        turnIndex: Int?,
        checkerRuns: MutableList<CheckerRunRecord>,
        checkerDiagnostics: MutableList<CheckerDiagnosticRecord>,
    ): pipeline.ports.CheckerResult {
        val result = buildRunner.runChecker(
            worktree,
            File(ctx.config.environment.checkerFrameworkHome),
            checker.customQualsJar,
            checker.processor,
            checker.qualifierClassNames,
            checker.checkerCategory,
            ctx.config.guardrails.mode,
        )
        val runId = TelemetryIds.id()
        checkerRuns += CheckerRunRecord(
            runId,
            ctx.runId,
            ctx.stepId,
            stage,
            turnIndex ?: checkerRuns.size + 1,
            checker.processor,
            checker.checkerCategory,
            checker.qualifierClassNames.joinToString(",") { it.value },
            result.exitCode,
            result.success,
            result.checkerInvoked,
            result.errorCount,
            result.warningCount,
            result.diagnosticsLog.absolutePath,
        )
        checkerDiagnostics += result.diagnostics.map { diagnostic ->
            CheckerDiagnosticRecord(
                TelemetryIds.id(),
                ctx.runId,
                ctx.stepId,
                runId,
                stage,
                turnIndex,
                diagnostic.severity,
                checker.processor.substringAfterLast('.'),
                diagnostic.errorCategory,
                diagnostic.filePath,
                diagnostic.lineNumber,
                null,
                diagnostic.message,
                null,
                false,
            )
        }
        return result
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): RepairResult? {
        val patch = bundleDir.resolve(ArtifactNames.REPAIR_PATCH)
        val log = bundleDir.resolve(ArtifactNames.FINAL_CHECKER_ERRORS)
        val audit = bundleDir.resolve(ArtifactNames.ANTI_CHEAT_AUDIT)
        val events = bundleDir.resolve(ArtifactNames.GUARDRAIL_EVENTS)
        val metrics = bundleDir.resolve(ArtifactNames.FIXER_METRICS)
        if (!patch.isFile || !log.isFile || !audit.isFile || !events.isFile || !metrics.isFile) return null
        return runCatching {
            val remaining = PipelineJson.json.parseToJsonElement(metrics.readText())
                .jsonObject["remaining_type_errors"]?.jsonPrimitive?.content?.toInt() ?: -1
            RepairResult(patch, log, audit, events, remaining)
        }.getOrNull()
    }

    companion object {
        const val MAX_AGENT_TURNS = 4
        const val MAX_PROMPT_ERRORS = 60_000
        const val MAX_FEEDBACK_CHARS = 30_000
        val MIRROR_EXCLUDES = setOf(".agent-libs", "run-checker.sh")
    }
}

/**
 * Redacts the worktree's absolute path from checker logs shown to the blind
 * repair agent. Absolute paths would otherwise disclose the machine layout and
 * the run's worktree directory; diagnostics stay fully usable as
 * worktree-relative paths.
 */
internal fun sanitizeCheckerLogPaths(worktree: File, text: String): String =
    text.replace(worktree.canonicalPath, ".").replace(worktree.absolutePath, ".")

/** Commits a dirty annotated-baseline worktree so HEAD becomes the annotated baseline. */
internal fun commitAnnotatedBaseline(worktree: File) {
    if (Git.statusPorcelain(worktree).isBlank()) return
    val add = Git.run(worktree, "add", "-A")
    if (add.exitCode != 0) throw RuntimeException("git add -A failed: ${add.output.takeLast(2000)}")
    val commit = Git.run(
        worktree,
        "-c", "user.name=Pipeline",
        "-c", "user.email=pipeline@localhost",
        "commit", "--no-gpg-sign", "-m", "annotated baseline",
    )
    if (commit.exitCode != 0) {
        throw RuntimeException("annotated baseline commit failed: ${commit.output.takeLast(2000)}")
    }
    if (Git.statusPorcelain(worktree).isNotBlank()) {
        throw RuntimeException("annotated baseline commit left a dirty worktree")
    }
}

internal fun resetToBaseline(worktree: File) {
    val reset = Git.run(worktree, "reset", "--hard", "HEAD")
    if (reset.exitCode != 0) throw RuntimeException("git reset --hard HEAD failed: ${reset.output.takeLast(2000)}")
    val clean = Git.run(worktree, "clean", "-fdx")
    if (clean.exitCode != 0) throw RuntimeException("git clean -fdx failed: ${clean.output.takeLast(2000)}")
}

/** Re-exports the annotated baseline into the agent workspace and restores helper files. */
internal fun prepareRepairWorkspace(worktree: File, workspace: File, checker: CheckerConfig, ctx: RunContext) {
    FileOps.exportSanitized(worktree, workspace, testReadOnly = true)
    val agentLibs = workspace.resolve(".agent-libs")
    Files.createDirectories(agentLibs.toPath())
    if (checker.customQualsJar.isFile) {
        Files.copy(
            checker.customQualsJar.toPath(),
            agentLibs.resolve("custom-quals.jar").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    }
    val checkerQualJar = File(ctx.config.environment.checkerFrameworkHome, "checker/dist/checker-qual.jar")
    if (checkerQualJar.isFile) {
        Files.copy(
            checkerQualJar.toPath(),
            agentLibs.resolve("checker-qual.jar").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    }
    writeCheckerInitScript(workspace, checker, ctx)
    writeRunCheckerScript(workspace, checker, ctx)
}

/** Re-exports accepted edits back into the agent workspace before the next agent turn. */
internal fun refreshWorkspace(worktree: File, workspace: File, checker: CheckerConfig, ctx: RunContext) {
    prepareRepairWorkspace(worktree, workspace, checker, ctx)
}

/**
 * Renders the checker init script consumed by the agent-side run-checker.sh.
 * It mirrors the harness template from the implementation plan (selected processor
 * over main sources only, -Aquals for custom hierarchies only).
 */
internal fun writeCheckerInitScript(workspace: File, checker: CheckerConfig, ctx: RunContext) {
    val initDir = workspace.parentFile.resolve(".pipeline-init")
    initDir.mkdirs()
    initDir.resolve("checker.init.gradle").writeText(
        """
        def required = { Project project, String key ->
            def value = System.getenv(key)
            if (value == null) value = System.getProperty(key)
            if (value == null) value = project.findProperty(key)
            if (value == null || value.toString().trim().isEmpty()) {
                throw new GradleException("checker.init.gradle: missing required configuration '${'$'}{key}'")
            }
            return value.toString()
        }
        def optional = { Project project, String key, String fallback ->
            def value = System.getenv(key)
            if (value == null) value = System.getProperty(key)
            if (value == null) value = project.findProperty(key)
            return (value == null || value.toString().trim().isEmpty()) ? fallback : value.toString()
        }
        allprojects { project ->
            project.plugins.withId('java') {
                project.afterEvaluate {
                    def cfHome = required(project, 'CHECKERFRAMEWORK_HOME')
                    def customQualJarPath = required(project, 'CUSTOM_QUAL_JAR_PATH')
                    def processorName = required(project, 'CF_PROCESSOR')
                    def qualsOpt = optional(project, 'CF_AQUALS', '')
                    def checkerCategory = required(project, 'CF_CHECKER_CATEGORY')
                    def guardrailsMode = optional(project, 'GUARDRAILS_MODE', 'lenient')
                    def projectSourceCompat = String.valueOf(project.sourceCompatibility ?: org.gradle.api.JavaVersion.current())
                    def projectTargetCompat = String.valueOf(project.targetCompatibility ?: project.sourceCompatibility ?: org.gradle.api.JavaVersion.current())
                    def sourceCompat = optional(project, 'CF_SOURCE_COMPATIBILITY', projectSourceCompat)
                    def targetCompat = optional(project, 'CF_TARGET_COMPATIBILITY', projectTargetCompat)
                    def checkerJar = project.file("${'$'}{cfHome}/checker/dist/checker.jar")
                    def checkerQualJar = project.file("${'$'}{cfHome}/checker/dist/checker-qual.jar")
                    def customQualJar = project.file(customQualJarPath)
                    [checkerJar, checkerQualJar, customQualJar].each { artifact ->
                        if (!artifact.exists()) {
                            throw new GradleException("checker.init.gradle: missing required artifact ${'$'}{artifact}")
                        }
                    }
                    def javacPackages = ['api', 'code', 'comp', 'file', 'main', 'model', 'parser', 'processing', 'tree', 'util', 'jvm']
                    def jvmArgs = []
                    javacPackages.each { pkg ->
                        jvmArgs += "--add-exports=jdk.compiler/com.sun.tools.javac.${'$'}{pkg}=ALL-UNNAMED"
                        jvmArgs += "--add-opens=jdk.compiler/com.sun.tools.javac.${'$'}{pkg}=ALL-UNNAMED"
                    }
                    def main = project.sourceSets.main
                    project.tasks.register('checkerPass', JavaCompile) {
                        source = main.allJava
                        classpath = project.files(main.compileClasspath, checkerQualJar, customQualJar)
                        destinationDirectory.set(project.layout.buildDirectory.dir('checker-classes'))
                        sourceCompatibility = sourceCompat
                        targetCompatibility = targetCompat
                        options.fork = true
                        options.forkOptions.jvmArgs.addAll(jvmArgs)
                        options.annotationProcessorPath = project.files(checkerJar, customQualJar)
                        def checkerArgs = ['-processor', processorName, '-Xmaxerrs', '10000', '-Xmaxwarns', '10000']
                        if (checkerCategory == 'custom_qualifier_hierarchy') {
                            if (qualsOpt.isEmpty()) {
                                throw new GradleException("checker.init.gradle: CF_AQUALS is required for custom_qualifier_hierarchy")
                            }
                            checkerArgs.add("-Aquals=${'$'}{qualsOpt}")
                        }
                        if (guardrailsMode == 'strict') {
                            checkerArgs.addAll(['-AignoreRawTypeArguments=false', '-AinvariantArrays', '-AcheckPurityAnnotations', '-Alint=cast:unsafe'])
                        }
                        options.compilerArgs.addAll(checkerArgs)
                    }
                    project.tasks.withType(Test).configureEach { testTask ->
                        testTask.classpath += project.files(checkerQualJar, customQualJar)
                    }
                }
            }
        }
        """.trimIndent(),
    )
}

/**
 * Agent-side checker entrypoint. Enforces the invocation budget with a counter file
 * kept outside the workspace; the stage additionally enforces the budget on its own
 * checker invocations.
 */
internal fun writeRunCheckerScript(workspace: File, checker: CheckerConfig, ctx: RunContext) {
    val max = ctx.config.agent.maxCheckerInvocations
    val script = workspace.resolve("run-checker.sh")
    script.writeText(
        """
        #!/usr/bin/env bash
        # Agent-side selected-checker entrypoint (budget-enforced). Not part of the audited diff.
        set -u
        SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
        COUNT_DIR="${'$'}SCRIPT_DIR/../.checker-invocations"
        MAX=$max
        mkdir -p "${'$'}COUNT_DIR"
        COUNT_FILE="${'$'}COUNT_DIR/count"
        COUNT=$(cat "${'$'}COUNT_FILE" 2>/dev/null || echo 0)
        if [ "${'$'}COUNT" -ge "${'$'}MAX" ]; then
          echo "checker invocation budget exhausted (${'$'}COUNT/${'$'}MAX)" >&2
          exit 2
        fi
        echo $((${'$'}COUNT + 1)) > "${'$'}COUNT_FILE"
        export CHECKERFRAMEWORK_HOME="${ctx.config.environment.checkerFrameworkHome}"
        export CUSTOM_QUAL_JAR_PATH="${'$'}SCRIPT_DIR/.agent-libs/custom-quals.jar"
        export CF_PROCESSOR="${checker.processor}"
        export CF_AQUALS="${checker.qualifierClassNames.joinToString(",") { it.value }}"
        export CF_CHECKER_CATEGORY="${checkerCategoryName(checker.checkerCategory)}"
        export CF_SOURCE_COMPATIBILITY="${ctx.sourceCompatibility}"
        export CF_TARGET_COMPATIBILITY="${ctx.targetCompatibility}"
        export GUARDRAILS_MODE="${ctx.config.guardrails.mode.name.lowercase()}"
        exec ./gradlew :checkerPass --init-script "${'$'}SCRIPT_DIR/../.pipeline-init/checker.init.gradle"
        """.trimIndent(),
    )
    script.setExecutable(true)
}

/** Records one guardrail event per violation; violations are treated opaquely as text. */
internal fun appendRepairViolationEvents(
    ctx: RunContext,
    out: MutableList<GuardrailEventRecord>,
    turnIndex: Int,
    guardType: String,
    violations: List<*>,
) {
    violations.forEach { violation ->
        out += GuardrailEventRecord(
            TelemetryIds.id(),
            ctx.runId,
            ctx.stepId,
            "repair",
            turnIndex,
            guardType,
            GuardrailViolationCategory.EXECUTABLE_EDIT,
            true,
            null,
            violation.toString().take(2000),
            violation.toString().take(4000),
        )
    }
}
