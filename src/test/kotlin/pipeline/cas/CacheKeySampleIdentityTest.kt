package pipeline.cas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.ports.AgentRunner
import pipeline.ports.AgentRunResult
import pipeline.ports.BuildResult
import pipeline.ports.BuildRunner
import pipeline.ports.CheckerResult
import pipeline.ports.DatabasePort
import pipeline.ports.JavacCompiler
import pipeline.ports.JavacResult
import pipeline.ports.TestRunResult
import pipeline.stages.ApplicationInput
import pipeline.stages.ApplicationStage
import pipeline.stages.ApplicationTarget
import pipeline.stages.AutomatedTriageStage
import pipeline.stages.BenchmarkId
import pipeline.stages.BlindRepairTarget
import pipeline.stages.BugContext
import pipeline.stages.CheckerCategory
import pipeline.stages.CheckerConfig
import pipeline.stages.CommitHash
import pipeline.stages.EvalVerdict
import pipeline.stages.Fqcn
import pipeline.stages.GuardrailsMode
import pipeline.stages.IngestionInput
import pipeline.stages.IngestionStage
import pipeline.stages.IngestionWorktree
import pipeline.stages.ManifestTriageInput
import pipeline.stages.ManifestTriageStage
import pipeline.stages.RepairInput
import pipeline.stages.RepairResult
import pipeline.stages.RepairStage
import pipeline.stages.RunContext
import pipeline.stages.RunId
import pipeline.stages.StageName
import pipeline.stages.SynthesisInput
import pipeline.stages.SynthesisStage
import pipeline.stages.SynthesizedLattice
import pipeline.stages.TestCoordinate
import pipeline.stages.VerificationInput
import pipeline.stages.VerificationScope
import pipeline.stages.VerificationStage
import pipeline.stages.VerifiedSample

/**
 * Cache keys must not be partitioned by sample identity for deterministic
 * stages, and must be for LLM-backed ones.
 */
class CacheKeySampleIdentityTest {

    private val commit = CommitHash("a".repeat(40))
    private val parent = CommitHash("b".repeat(40))
    private val benchmarkId = BenchmarkId("demo", "aaaaaaa")
    private val missingFile = File("/nonexistent/cache-key-test")

    private fun context(
        sampleIndex: Int,
        temperature: Double = 0.7,
        build: BuildSection = BuildSection(),
        config: ProjectConfig = ProjectConfig()
    ) = RunContext(
        config = config,
        build = build,
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("run-$sampleIndex"),
        sampleIndex = sampleIndex,
        sampleTemperature = temperature,
        repoDir = File("/nonexistent/repo"),
        casDir = File("/nonexistent/cas"),
        worktreeDir = File("/nonexistent/worktree"),
        agentWorkspaceDir = File("/nonexistent/agent"),
        database = noopDatabase
    )

    private val noopRunner = object : BuildRunner {
        override suspend fun compile(worktreeDir: File, customQualsJar: File?, extraClasspath: List<File>): BuildResult =
            error("build port must not be used for cache-key computation")

        override suspend fun runTests(
            worktreeDir: File,
            testFilter: TestCoordinate?,
            customQualsJar: File?,
            extraClasspath: List<File>
        ): TestRunResult = error("build port must not be used for cache-key computation")

        override suspend fun runChecker(
            worktreeDir: File,
            checkerHome: File,
            customQualsJar: File,
            processor: String,
            quals: List<Fqcn>,
            checkerCategory: CheckerCategory,
            guardrailsMode: GuardrailsMode
        ): CheckerResult = error("build port must not be used for cache-key computation")
    }

    private val noopAgent = object : AgentRunner {
        override suspend fun runAgent(
            prompt: String,
            cwd: File,
            agentRole: String,
            model: String,
            maxSteps: Int,
            timeoutSeconds: Long,
            seed: Long,
            temperature: Double,
            environment: Map<String, String>,
            onEvent: (suspend (pipeline.stages.AgentTranscriptEvent) -> Unit)?
        ): AgentRunResult = error("agent port must not be used for cache-key computation")
    }

    private val noopJavac = object : JavacCompiler {
        override fun compileAndPackageJar(sourceFiles: List<File>, outputJar: File, classpath: List<File>): JavacResult =
            error("javac port must not be used for cache-key computation")
    }

    private fun lattice(jar: File = missingFile) = SynthesizedLattice(
        customQualsJar = jar,
        placementGuideFile = missingFile,
        customQualsSrcDir = missingFile,
        qualifierClassNames = listOf(Fqcn("demo.qual.Demo")),
        qualifiersCount = 1,
        processor = "demo.qual.DemoChecker",
        checkerCategory = CheckerCategory.BUILTIN_CHECKER
    )

    private fun verificationInput(
        lattice: SynthesizedLattice = lattice(),
        scope: VerificationScope = VerificationScope.REGRESSION_ONLY
    ) = VerificationInput(
        sample = VerifiedSample(
            benchmarkId, commit, parent,
            TestCoordinate(Fqcn("demo.RegressionTest"), "testBug"),
            missingFile
        ),
        repair = RepairResult(missingFile, missingFile, missingFile, missingFile, remainingTypeErrors = 0),
        lattice = lattice,
        annotationsPatch = missingFile,
        worktreeDir = File("/nonexistent/verification"),
        verificationScope = scope
    )

    private val noopLlmClient = object : pipeline.ports.SingleShotLlmClient {
        override suspend fun classifyCommit(
            prompt: String,
            model: String,
            temperature: Double
        ): pipeline.ports.SingleShotLlmResponse<pipeline.ports.TriageClassification> =
            error("llm client must not be used for cache-key computation")
    }

    @Test
    fun `deterministic stages declare themselves deterministic`() {
        assertTrue(IngestionStage(noopRunner).isDeterministic)
        assertTrue(ManifestTriageStage(noopRunner).isDeterministic)
        assertTrue(VerificationStage(noopRunner).isDeterministic)
        assertFalse(SynthesisStage(noopAgent, noopJavac).isDeterministic)
        assertFalse(ApplicationStage(noopAgent, noopRunner).isDeterministic)
        assertFalse(RepairStage(noopAgent, noopRunner).isDeterministic)
        assertFalse(AutomatedTriageStage(noopLlmClient, noopRunner).isDeterministic)
    }

    @Test
    fun `ingestion key ignores sample identity`() {
        val stage = IngestionStage(noopRunner)
        val input = IngestionInput(benchmarkId, "/nonexistent/repo", commit, parent)

        assertEquals(
            stage.computeCacheKey(context(sampleIndex = 0), input),
            stage.computeCacheKey(context(sampleIndex = 3, temperature = 0.1), input)
        )
    }

    @Test
    fun `manifest triage key ignores sample identity`() {
        val stage = ManifestTriageStage(noopRunner)
        val input = ManifestTriageInput(
            benchmarkId = benchmarkId,
            worktree = IngestionWorktree(commit, parent, File("/nonexistent/worktree")),
            regressionTest = TestCoordinate(Fqcn("demo.RegressionTest"), "testBug")
        )

        assertEquals(
            stage.computeCacheKey(context(sampleIndex = 0), input),
            stage.computeCacheKey(context(sampleIndex = 7, temperature = 1.0), input)
        )
    }

    @Test
    fun `verification key ignores sample identity`() {
        val stage = VerificationStage(noopRunner)
        val input = verificationInput()

        assertEquals(
            stage.computeCacheKey(context(sampleIndex = 0), input),
            stage.computeCacheKey(context(sampleIndex = 5, temperature = 0.2), input)
        )
    }

    @Test
    fun `verification key covers custom qualifier jar bytes`() {
        val stage = VerificationStage(noopRunner)
        val jarA = File.createTempFile("quals-a", ".jar").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val jarB = File.createTempFile("quals-b", ".jar").apply {
            writeBytes(byteArrayOf(1, 2, 4))
            deleteOnExit()
        }

        assertNotEquals(
            stage.computeCacheKey(context(sampleIndex = 0), verificationInput(lattice(jarA))),
            stage.computeCacheKey(context(sampleIndex = 0), verificationInput(lattice(jarB)))
        )
    }

    @Test
    fun `deterministic keys cover every build setting the runner reads`() {
        val ingestion = IngestionStage(noopRunner)
        val triage = ManifestTriageStage(noopRunner)
        val verification = VerificationStage(noopRunner)
        val ingestionInput = IngestionInput(benchmarkId, "/nonexistent/repo", commit, parent)
        val triageInput = ManifestTriageInput(
            benchmarkId = benchmarkId,
            worktree = IngestionWorktree(commit, parent, File("/nonexistent/worktree")),
            regressionTest = TestCoordinate(Fqcn("demo.RegressionTest"), "testBug")
        )
        val verificationStageInput = verificationInput()

        fun keys(build: BuildSection) = listOf(
            ingestion.computeCacheKey(context(sampleIndex = 0, build = build), ingestionInput),
            triage.computeCacheKey(context(sampleIndex = 0, build = build), triageInput),
            verification.computeCacheKey(context(sampleIndex = 0, build = build), verificationStageInput)
        )

        val baseline = keys(BuildSection())
        val variants = listOf(
            BuildSection(tool = "maven"),
            BuildSection(wrapperExecutable = "./gradlew-custom"),
            BuildSection(subproject = "module"),
            BuildSection(compileTask = "buildJava"),
            BuildSection(testTask = "check"),
            BuildSection(checkerTask = "customCheckerPass")
        )
        variants.forEach { variant ->
            assertNotEquals(keys(variant), baseline, "build change must fork the key: $variant")
        }
    }

    @Test
    fun `runner timeouts fork stage cache keys`() {
        val input = IngestionInput(benchmarkId, "/nonexistent/repo", commit, parent)
        val stage = IngestionStage(noopRunner)
        val defaultKey = stage.computeCacheKey(context(0), input)
        val changedTimeout = ProjectConfig().copy(agent = ProjectConfig().agent.copy(timeoutMs = 900_000))
        assertNotEquals(defaultKey, stage.computeCacheKey(context(0, config = changedTimeout), input))

        val synthesis = SynthesisStage(noopAgent, noopJavac)
        val bugInput = SynthesisInput(BugContext(benchmarkId, commit, parent, "", "demo bug"))
        val internetEnabled = ProjectConfig().copy(repair = ProjectConfig().repair.copy(internetAccess = true))
        assertNotEquals(
            synthesis.computeCacheKey(context(0), bugInput),
            synthesis.computeCacheKey(context(0, config = internetEnabled), bugInput)
        )
    }

    @Test
    fun `agent stages keep sample identity in the key`() {
        val synthesis = SynthesisStage(noopAgent, noopJavac)
        val bug = BugContext(benchmarkId, commit, parent, "", "demo bug")

        assertNotEquals(
            synthesis.computeCacheKey(context(sampleIndex = 0), SynthesisInput(bug)),
            synthesis.computeCacheKey(context(sampleIndex = 1), SynthesisInput(bug))
        )

        val repair = RepairStage(noopAgent, noopRunner)
        val repairInput = RepairInput(
            target = BlindRepairTarget(parent, File("/nonexistent/repair")),
            checker = CheckerConfig(missingFile, "demo.qual.DemoChecker", CheckerCategory.BUILTIN_CHECKER, listOf(Fqcn("demo.qual.Demo"))),
            initialCheckerErrorsLog = missingFile,
            annotationsPatch = missingFile
        )
        assertNotEquals(
            repair.computeCacheKey(context(sampleIndex = 0), repairInput),
            repair.computeCacheKey(context(sampleIndex = 1), repairInput)
        )

        val application = ApplicationStage(noopAgent, noopRunner)
        val appInput = ApplicationInput(ApplicationTarget(parent, File("/nonexistent/application")), lattice())
        assertNotEquals(
            application.computeCacheKey(context(sampleIndex = 0), appInput),
            application.computeCacheKey(context(sampleIndex = 1), appInput)
        )
    }

    @Test
    fun `key primitive honours includeSampleIdentity`() {
        val fields = mapOf("example" to 1)
        val withSeed0 = CacheKeys.compute(context(sampleIndex = 0), StageName.SYNTHESIS, fields, emptyList(), emptyMap(), null)
        val withSeed1 = CacheKeys.compute(context(sampleIndex = 1), StageName.SYNTHESIS, fields, emptyList(), emptyMap(), null)
        assertNotEquals(withSeed0, withSeed1)

        val withoutSeed0 = CacheKeys.compute(
            context(sampleIndex = 0), StageName.SYNTHESIS, fields, emptyList(), emptyMap(), null,
            includeSampleIdentity = false
        )
        val withoutSeed1 = CacheKeys.compute(
            context(sampleIndex = 1), StageName.SYNTHESIS, fields, emptyList(), emptyMap(), null,
            includeSampleIdentity = false
        )
        assertEquals(withoutSeed0, withoutSeed1)
    }

    private val noopDatabase = object : DatabasePort {
        override suspend fun recordRunStart(run: pipeline.db.EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: pipeline.db.StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: pipeline.db.TelemetryProjection) = Unit
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: pipeline.db.CommitTriageUpdate) = Unit
    }
}
