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
import pipeline.cas.ChangedPath
import pipeline.cas.PatchManager
import pipeline.cas.StageArtifactWriter
import pipeline.db.AnnotationPlacementRecord
import pipeline.db.CheckerDiagnosticRecord
import pipeline.db.CheckerRunRecord
import pipeline.db.DiscardCheck
import pipeline.db.GuardrailEventRecord
import pipeline.db.TelemetryProjection
import pipeline.db.TestExecutionRecord
import pipeline.db.TestRunRecord
import pipeline.ports.AgentRunner
import pipeline.ports.BuildRunner
import pipeline.ports.DatabasePort
import pipeline.runners.BuildMetrics
import pipeline.ports.TestStatus
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson
import pipeline.validators.AstGuard
import pipeline.validators.FilesystemGuard

private val logger = KotlinLogging.logger {}

/**
 * Semantic type application stage: the agent grounds the declarative placement guide
 * in the clean C-1 tree. Every agent turn is audited with [FilesystemGuard] and
 * [AstGuard]; rejected edits are rolled back and the agent is re-prompted.
 */
class ApplicationStage(
    private val agentRunner: AgentRunner,
    private val buildRunner: BuildRunner,
) : PipelineStage<ApplicationInput, AnnotatedRepo> {

    override val stageName: StageName = StageName.APPLICATION

    override fun computeCacheKey(ctx: RunContext, input: ApplicationInput): CasKey {
        val lattice = input.lattice
        val template = runCatching { PromptTemplates.load("application.md") }.getOrDefault("")
        val stageInput = mapOf(
            "parent" to input.target.parentHash.toString(),
            "lattice_jar_sha" to fileShaOrMissing(lattice.customQualsJar),
            "placement_guide_sha" to fileShaOrMissing(lattice.placementGuideFile),
            "qualifiers" to lattice.qualifierClassNames.map { it.value }.sorted(),
            "processor" to lattice.processor,
            "checker_category" to checkerCategoryName(lattice.checkerCategory),
        )
        val configSlice = mapOf(
            "model_application" to ctx.config.model.applicationModel,
            "agent_steps_application" to ctx.config.agent.steps.application,
            "agent_timeout_ms" to ctx.config.agent.timeoutMs,
            "tell_non_triviality_error" to ctx.config.prompt.tellNonTrivialityError,
            "checker_processor" to ctx.config.checker.processor,
            "checker_category" to checkerCategoryName(ctx.config.checker.category),
            "checker_quals" to RETIRED_CHECKER_QUALS_KEY,
            "build_timeout_ms" to ctx.config.agent.timeoutMs,
            "guardrails_mode" to ctx.config.guardrails.mode.name.lowercase(),
            "production_roots" to ctx.productionRoots.sorted(),
            "test_roots" to ctx.testRoots.sorted(),
        )
        return CacheKeys.compute(ctx, stageName, stageInput, emptyList(), configSlice, template, includeSampleIdentity = !isDeterministic)
    }

    override suspend fun execute(ctx: RunContext, input: ApplicationInput): StageResult<AnnotatedRepo> {
        val startedMs = System.currentTimeMillis()
        val key = computeCacheKey(ctx, input)
        val template = runCatching { PromptTemplates.load("application.md") }.getOrElse {
            return StageResult.TransientFailure(
                RuntimeException("application prompt template missing: ${it.message}", it),
                0,
                emptyList(),
            )
        }
        val lattice = input.lattice
        val worktree = input.target.worktreeDir
        resetWorktree(worktree)

        val stagingDir = CasEngine(ctx.casDir).createStagingDir(stageName)
        val writer = StageArtifactWriter(
            stageName,
            stagingDir,
            sampleIndex = ctx.sampleIndex,
            sampleTemperature = ctx.sampleTemperature,
        )
        val workspace = ctx.agentWorkspaceDir.resolve("application_${ctx.runId}")
        prepareWorkspace(worktree, workspace, lattice, ctx)

        val builtin = if (lattice.checkerCategory == CheckerCategory.BUILTIN_CHECKER) {
            BuiltinCheckerCatalog.get(lattice.processor)
        } else {
            null
        }
        val qualifierNames = builtin?.qualifierNames ?: lattice.qualifierClassNames.map { it.value }
        val importPrefixes = builtin?.let { setOf(it.importPrefix) }
            ?: QualifierDiscovery.packagePrefixes(lattice.customQualsSrcDir)
                .ifEmpty { setOf("org.checkerframework") }
        val checkerModeNote = if (builtin != null) {
            "This run uses the built-in ${builtin.displayName} (`${builtin.processor}`). " +
                "Apply that checker's annotations, listed below, according to the placement guide; " +
                "do not invent custom qualifier names."
        } else {
            "This run uses a synthesized marker qualifier hierarchy. Use only the qualifiers listed below."
        }
        val basePrompt = PromptTemplates.render(
            template,
            mapOf(
                "placement_guide" to lattice.placementGuideFile.readText(),
                "qualifier_names" to qualifierNames.joinToString(", "),
                "qualifier_import_prefixes" to importPrefixes.joinToString(", "),
                "checker_mode_note" to checkerModeNote,
                "custom_quals_jar_path" to ".agent-libs/custom-quals.jar",
                "tell_non_triviality_error" to ctx.config.prompt.tellNonTrivialityError.toString(),
                "production_roots" to pipeline.validators.SourceRoots.display(ctx.productionRoots),
                "production_globs" to pipeline.validators.SourceRoots.displayGlobs(ctx.productionRoots),
                "test_roots" to pipeline.validators.SourceRoots.display(ctx.testRoots),
                "test_globs" to pipeline.validators.SourceRoots.displayGlobs(ctx.testRoots),
            ),
        )

        val fullTranscript = mutableListOf<AgentTranscriptEvent>()
        val guardrailEvents = mutableListOf<GuardrailEventRecord>()
        val checkerRuns = mutableListOf<CheckerRunRecord>()
        val checkerDiagnostics = mutableListOf<CheckerDiagnosticRecord>()
        var testRun: TestRunRecord? = null
        var testExecutions: List<TestExecutionRecord> = emptyList()
        var stepsTaken = 0
        var feedback = ""
        var attempt = 0
        var accepted = false
        var successChanged: List<ChangedPath> = emptyList()
        val timeoutSeconds = ctx.config.agent.timeoutMs / 1000
        while (attempt < MAX_ATTEMPTS && !accepted) {
            attempt++
            val attemptStartedMs = System.currentTimeMillis()
            val effectivePrompt = if (feedback.isEmpty()) {
                basePrompt
            } else {
                basePrompt + "\n\n## Feedback from previous attempt (the tree was reset to clean C-1; re-apply annotations from scratch):\n$feedback"
            }
            val runResult = agentRunner.runAgent(
                effectivePrompt,
                workspace,
                "application-agent",
                ctx.config.model.applicationModel,
                ctx.config.agent.steps.application,
                timeoutSeconds,
                ctx.sampleIndex.toLong(),
                ctx.sampleTemperature,
                emptyMap(),
            )
            stepsTaken += runResult.stepsCounted
            fullTranscript += runResult.transcript
            agentStartupFailure(runResult)?.let { failure ->
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.OTHER, failure,
                    patchOmittedReason = "agent failed to start; no edits were produced",
                )
                throw StageHaltException(failure)
            }
            try {
                haltIfBenchmarkRepoDirty(ctx, "application")
            } catch (t: Throwable) {
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.OTHER, t.message,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = t.stackTraceToString(),
                )
                throw t
            }

            FileOps.mirrorTreeInto(workspace, worktree, MIRROR_EXCLUDES)
            val changed = PatchManager(worktree).changedPaths()
            if (changed.isEmpty()) {
                feedback = "No file changes were detected in the worktree. Apply type annotations and qualifier imports; an empty patch is not acceptable." +
                    (agentFailureMessage(runResult)?.let { "\n$it" } ?: "")
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.NO_CHANGES, feedback,
                    patchOmittedReason = "no file changes were detected in the worktree",
                    diagnostics = agentFailureMessage(runResult),
                )
                continue
            }
            val pathAudit = FilesystemGuard.audit(
                worktree, changed, StageName.APPLICATION, true, true,
                ctx.productionRoots, ctx.testRoots
            )
            val astAudit = AstGuard.validate(worktree, changed, lattice, true)
            if (!pathAudit.valid || !astAudit.valid) {
                appendViolationEvents(ctx, guardrailEvents, attempt, "filesystem_guard", pathAudit.violations)
                appendViolationEvents(ctx, guardrailEvents, attempt, "ast_gate", astAudit.violations)
                val violations = violationTexts(pathAudit.violations) + violationTexts(astAudit.violations)
                feedback = "Guardrails rejected the edits:\n" + violations.joinToString("\n").take(8000)
                // Capture the rejected patch and transcript BEFORE the reset below.
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.GUARDRAILS, feedback,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = violations.joinToString("\n"),
                )
                resetWorktree(worktree)
                prepareWorkspace(worktree, workspace, lattice, ctx)
                continue
            }
            val metrics = BuildMetrics(ctx, buildRunner)
            val compile = metrics.compile("application compile", worktree, lattice.toCheckerConfig())
            if (!compile.success) {
                feedback = "Compilation of the annotated tree failed:\n" + compile.output.take(8000)
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.COMPILE, feedback,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = compile.output,
                )
                resetWorktree(worktree)
                prepareWorkspace(worktree, workspace, lattice, ctx)
                continue
            }
            val tests = metrics.tests("pre-existing suite", worktree, null, lattice.toCheckerConfig())
            val runId = TelemetryIds.id()
            testRun = TestRunRecord(
                runId,
                ctx.runId,
                ctx.stepId,
                "application",
                "pre_existing_suite",
                attempt,
                ctx.config.verification.scope,
                tests.exitCode,
                tests.compileFailed,
                tests.testsFound,
                tests.outcomes.count { it.status == TestStatus.PASSED },
                tests.outcomes.count { it.status == TestStatus.FAILED },
                tests.outcomes.count { it.status == TestStatus.SKIPPED },
                tests.success,
                tests.xmlPaths.firstOrNull()?.absolutePath,
                tests.timedOut,
                tests.outcomes.count { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR },
            )
            testExecutions = tests.outcomes.map { outcome ->
                TestExecutionRecord(
                    TelemetryIds.id(),
                    ctx.runId,
                    ctx.stepId,
                    runId,
                    "application",
                    null,
                    outcome.testClass.value,
                    outcome.testClass.value,
                    outcome.testMethod,
                    false,
                    outcome.status,
                    outcome.durationMs,
                    outcome.failureMessage,
                )
            }
            if (tests.success && tests.outcomes.any { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }) {
                val haltMessage = "existing tests fail on annotated tree"
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.TESTS, haltMessage,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = buildString {
                        append(tests.output)
                        tests.outcomes.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
                            .forEach { outcome ->
                                append("\n${outcome.testClass.value}.${outcome.testMethod}: ")
                                append(outcome.failureMessage ?: outcome.status)
                            }
                    },
                )
                recordApplicationFailureTelemetry(
                    ctx.database, ctx, fullTranscript, System.currentTimeMillis() - startedMs,
                    guardrailEvents = guardrailEvents,
                    testRun = testRun,
                    testExecutions = testExecutions,
                )
                throw StageHaltException(haltMessage)
            }
            if (!tests.success) {
                feedback = "Test execution failed to produce a report:\n" + tests.output.take(8000)
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.TESTS, feedback,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = tests.output,
                )
                resetWorktree(worktree)
                prepareWorkspace(worktree, workspace, lattice, ctx)
                continue
            }
            val checker = metrics.checker(
                "application checker",
                worktree,
                File(ctx.config.environment.checkerFrameworkHome),
                lattice.customQualsJar,
                lattice.processor,
                lattice.qualifierClassNames,
                lattice.checkerCategory,
                ctx.config.guardrails.mode,
            )
            val checkerRunId = TelemetryIds.id()
            checkerRuns += CheckerRunRecord(
                checkerRunId,
                ctx.runId,
                ctx.stepId,
                "application_initial",
                attempt,
                lattice.processor,
                lattice.checkerCategory,
                lattice.qualifierClassNames.joinToString(",") { it.value },
                checker.exitCode,
                checker.success,
                checker.checkerInvoked,
                checker.errorCount,
                checker.warningCount,
                checker.diagnosticsLog.absolutePath,
                checker.timedOut,
            )
            checkerDiagnostics += checker.diagnostics.map { diagnostic ->
                CheckerDiagnosticRecord(
                    TelemetryIds.id(),
                    ctx.runId,
                    ctx.stepId,
                    checkerRunId,
                    "application_initial",
                    null,
                    diagnostic.severity,
                    lattice.processor.substringAfterLast('.'),
                    diagnostic.errorCategory,
                    diagnostic.filePath,
                    diagnostic.lineNumber,
                    null,
                    diagnostic.message,
                    null,
                    false,
                )
            }
            if (!checker.checkerInvoked || !checker.success) {
                val checkerLog = runCatching { checker.diagnosticsLog.readText() }.getOrDefault("")
                feedback = "The selected checker pass did not complete (invoked=${checker.checkerInvoked}, success=${checker.success}). " +
                    "Fix the annotated tree so the checker post-pass runs:\n" +
                    checkerLog.take(8000)
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.CHECKER, feedback,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = checkerLog + "\n" + checker.diagnostics.joinToString("\n") { it.message },
                )
                resetWorktree(worktree)
                prepareWorkspace(worktree, workspace, lattice, ctx)
                continue
            }
            if (checker.errorCount == 0) {
                val haltMessage = "trivial application: selected checker reported zero errors"
                DiscardedAttemptRecorder.record(
                    ctx, StageName.APPLICATION, attempt, attemptStartedMs, effectivePrompt,
                    runResult.transcript, runResult, ctx.config.agent.steps.application,
                    DiscardCheck.CHECKER, haltMessage,
                    patch = DiscardedAttemptRecorder.patchOrNull(worktree),
                    diagnostics = checker.diagnostics.joinToString("\n") { it.message },
                )
                throw StageHaltException(haltMessage)
            }
            successChanged = changed
            accepted = true
        }
        if (!accepted) {
            recordApplicationFailureTelemetry(
                ctx.database, ctx, fullTranscript, System.currentTimeMillis() - startedMs,
                guardrailEvents = guardrailEvents,
                checkerRuns = checkerRuns,
                checkerDiagnostics = checkerDiagnostics,
                testRun = testRun,
                testExecutions = testExecutions,
            )
            return StageResult.TransientFailure(
                RuntimeException("application failed after $attempt attempts: ${feedback.take(2000)}"),
                stepsTaken,
                fullTranscript.toList(),
            )
        }

        val patchFile = PatchManager(worktree).writePatch(stagingDir.resolve(ArtifactNames.ANNOTATIONS_PATCH))
        writer.register(ArtifactNames.ANNOTATIONS_PATCH, patchFile)
        val lastChecker = checkerRuns.last()
        val auditJson = buildJsonObject {
            put("valid", JsonPrimitive(true))
            put(
                "files",
                JsonArray(successChanged.map { JsonPrimitive(it.path) }),
            )
            put("violations", JsonArray(emptyList<JsonPrimitive>()))
            put("checker_error_count", JsonPrimitive(lastChecker.errorCount))
            put(
                "checker_run",
                buildJsonObject {
                    put("processor", JsonPrimitive(lastChecker.processor))
                    put("checker_invoked", JsonPrimitive(lastChecker.checkerInvoked))
                    put("success", JsonPrimitive(lastChecker.success))
                    put("error_count", JsonPrimitive(lastChecker.errorCount))
                    put("warning_count", JsonPrimitive(lastChecker.warningCount))
                },
            )
        }
        val auditFile = writer.writeText(
            ArtifactNames.AST_AUDIT,
            PipelineJson.json.encodeToString(JsonObject.serializer(), auditJson),
        )
        val initialLog = writer.copyIn(
            ArtifactNames.INITIAL_CHECKER_ERRORS,
            File(checkerRuns.last().diagnosticsLogPath ?: error("missing checker diagnostics log")),
        )
        val transcriptFile = writer.writeText(
            ArtifactNames.APPLICATION_TRANSCRIPT,
            PipelineJson.json.encodeToString(ListSerializer(AgentTranscriptEvent.serializer()), fullTranscript),
        )
        val manifest = writer.buildManifest(key)

        val qualifierSimpleNames = if (builtin != null) {
            builtin.qualifierNames.map { it.substringAfterLast('.') }.toSet()
        } else {
            lattice.qualifierClassNames.map { it.simpleName }.toSet()
        }
        val durationMs = System.currentTimeMillis() - startedMs
        val projection = TelemetryProjection(
            agentTurns = AgentTelemetry.turns(ctx, StageName.APPLICATION, fullTranscript),
            annotationPlacements = AnnotationScanner.scan(worktree, qualifierSimpleNames).map { placement ->
                AnnotationPlacementRecord(
                    TelemetryIds.id(),
                    ctx.runId,
                    ctx.stepId,
                    placement.filePath,
                    placement.lineNumber,
                    placement.symbol,
                    placement.qualifier,
                    null,
                )
            },
            guardrailEvents = guardrailEvents.toList(),
            checkerRuns = checkerRuns.toList(),
            checkerDiagnostics = checkerDiagnostics.toList(),
            testRuns = listOfNotNull(testRun),
            testExecutions = testExecutions,
            llmInvocations = AgentTelemetry.llmInvocations(
                ctx,
                StageName.APPLICATION,
                fullTranscript,
                ctx.config.model.applicationModel,
                durationMs,
            ),
        )
        ctx.database.recordStageTelemetry(projection)

        logger.info {
            "application complete: files=${successChanged.size} initial_errors=${lastChecker.errorCount} " +
                "transcript=${transcriptFile.name} attempts=$attempt"
        }
        return StageResult.Success(
            AnnotatedRepo(
                patchFile,
                auditFile,
                initialLog,
                lastChecker.errorCount,
                isTrivial = false,
            ),
            key,
            manifest,
            stagingDir,
        )
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): AnnotatedRepo? {
        val patch = bundleDir.resolve(ArtifactNames.ANNOTATIONS_PATCH)
        val audit = bundleDir.resolve(ArtifactNames.AST_AUDIT)
        val log = bundleDir.resolve(ArtifactNames.INITIAL_CHECKER_ERRORS)
        if (!patch.isFile || !audit.isFile || !log.isFile) return null
        return runCatching {
            val errorCount = PipelineJson.json.parseToJsonElement(audit.readText())
                .jsonObject.getValue("checker_error_count").jsonPrimitive.content.toInt()
            AnnotatedRepo(patch, audit, log, errorCount, isTrivial = false)
        }.getOrNull()
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        val MIRROR_EXCLUDES = setOf(".agent-libs", "run-compile.sh")
    }
}

internal fun fileShaOrMissing(file: File): String =
    if (file.isFile) Hashing.sha256(file) else "missing"

internal fun resetWorktree(worktree: File) {
    val reset = Git.run(worktree, "reset", "--hard")
    if (reset.exitCode != 0) throw RuntimeException("git reset --hard failed: ${reset.output.takeLast(2000)}")
    val clean = Git.run(worktree, "clean", "-fdx")
    if (clean.exitCode != 0) throw RuntimeException("git clean -fdx failed: ${clean.output.takeLast(2000)}")
}

internal fun prepareWorkspace(worktree: File, workspace: File, lattice: SynthesizedLattice, ctx: RunContext) {
    FileOps.exportSanitized(
        worktree, workspace, excludeTests = false,
        testRoots = ctx.testRoots
    )
    val agentLibs = workspace.resolve(".agent-libs")
    Files.createDirectories(agentLibs.toPath())
    if (lattice.customQualsJar.isFile) {
        Files.copy(
            lattice.customQualsJar.toPath(),
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
    val helper = workspace.resolve("run-compile.sh")
    helper.writeText(
        """
        #!/usr/bin/env bash
        # Best-effort local compile helper for the application agent (not part of the audited diff).
        set -u
        cd "$(dirname "$0")"
        if [ -x ./gradlew ]; then
          ./gradlew compileJava
        else
          echo "no gradlew wrapper found" >&2
          exit 1
        fi
        """.trimIndent(),
    )
    helper.setExecutable(true)
}

/** Records one guardrail event per violation; violations are treated opaquely as text. */
internal fun appendViolationEvents(
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
            "application",
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

internal fun violationTexts(violations: List<*>): List<String> = violations.map { it.toString() }

/**
 * Persists the telemetry accumulated by an application attempt that did not reach
 * the success path (which records its own, fuller projection). Without this, long
 * agent runs that only failed application attempts leave no agent turns or LLM
 * invocation rows behind. Any checker/test telemetry gathered so far is included.
 */
internal suspend fun recordApplicationFailureTelemetry(
    database: DatabasePort,
    ctx: RunContext,
    fullTranscript: List<AgentTranscriptEvent>,
    durationMs: Long,
    guardrailEvents: List<GuardrailEventRecord> = emptyList(),
    checkerRuns: List<CheckerRunRecord> = emptyList(),
    checkerDiagnostics: List<CheckerDiagnosticRecord> = emptyList(),
    testRun: TestRunRecord? = null,
    testExecutions: List<TestExecutionRecord> = emptyList(),
) {
    database.recordStageTelemetry(
        TelemetryProjection(
            agentTurns = AgentTelemetry.turns(ctx, StageName.APPLICATION, fullTranscript),
            llmInvocations = AgentTelemetry.llmInvocations(
                ctx,
                StageName.APPLICATION,
                fullTranscript,
                ctx.config.model.applicationModel,
                durationMs,
            ),
            guardrailEvents = guardrailEvents,
            checkerRuns = checkerRuns,
            checkerDiagnostics = checkerDiagnostics,
            testRuns = listOfNotNull(testRun),
            testExecutions = testExecutions,
        )
    )
}
