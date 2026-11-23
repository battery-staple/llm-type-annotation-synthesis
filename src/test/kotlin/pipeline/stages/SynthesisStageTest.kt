package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.ports.AgentRunner
import pipeline.ports.AgentRunResult
import pipeline.ports.DatabasePort
import pipeline.ports.JavacCompiler
import pipeline.ports.JavacResult

class SynthesisStageTest {
    private val customProcessor = ProjectConfig().checker.processor
    private val builtinProcessor = BuiltinCheckerCatalog.nullness.processor
    private val bug = BugContext(
        BenchmarkId("demo", "aaaaaaaa"),
        CommitHash("a".repeat(40)),
        CommitHash("b".repeat(40)),
        "",
        "A demo bug",
    )

    @Test
    fun `custom_only accepts an explicit custom decision`() = runBlocking {
        withFixture(AllowedCheckers.CUSTOM_ONLY) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val success = assertIs<StageResult.Success<SynthesizedLattice>>(result)
            assertEquals(CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY, success.data.checkerCategory)
            assertEquals(customProcessor, success.data.processor)
            assertTrue(success.data.qualifierClassNames.any { it.simpleName == "Child" || it.simpleName == "Root" })
            val metadataText = File(success.bundleDir, SynthesisStage.SYNTHESIS_METADATA).readText()
            val metadata = Json.parseToJsonElement(metadataText).jsonObject
            assertEquals("false", metadata.getValue("fallback_occurred").jsonPrimitive.content)
            assertEquals("agent", metadata.getValue("decision").jsonObject.getValue("source").jsonPrimitive.content)
            assertContains(metadataText, "custom_qualifier_hierarchy")
            assertTrue("builtin" !in metadataText)
            assertTrue(
                File(success.bundleDir, pipeline.cas.ArtifactNames.SYNTHESIS_DECISION).isFile,
                "accepted decision must be staged with the synthesis bundle"
            )
            val run = fixture.database.projections.single().synthesisRun!!
            assertEquals("agent", run.decisionSource)
            assertEquals(false, run.fallbackOccurred)
            assertTrue(run.decisionRationale.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `builtin_only accepts an explicit builtin decision without qualifier sources`() = runBlocking {
        withFixture(AllowedCheckers.BUILTIN_ONLY, sources = false, customDecision = false) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val success = assertIs<StageResult.Success<SynthesizedLattice>>(result)
            assertEquals(CheckerCategory.BUILTIN_CHECKER, success.data.checkerCategory)
            assertEquals(builtinProcessor, success.data.processor)
            assertTrue(success.data.qualifierClassNames.isEmpty())
            assertEquals(0, fixture.javac.calls)
        }
    }

    @Test
    fun `all accepts an explicit builtin decision`() = runBlocking {
        withFixture(AllowedCheckers.ALL, sources = false, customDecision = false) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val success = assertIs<StageResult.Success<SynthesizedLattice>>(result)
            assertEquals(CheckerCategory.BUILTIN_CHECKER, success.data.checkerCategory)
            assertEquals(builtinProcessor, success.data.processor)
            assertTrue(success.data.qualifierClassNames.isEmpty())
        }
    }

    @Test
    fun `missing decision is retried and fails instead of falling back`() = runBlocking {
        withFixture(AllowedCheckers.ALL, decision = false, sources = false) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val failure = assertIs<StageResult.TransientFailure>(result)
            assertEquals(3, fixture.agent.calls)
            assertContains(failure.error.message.orEmpty(), "synthesis_decision.json")
        }
    }

    @Test
    fun `malformed qualifiers never trigger a builtin fallback`() = runBlocking {
        withFixture(AllowedCheckers.ALL, malformed = true) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val failure = assertIs<StageResult.TransientFailure>(result)
            assertEquals(3, fixture.agent.calls)
            assertContains(failure.error.message.orEmpty(), "TYPE_USE")
            assertTrue("NullnessChecker" !in failure.error.message.orEmpty())
        }
    }

    @Test
    fun `builtin decision rejects custom qualifier sources`() = runBlocking {
        withFixture(AllowedCheckers.BUILTIN_ONLY, customDecision = false) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val failure = assertIs<StageResult.TransientFailure>(result)
            assertContains(failure.error.message.orEmpty(), "must not include custom qualifier sources")
        }
    }

    @Test
    fun `custom decision with mismatched processor is rejected`() = runBlocking {
        withFixture(AllowedCheckers.CUSTOM_ONLY, customDecision = true, processor = builtinProcessor) { fixture ->
            val result = fixture.stage.execute(fixture.context, SynthesisInput(bug))
            val failure = assertIs<StageResult.TransientFailure>(result)
            assertContains(failure.error.message.orEmpty(), "must name processor")
        }
    }

    @Test
    fun `builtin checker setting forks the synthesis cache key`() = runBlocking {
        withFixture(AllowedCheckers.ALL) { fixture ->
            val baseline = fixture.stage.computeCacheKey(fixture.context, SynthesisInput(bug))
            val alternateConfig = fixture.context.config.copy(
                synthesis = fixture.context.config.synthesis.copy(
                    builtinChecker = BuiltinCheckerCatalog.signedness.processor,
                ),
            )
            val alternate = fixture.stage.computeCacheKey(
                fixture.context.copy(config = alternateConfig),
                SynthesisInput(bug),
            )
            assertNotEquals(baseline, alternate)
        }
    }

    private data class Fixture(
        val context: RunContext,
        val stage: SynthesisStage,
        val agent: FakeAgent,
        val javac: FakeJavac,
        val database: RecordingDatabase,
    )

    private suspend fun withFixture(
        allowed: AllowedCheckers,
        decision: Boolean = true,
        sources: Boolean = true,
        customDecision: Boolean = true,
        malformed: Boolean = false,
        processor: String? = null,
        block: suspend (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("synthesis-stage-test").toFile()
        try {
            root.resolve("agent").mkdirs()
            root.resolve("cas").mkdirs()
            // The stage checks that the benchmark checkout remains clean; keep the
            // agent/cas workspaces outside the git repository.
            val repo = root.resolve("repo").also { it.mkdirs() }
            ProcessBuilder("git", "init", repo.absolutePath).redirectErrorStream(true).start().waitFor()
            root.resolve(".gitignore").writeText("agent/\ncas/\n")
            ProcessBuilder("git", "-C", root.absolutePath, "add", ".gitignore")
                .redirectErrorStream(true).start().waitFor()
            val agent = FakeAgent { cwd ->
                if (decision) writeDecision(cwd, customDecision, processor)
                writeGuide(cwd)
                if (sources) writeSources(cwd, malformed)
            }
            val javac = FakeJavac()
            val database = RecordingDatabase()
            val base = ProjectConfig()
            val config = base.copy(synthesis = base.synthesis.copy(allowedCheckers = allowed))
            val context = RunContext(
                config = config,
                build = BuildSection(),
                sourceCompatibility = "17",
                targetCompatibility = "17",
                runId = RunId("synthesis-test"),
                sampleIndex = 0,
                sampleTemperature = 0.0,
                repoDir = repo,
                casDir = root.resolve("cas"),
                worktreeDir = repo,
                agentWorkspaceDir = root.resolve("agent"),
                database = database,
            )
            block(Fixture(context, SynthesisStage(agent, javac), agent, javac, database))
        } finally {
            pipeline.util.FileOps.deleteRecursively(root)
        }
    }

    private fun writeDecision(cwd: File, custom: Boolean, processor: String?) {
        cwd.resolve(SynthesisDecision.FILE_NAME).writeText(
            """{"checker_category":"${if (custom) "custom_qualifier_hierarchy" else "builtin_checker"}","processor":"${processor ?: if (custom) customProcessor else builtinProcessor}","rationale":"Selected based on the bug domain."}""",
        )
    }

    private fun writeGuide(cwd: File) {
        cwd.resolve("placement_guide.md").writeText(
            """
            # Default
            - Use the default qualifier conservatively.
            # Source
            - Mark values at their origin.
            # Sink
            - Mark values at sensitive consumers.
            # Sanitizer
            - Mark validated transformations.
            # Semantics
            - Preserve the meaning of each value.
            """.trimIndent(),
        )
    }

    private fun writeSources(cwd: File, malformed: Boolean) {
        val dir = cwd.resolve("custom_quals_src")
        dir.mkdirs()
        val target = if (malformed) "@Target(ElementType.FIELD)" else "@Target(ElementType.TYPE_USE)"
        dir.resolve("Root.java").writeText(
            """
            package demo.qual;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import org.checkerframework.framework.qual.DefaultQualifierInHierarchy;
            $target
            @Retention(RetentionPolicy.RUNTIME)
            @DefaultQualifierInHierarchy
            public @interface Root {}
            """.trimIndent(),
        )
        dir.resolve("Child.java").writeText(
            """
            package demo.qual;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import org.checkerframework.framework.qual.SubtypeOf;
            @Target(ElementType.TYPE_USE)
            @Retention(RetentionPolicy.RUNTIME)
            @SubtypeOf(Root.class)
            public @interface Child {}
            """.trimIndent(),
        )
    }

    private class FakeAgent(private val sourcesWriter: (File) -> Unit) : AgentRunner {
        var calls = 0
        val prompts = mutableListOf<String>()

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
            onEvent: (suspend (AgentTranscriptEvent) -> Unit)?,
        ): AgentRunResult {
            calls++
            prompts += prompt
            sourcesWriter(cwd)
            return AgentRunResult(
                exitCode = 0,
                stepsCounted = 1,
                timedOut = false,
                transcript = listOf(
                    AgentTranscriptEvent(
                        timestamp = "2026-01-01T00:00:00Z",
                        eventType = AgentEventType.MESSAGE,
                        turnIndex = 1,
                        content = "ok",
                    ),
                ),
            )
        }
    }

    private class FakeJavac : JavacCompiler {
        var calls = 0
        override fun compileAndPackageJar(
            sourceFiles: List<File>,
            outputJar: File,
            classpath: List<File>,
        ): JavacResult {
            calls++
            // The real compiler writes the staging jar; the stage registers it as
            // an artifact, so the fake must create it too.
            outputJar.parentFile?.mkdirs()
            outputJar.writeBytes(byteArrayOf())
            return JavacResult(success = true, exitCode = 0, diagnostics = emptyList())
        }
    }

    private class RecordingDatabase : DatabasePort {
        val projections = mutableListOf<TelemetryProjection>()

        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) {
            projections += projection
        }

        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun updateCommit(update: CommitTriageUpdate) = Unit
        override suspend fun recordRetry(event: pipeline.db.RetryEvent) = Unit
        override suspend fun recordSubprocess(metric: pipeline.db.SubprocessMetricRecord) = Unit
    }
}
