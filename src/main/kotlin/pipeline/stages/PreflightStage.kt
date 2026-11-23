package pipeline.stages

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import pipeline.cas.CacheKeys
import pipeline.cas.CasEngine
import pipeline.cas.StageArtifactWriter
import pipeline.ports.BuildResult
import pipeline.ports.GradleTaskRunner
import pipeline.ports.JavacCompiler
import pipeline.ports.TestStatus
import pipeline.runners.findTestXml
import pipeline.runners.outputShowsCheckerCrash
import pipeline.runners.parseCheckerDiagnostics
import pipeline.runners.parseTestXml
import pipeline.runners.qualifiedTask
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson

private val logger = KotlinLogging.logger {}

/**
 * Build-integration preflight (Dockerization plan, section 7.3).
 *
 * Runs checks P1-P7 at clean C-1 in its own worktree and stops at the first
 * failure. A repository outside the supported envelope is not a stage failure:
 * the stage succeeds with an `unsupported` [PreflightReport] carrying the
 * failed check and a reason code, and the orchestrator ends the run with
 * lifecycle status `unsupported` before any LLM call. Infrastructure trouble
 * (network outages, a missing worktree) is a [StageResult.TransientFailure].
 *
 * The result is cached on the build configuration, not on every source change
 * (see [computeCacheKey]); outcomes that depend on the sources, such as a
 * checker crash on new syntax or a test that starts failing, are rechecked by
 * the later stages.
 */
class PreflightStage(
    private val gradle: GradleTaskRunner,
    private val javac: JavacCompiler,
) : PipelineStage<PreflightInput, PreflightReport> {

    override val stageName: StageName = StageName.PREFLIGHT
    override val isDeterministic: Boolean = true

    override fun computeCacheKey(ctx: RunContext, input: PreflightInput): CasKey {
        val gitDir = input.ingestion.worktreeDir
        val parent = input.ingestion.parentHash.hash
        val testClass = chooseTestClass(gitDir, ctx, input)
        val stageInput = mapOf(
            "repo_identity" to IngestionStage.repoIdentity(ctx.repoDir.path, File(System.getProperty("user.dir"))),
            "build_config" to PreflightSupport.buildConfigHash(gitDir, parent, ctx.productionRoots, ctx.testRoots),
            "test_class" to testClass?.first?.value,
            "test_class_source" to testClass?.second?.let {
                PreflightSupport.blobHash(gitDir, parent, it.relativeTo(gitDir.absoluteFile).invariantSeparatorsPath)
            },
            "configured_source_level" to input.configuredSourceLevel,
            "production_roots" to ctx.productionRoots.sorted(),
            "test_roots" to ctx.testRoots.sorted(),
        )
        val configSlice = buildConfigSlice(ctx.build) + buildTimeoutConfig(ctx) + mapOf(
            "suite_check" to ctx.config.preflight.suiteCheck,
            "guardrails_mode" to ctx.config.guardrails.mode.name.lowercase(),
            "java_home" to ctx.config.environment.javaHome,
            "gradle_user_home" to ctx.config.environment.gradleUserHome,
            "toolchain_auto_download" to false,
        )
        return CacheKeys.compute(
            ctx = ctx,
            stage = stageName,
            stageInput = stageInput,
            upstreamKeys = emptyList(),
            configSlice = configSlice,
            promptTemplate = null,
            extra = mapOf("probe_templates" to PROBE_TEMPLATES_SHA),
            includeSampleIdentity = !isDeterministic,
        )
    }

    override suspend fun execute(ctx: RunContext, input: PreflightInput): StageResult<PreflightReport> {
        val key = computeCacheKey(ctx, input)
        val stagingDir = CasEngine(ctx.casDir).createStagingDir(stageName)
        val logsDir = stagingDir.resolve(LOGS_DIR).apply { mkdirs() }
        val run = Run(ctx, input, logsDir, key)
        val report = try {
            if (!input.worktreeDir.exists()) {
                Git.addWorktree(input.mirror, input.worktreeDir, input.ingestion.parentHash.hash)
            }
            run.checks()
        } catch (transient: TransientPreflightException) {
            return StageResult.TransientFailure(transient, 0, emptyList())
        }
        val writer = StageArtifactWriter(stageName, stagingDir, sampleIndex = 0, sampleTemperature = 0.0)
        writer.writeText(REPORT_FILE, PipelineJson.json.encodeToString(PreflightReport.serializer(), report))
        writer.registerDirectory(LOGS_DIR)
        logger.info {
            "Preflight ${input.benchmarkId.canonical}: ${report.status}" +
                (report.reasonCode?.let { " (${report.failedCheck}: $it)" } ?: "")
        }
        return StageResult.Success(report, key, writer.buildManifest(key), stagingDir)
    }

    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): PreflightReport? =
        runCatching {
            PipelineJson.json.decodeFromString(PreflightReport.serializer(), bundleDir.resolve(REPORT_FILE).readText())
        }.getOrNull()

    private fun chooseTestClass(root: File, ctx: RunContext, input: PreflightInput): Pair<Fqcn, File>? =
        PreflightSupport.chooseTestClass(
            root = root,
            subprojectDir = ctx.build.subproject.trim().trim(':').replace(':', '/'),
            testRoots = ctx.testRoots,
            candidates = input.testClasses,
        )

    /** One preflight execution; the first failing check ends it. */
    private inner class Run(
        private val ctx: RunContext,
        private val input: PreflightInput,
        private val logsDir: File,
        private val key: CasKey,
    ) {
        private val started = System.currentTimeMillis()
        private val worktree = input.worktreeDir
        private val checks = mutableListOf<PreflightCheck>()
        private var report = PreflightReport(
            status = PreflightReport.STATUS_PASS,
            sourceLevelConfigured = input.configuredSourceLevel,
            cacheKey = key.toString(),
        )

        private fun task(name: String) = qualifiedTask(ctx.build, name)

        private val compileTasks: List<String>
            get() = listOf(task(ctx.build.compileTask), task("compileTestJava"))

        suspend fun checks(): PreflightReport {
            val probe = p1() ?: return finish()
            if (!p2(probe)) return finish()
            val checker = probeLattice()
            val env = levelEnv()
            if (!p3(checker, env)) return finish()
            val plant = p4(probe, checker, env) ?: return finish()
            if (!p5(checker, env, plant)) return finish()
            resetWorktree()
            if (!p6(probe, checker, env)) return finish()
            if (ctx.config.preflight.suiteCheck) {
                p7(checker, env)
            } else {
                checks += PreflightCheck("P7", "skipped", detail = "preflight.suite_check is false")
            }
            return finish()
        }

        private fun finish(): PreflightReport =
            report.copy(checks = checks.toList(), durationMs = System.currentTimeMillis() - started)

        /** Records a failed check and marks the report unsupported. Always returns false. */
        private fun fail(check: String, reason: String, message: String, output: String = "", durationMs: Long = 0): Boolean {
            require(reason in PreflightReason.ALL) { "unknown preflight reason code '$reason'" }
            checks += PreflightCheck(check, "fail", durationMs, "$reason: ${message.take(500)}")
            report = report.copy(
                status = PreflightReport.STATUS_UNSUPPORTED,
                failedCheck = check,
                reasonCode = reason,
                message = message.take(4000),
                logTail = output.takeLast(8000),
            )
            return false
        }

        private fun pass(check: String, durationMs: Long, detail: String = "") {
            checks += PreflightCheck(check, "pass", durationMs, detail)
        }

        /** Runs Gradle, keeps the log, and turns a network outage into a retry. */
        private suspend fun gradle(
            label: String,
            tasks: List<String>,
            checker: CheckerConfig? = null,
            initScripts: List<File> = emptyList(),
            env: Map<String, String> = emptyMap(),
        ): Pair<BuildResult, Long> {
            val start = System.currentTimeMillis()
            val result = pipeline.runners.BuildMetrics(ctx).task("preflight", label) {
                this@PreflightStage.gradle.runTasks(worktree, tasks, checker, initScripts, env)
            }
            val duration = System.currentTimeMillis() - start
            runCatching { logsDir.resolve("$label.log").writeText(result.output.takeLast(LOG_LIMIT)) }
            if (!result.success && !result.timedOut && PreflightSupport.isTransient(result.output)) {
                throw TransientPreflightException("$label: network failure: ${result.output.takeLast(2000)}")
            }
            return result to duration
        }

        // --- P1 Environment ------------------------------------------------

        private suspend fun p1(): JsonObject? {
            val start = System.currentTimeMillis()
            val wrapper = worktree.resolve(ctx.build.wrapperExecutable.removePrefix("./"))
            val hasGradleBuild = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
                .any { worktree.resolve(it).isFile }
            if (!wrapper.isFile || !hasGradleBuild) {
                val other = listOf("pom.xml" to "Maven", "build.xml" to "Ant", "WORKSPACE" to "Bazel", "build.sbt" to "sbt")
                    .firstOrNull { worktree.resolve(it.first).isFile }?.second
                fail("P1", PreflightReason.BUILD_TOOL_UNSUPPORTED,
                    if (other != null) "$other build without a Gradle wrapper" else "no Gradle wrapper or build file")
                return null
            }
            val version = PreflightSupport.wrapperGradleVersion(worktree)
            report = report.copy(gradleVersion = version)
            if (version != null && !PreflightSupport.versionAtLeast(version, MIN_GRADLE_MAJOR, MIN_GRADLE_MINOR)) {
                fail("P1", PreflightReason.GRADLE_TOO_OLD, "Gradle $version is older than $MIN_GRADLE_MAJOR.$MIN_GRADLE_MINOR")
                return null
            }
            val probeScript = writeResource("probe.init.gradle", harnessDir().resolve("probe.init.gradle"))
            val reportFile = harnessDir().resolve("probe-report.json").also { it.delete() }
            val projectPath = ctx.build.subproject.trim().trim(':').let { if (it.isEmpty()) ":" else ":$it" }
            val (result, duration) = gradle(
                "P1-probe", listOf("help", "--no-configuration-cache"), initScripts = listOf(probeScript),
                env = mapOf("LLTAS_PREFLIGHT_REPORT" to reportFile.absolutePath, "LLTAS_PREFLIGHT_PROJECT" to projectPath),
            )
            if (!result.success || !reportFile.isFile) {
                fail("P1", PreflightSupport.classify("P1", result.output, result.timedOut),
                    "Gradle probe failed (exit ${result.exitCode})", result.output, duration)
                return null
            }
            val probe = PipelineJson.json.parseToJsonElement(reportFile.readText()).jsonObject
            runCatching { reportFile.copyTo(logsDir.resolve("probe-report.json"), overwrite = true) }
            val compile = probe["compile"] as? JsonObject
            val declared = compile?.let {
                str(it, "release") ?: str(it, "args_release") ?: str(it, "source_compat")
            }
            report = report.copy(
                gradleVersion = str(probe, "gradle_version") ?: version,
                runtimeJdk = str(probe, "runtime_jdk"),
                compileToolchainJdk = compile?.let { str(it, "toolchain_version") },
                testToolchainJdk = str(probe, "test_toolchain_version"),
                sourceLevelDeclared = PreflightSupport.javaLevel(declared)?.toString() ?: declared,
            )
            when {
                str(probe, "project_found") != "true" ->
                    fail("P1", PreflightReason.BUILD_TOOL_UNSUPPORTED, "Gradle project $projectPath not found", durationMs = duration)
                str(probe, "java_plugin") != "true" ->
                    fail("P1", PreflightReason.BUILD_TOOL_UNSUPPORTED, "project $projectPath does not apply the java plugin", durationMs = duration)
                str(probe, "android") == "true" ->
                    fail("P1", PreflightReason.BUILD_TOOL_UNSUPPORTED, "Android project", durationMs = duration)
                processors(probe).any { p -> UNSUPPORTED_PROCESSORS.any { p.startsWith(it) } } ->
                    fail("P1", PreflightReason.PROCESSOR_UNSUPPORTED,
                        "unsupported annotation processor: ${processors(probe).filter { p -> UNSUPPORTED_PROCESSORS.any { p.startsWith(it) } }}",
                        durationMs = duration)
                compile?.let { str(it, "toolchain_error") } != null ->
                    fail("P1", PreflightReason.JDK_UNAVAILABLE, "compile toolchain: ${str(compile, "toolchain_error")}", durationMs = duration)
                str(probe, "test_toolchain_error") != null ->
                    fail("P1", PreflightReason.JDK_UNAVAILABLE, "test toolchain: ${str(probe, "test_toolchain_error")}", durationMs = duration)
                (PreflightSupport.javaLevel(declared) ?: 0) > MAX_SOURCE_LEVEL ->
                    fail("P1", PreflightReason.SOURCE_LEVEL_UNSUPPORTED, "declared source level $declared > $MAX_SOURCE_LEVEL", durationMs = duration)
                else -> {
                    pass("P1", System.currentTimeMillis() - start,
                        "gradle=${report.gradleVersion} runtime=${report.runtimeJdk} toolchain=${report.compileToolchainJdk} declared=${report.sourceLevelDeclared}")
                    return probe
                }
            }
            return null
        }

        // --- P2 Baseline compile -------------------------------------------

        private suspend fun p2(probe: JsonObject): Boolean {
            val (result, duration) = gradle("P2-baseline", compileTasks)
            if (!result.success) {
                return fail("P2", PreflightSupport.classify("P2", result.output, result.timedOut),
                    "baseline compile failed (exit ${result.exitCode})", result.output, duration)
            }
            val classes = (probe["compile"] as? JsonObject)?.let { str(it, "destination_dir") }?.let(::File)
            val observed = PreflightSupport.observedLevel(classes)
            report = report.copy(sourceLevelObserved = observed?.toString())
            if (observed != null && observed > MAX_SOURCE_LEVEL) {
                return fail("P2", PreflightReason.SOURCE_LEVEL_UNSUPPORTED,
                    "compiled classes are Java $observed (> $MAX_SOURCE_LEVEL)", durationMs = duration)
            }
            val mismatch = report.sourceLevelConfigured?.let { PreflightSupport.javaLevel(it) }
                ?.takeIf { observed != null && it != observed }
            pass("P2", duration, buildString {
                append("observed level=${observed ?: "unknown"}")
                if (mismatch != null) append("; configured source_compatibility=$mismatch disagrees with the build")
            })
            return true
        }

        /** P3-P7 compile and check at the level the build produced, not a configured default. */
        private fun levelEnv(): Map<String, String> {
            val level = report.effectiveSourceLevel ?: report.sourceLevelConfigured ?: return emptyMap()
            return mapOf("CF_SOURCE_COMPATIBILITY" to level, "CF_TARGET_COMPATIBILITY" to level)
        }

        private fun probeLattice(): CheckerConfig {
            val dir = harnessDir().resolve("probe-lattice/lltas/probe").apply { mkdirs() }
            val sources = listOf("ProbeTop.java", "ProbeBottom.java").map { writeResource(it, dir.resolve(it)) }
            val jar = harnessDir().resolve("probe-quals.jar")
            val checkerQual = File(ctx.config.environment.checkerFrameworkHome, "checker/dist/checker-qual.jar")
            val result = javac.compileAndPackageJar(sources, jar, listOf(checkerQual))
            check(result.success) { "probe lattice does not compile: ${result.diagnostics}" }
            return CheckerConfig(
                customQualsJar = jar,
                processor = SUBTYPING_CHECKER,
                checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY,
                qualifierClassNames = listOf(Fqcn("lltas.probe.ProbeTop"), Fqcn("lltas.probe.ProbeBottom")),
            )
        }

        // --- P3 Integration compile ----------------------------------------

        private suspend fun p3(checker: CheckerConfig, env: Map<String, String>): Boolean {
            val (result, duration) = gradle("P3-integration", compileTasks, checker, env = env)
            if (!result.success) {
                return fail("P3", PreflightSupport.classify("P3", result.output, result.timedOut),
                    "compile with the harness init script failed (exit ${result.exitCode})", result.output, duration)
            }
            pass("P3", duration)
            return true
        }

        // --- P4 Probe annotation -------------------------------------------

        private suspend fun p4(probe: JsonObject, checker: CheckerConfig, env: Map<String, String>): File? {
            val main = packageDir(dirs(probe, "main_src_dirs"))
            if (main == null) {
                fail("P4", PreflightReason.BUILD_TOOL_UNSUPPORTED, "no Java main sources in the target project")
                return null
            }
            val plantFile = main.first.resolve("LltasProbe.java")
            plantFile.writeText(template("LltasProbe.java.template").replace("@@PACKAGE@@", main.second))
            packageDir(dirs(probe, "test_src_dirs"))?.let { (dir, pkg) ->
                dir.resolve("LltasProbeSupport.java")
                    .writeText(template("LltasProbeSupport.java.template").replace("@@PACKAGE@@", pkg))
            }
            val (result, duration) = gradle("P4-probe", compileTasks, checker, env = env)
            if (!result.success) {
                fail("P4", PreflightSupport.classify("P4", result.output, result.timedOut),
                    "compile of probe-annotated sources failed (exit ${result.exitCode})", result.output, duration)
                return null
            }
            pass("P4", duration, "probe in ${main.second}")
            return plantFile
        }

        // --- P5 Checker run ------------------------------------------------

        private suspend fun p5(checker: CheckerConfig, env: Map<String, String>, plantFile: File): Boolean {
            val (clean, cleanDuration) = gradle("P5-checker-clean", listOf(task(ctx.build.checkerTask)), checker, env = env)
            checkerFailure(clean, cleanDuration)?.let { return it }
            val baseline = parseCheckerDiagnostics(clean.output)
            val javacErrors = baseline.filter { it.severity == "error" && it.errorCategory.isBlank() }
            if (javacErrors.isNotEmpty()) {
                return fail("P5", PreflightSupport.classify("P5", clean.output, false).let {
                    if (it == PreflightReason.UNKNOWN) PreflightReason.DIAGNOSTICS_UNPARSED else it
                }, "checkerPass reports compiler errors outside the checker: ${javacErrors.first().message}",
                    clean.output, cleanDuration)
            }
            val text = plantFile.readText()
            val plantLine = text.lines().indexOfFirst { PLANT_MARKER in it } + 1
            plantFile.writeText(text.replace(PLANT_MARKER, PLANTED_ERROR))
            val (planted, plantedDuration) = gradle("P5-checker-planted", listOf(task(ctx.build.checkerTask)), checker, env = env)
            checkerFailure(planted, plantedDuration)?.let { return it }
            val baselineKeys = baseline.map { listOf(it.filePath, it.lineNumber, it.errorCategory, it.message) }.toSet()
            val added = parseCheckerDiagnostics(planted.output)
                .filter { it.severity == "error" }
                .filterNot { listOf(it.filePath, it.lineNumber, it.errorCategory, it.message) in baselineKeys }
            val atPlant = added.filter { it.filePath.endsWith("/LltasProbe.java") && it.lineNumber == plantLine }
            return when {
                added.isEmpty() && planted.exitCode != 0 ->
                    fail("P5", PreflightReason.DIAGNOSTICS_UNPARSED, "checker failed but no diagnostic was parsed",
                        planted.output, plantedDuration)
                added.isEmpty() ->
                    fail("P5", PreflightReason.CHECKER_SILENT, "the planted ill-typed return was not reported",
                        planted.output, plantedDuration)
                added.size != 1 || atPlant.size != 1 ->
                    fail("P5", PreflightReason.CHECKER_MISREPORT,
                        "expected one error at LltasProbe.java:$plantLine, got ${added.map { "${it.filePath.substringAfterLast('/')}:${it.lineNumber} [${it.errorCategory}]" }}",
                        planted.output, plantedDuration)
                else -> {
                    pass("P5", cleanDuration + plantedDuration,
                        "baseline errors=${baseline.count { it.severity == "error" }}; planted [${atPlant.single().errorCategory}] reported")
                    true
                }
            }
        }

        /** A checker invocation that crashed, did not run, or could not start; null when it ran. */
        private fun checkerFailure(result: BuildResult, duration: Long): Boolean? {
            val output = result.output
            val invoked = output.contains(ctx.build.checkerTask) && !output.contains("Task '${ctx.build.checkerTask}' not found") ||
                output.contains("SubtypingChecker")
            return when {
                result.timedOut -> fail("P5", PreflightReason.TIMEOUT, "checker timed out", output, duration)
                outputShowsCheckerCrash(output) -> fail("P5", PreflightReason.CHECKER_CRASH, "the Checker Framework crashed", output, duration)
                PreflightSupport.classify("P5", output, false) == PreflightReason.CHECKER_JDK_UNSUPPORTED ->
                    fail("P5", PreflightReason.CHECKER_JDK_UNSUPPORTED, "the checker fork cannot start on the toolchain JDK", output, duration)
                !invoked -> fail("P5", PreflightReason.CHECKER_SILENT, "checkerPass did not run", output, duration)
                !result.success && parseCheckerDiagnostics(output).isEmpty() -> {
                    val reason = PreflightSupport.classify("P5", output, false)
                    fail("P5", if (reason == PreflightReason.UNKNOWN) PreflightReason.DIAGNOSTICS_UNPARSED else reason,
                        "checkerPass failed without parseable diagnostics (exit ${result.exitCode})", output, duration)
                }
                else -> null
            }
        }

        private fun resetWorktree() {
            Git.run(worktree, "reset", "--hard")
            Git.run(worktree, "clean", "-fdx")
        }

        // --- P6 Test harness -----------------------------------------------

        private suspend fun p6(probe: JsonObject, checker: CheckerConfig, env: Map<String, String>): Boolean {
            val chosen = chooseTestClass(worktree, ctx, input)?.first ?: probeTestClass(probe)
            if (chosen == null) {
                return fail("P6", PreflightReason.TEST_XML_MISSING, "no runnable test class found in the test sources")
            }
            report = report.copy(testClass = chosen.value)
            clearTestResults()
            val (result, duration) = gradle(
                "P6-test-class",
                listOf(task("clean" + ctx.build.testTask.replaceFirstChar { it.uppercaseChar() }), task(ctx.build.testTask),
                    "--tests", chosen.value),
                checker, env = env,
            )
            val outcomes = findTestXml(worktree).flatMap { parseTestXml(it) }
            if (outcomes.isEmpty()) {
                val reason = when {
                    result.timedOut -> PreflightReason.TIMEOUT
                    PreflightSupport.requiresDocker(result.output) -> PreflightReason.TEST_REQUIRES_DOCKER
                    "No tests found for given includes" in result.output -> PreflightReason.TEST_XML_MISSING
                    else -> PreflightSupport.classify("P6", result.output, false).let {
                        if (it == PreflightReason.UNKNOWN) PreflightReason.TEST_XML_MISSING else it
                    }
                }
                return fail("P6", reason, "running ${chosen.value} produced no JUnit XML (exit ${result.exitCode})",
                    result.output, duration)
            }
            pass("P6", duration, "${chosen.value}: ${outcomes.size} tests, ${failing(outcomes).size} failing")
            return true
        }

        private fun probeTestClass(probe: JsonObject): Fqcn? {
            val dirs = dirs(probe, "test_src_dirs").filter { it.isDirectory }
            return dirs.firstNotNullOfOrNull { dir ->
                PreflightSupport.chooseTestClass(dir, "", listOf(""), emptyList())?.first
            }
        }

        // --- P7 Full suite -------------------------------------------------

        private suspend fun p7(checker: CheckerConfig, env: Map<String, String>): Boolean {
            clearTestResults()
            val (result, duration) = gradle(
                "P7-suite",
                listOf(task("clean" + ctx.build.testTask.replaceFirstChar { it.uppercaseChar() }), task(ctx.build.testTask)),
                checker, env = env,
            )
            val outcomes = findTestXml(worktree).flatMap { parseTestXml(it) }
            val failing = failing(outcomes)
            report = report.copy(
                suiteDurationMs = duration,
                suiteTests = outcomes.size,
                suiteFailures = failing.size,
                suiteFailingTests = failing.take(50).map { "${it.testClass.value}.${it.testMethod}" },
            )
            return when {
                result.timedOut -> fail("P7", PreflightReason.SUITE_TIMEOUT,
                    "the full suite did not finish within ${ctx.config.agent.timeoutMs / 1000} s", result.output, duration)
                outcomes.isEmpty() -> fail("P7",
                    if (PreflightSupport.requiresDocker(result.output)) PreflightReason.TEST_REQUIRES_DOCKER else PreflightReason.TEST_XML_MISSING,
                    "the full suite produced no JUnit XML (exit ${result.exitCode})", result.output, duration)
                failing.isNotEmpty() -> fail("P7",
                    if (failing.any { PreflightSupport.requiresDocker(it.failureMessage.orEmpty()) } ||
                        PreflightSupport.requiresDocker(result.output)) PreflightReason.TEST_REQUIRES_DOCKER
                    else PreflightReason.SUITE_FAILS_AT_BASELINE,
                    "${failing.size} of ${outcomes.size} tests fail at clean C-1", result.output, duration)
                else -> {
                    pass("P7", duration, "${outcomes.size} tests in ${duration / 1000} s")
                    true
                }
            }
        }

        private fun failing(outcomes: List<pipeline.ports.TestOutcome>) =
            outcomes.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }

        private fun clearTestResults() {
            worktree.walkTopDown().onEnter { it.name != ".git" }
                .filter { it.isDirectory && it.name == "test-results" }
                .toList()
                .forEach { FileOps.deleteRecursively(it) }
        }

        // --- helpers -------------------------------------------------------

        /** Harness files live next to the worktree, never inside it. */
        private fun harnessDir(): File =
            worktree.absoluteFile.parentFile.resolve(".preflight-harness").apply { mkdirs() }

        private fun dirs(probe: JsonObject, key: String): List<File> =
            (probe[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.let(::File)
            } ?: emptyList()

        /** The directory and package of the first Java source (sorted by path) in [dirs]. */
        private fun packageDir(dirs: List<File>): Pair<File, String>? =
            dirs.filter { it.isDirectory }.sortedBy { it.path }.firstNotNullOfOrNull { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".java") && it.name !in setOf("module-info.java", "package-info.java") }
                    .sortedBy { it.invariantSeparatorsPath }
                    .firstNotNullOfOrNull { file ->
                        Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(file.readText())?.groupValues?.get(1)
                            ?.let { file.parentFile to it }
                    }
            }

        private fun processors(probe: JsonObject): List<String> =
            (probe["processors"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()
    }

    private fun str(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private fun writeResource(name: String, target: File): File {
        target.parentFile.mkdirs()
        target.writeText(template(name))
        return target
    }

    private class TransientPreflightException(message: String) : RuntimeException(message)

    companion object {
        const val REPORT_FILE = "preflight_report.json"
        const val LOGS_DIR = "preflight_logs"
        const val MIN_GRADLE_MAJOR = 7
        const val MIN_GRADLE_MINOR = 0
        const val MAX_SOURCE_LEVEL = 21
        const val SUBTYPING_CHECKER = "org.checkerframework.common.subtyping.SubtypingChecker"
        const val PLANT_MARKER = "// LLTAS_PLANT"
        const val PLANTED_ERROR = "static @lltas.probe.ProbeBottom Object planted() { return new Object(); }"
        private const val LOG_LIMIT = 400_000
        private val UNSUPPORTED_PROCESSORS = listOf("org.projectlombok", "org.mapstruct")
        private val TEMPLATES = listOf(
            "probe.init.gradle", "ProbeTop.java", "ProbeBottom.java",
            "LltasProbe.java.template", "LltasProbeSupport.java.template",
        )

        fun template(name: String): String =
            PreflightStage::class.java.getResourceAsStream("/templates/preflight/$name")?.bufferedReader()?.use { it.readText() }
                ?: error("preflight template $name not found on classpath")

        private val PROBE_TEMPLATES_SHA: String by lazy { Hashing.sha256(TEMPLATES.joinToString("\n") { template(it) }) }
    }
}
