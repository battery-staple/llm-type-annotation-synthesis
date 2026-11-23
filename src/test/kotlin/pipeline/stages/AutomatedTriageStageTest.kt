package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.config.TriageSection
import pipeline.db.CommitTriageUpdate
import pipeline.db.EvaluationRunRecord
import pipeline.db.StepTrajectoryRecord
import pipeline.db.TelemetryProjection
import pipeline.cas.ArtifactNames
import pipeline.ports.BuildResult
import pipeline.ports.BuildRunner
import pipeline.ports.CheckerResult
import pipeline.ports.DatabasePort
import pipeline.ports.SingleShotFailure
import pipeline.ports.SingleShotLlmClient
import pipeline.ports.SingleShotLlmException
import pipeline.ports.SingleShotLlmResponse
import pipeline.ports.TestOutcome
import pipeline.ports.TestRunResult
import pipeline.ports.TestStatus
import pipeline.ports.TriageClassification
import pipeline.util.CommitMetadata
import pipeline.util.Git

class AutomatedTriageStageTest {

    private class RecordingDatabase : DatabasePort {
        val updates = mutableListOf<CommitTriageUpdate>()
        override suspend fun recordRunStart(run: EvaluationRunRecord) = Unit
        override suspend fun recordStep(step: StepTrajectoryRecord) = Unit
        override suspend fun recordStageTelemetry(projection: TelemetryProjection) = Unit
        override suspend fun recordVerdict(runId: RunId, verdict: EvalVerdict) = Unit
        override suspend fun finishRun(runId: RunId, status: String) = Unit
        override suspend fun readVerdict(runId: RunId): EvalVerdict? = null
        override suspend fun recordRetry(event: pipeline.db.RetryEvent) = Unit
        override suspend fun recordSubprocess(metric: pipeline.db.SubprocessMetricRecord) = Unit
        override suspend fun updateCommit(update: CommitTriageUpdate) {
            updates += update
        }
    }

    private fun initGitRepo(dir: File): Pair<String, String> {
        fun git(vararg args: String) {
            val res = Git.run(dir, *args)
            require(res.exitCode == 0) { "git ${args.joinToString(" ")} failed: ${res.output}" }
        }
        dir.mkdirs()
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test User")
        File(dir, "src/main/java/com/example").mkdirs()
        File(dir, "src/test/java/com/example").mkdirs()
        File(dir, "src/main/java/com/example/App.java").writeText("package com.example;\nclass App { int f() { return 1; } }\n")
        File(dir, "src/test/java/com/example/AppTest.java").writeText("package com.example;\nclass AppTest {}\n")
        git("add", ".")
        git("commit", "-m", "initial")
        val parent = Git.revParse(dir, "HEAD")

        File(dir, "src/main/java/com/example/App.java").writeText("package com.example;\nclass App { int f() { return 2; } }\n")
        File(dir, "src/test/java/com/example/AppTest.java").writeText(
            "package com.example;\nclass AppTest { void testFix() {} }\n"
        )
        git("add", ".")
        git("commit", "-m", "fix: return 2 in App.f")
        val commit = Git.revParse(dir, "HEAD")
        return parent to commit
    }

    private fun makeContext(
        tmpRoot: File,
        repoDir: File,
        db: DatabasePort,
        triageSection: TriageSection = TriageSection(mode = TriageMode.CRAWLER, depth = 10, limit = 5, bugfixThreshold = 0.75)
    ): RunContext = RunContext(
        config = ProjectConfig(triage = triageSection),
        build = BuildSection(),
        sourceCompatibility = "17",
        targetCompatibility = "17",
        runId = RunId("test-run"),
        sampleIndex = 0,
        sampleTemperature = 0.0,
        repoDir = repoDir,
        casDir = tmpRoot.resolve("cas").apply { mkdirs() },
        worktreeDir = tmpRoot.resolve("worktrees/ingestion").apply { mkdirs() },
        agentWorkspaceDir = tmpRoot.resolve("agent").apply { mkdirs() },
        database = db
    )

    private val unusedBuildRunner = object : BuildRunner {
        override suspend fun compile(worktreeDir: File, checker: pipeline.stages.CheckerConfig?, extraClasspath: List<File>): BuildResult =
            BuildResult(true, 0, "ok")

        override suspend fun runTests(
            worktreeDir: File,
            testFilter: TestCoordinate?,
            checker: pipeline.stages.CheckerConfig?,
            extraClasspath: List<File>
        ): TestRunResult = TestRunResult(
            success = true,
            exitCode = 0,
            outcomes = emptyList(),
            xmlPaths = emptyList(),
            compileFailed = false,
            testsFound = 1,
            output = ""
        )

        override suspend fun runChecker(
            worktreeDir: File,
            checkerHome: File,
            customQualsJar: File,
            processor: String,
            quals: List<Fqcn>,
            checkerCategory: CheckerCategory,
            guardrailsMode: GuardrailsMode
        ): CheckerResult = error("unused")
    }

    private fun triageEvent(content: String) = AgentTranscriptEvent(
        timestamp = "2026-01-01T00:00:00Z",
        eventType = AgentEventType.MESSAGE,
        turnIndex = 1,
        content = content
    )

    @Test
    fun `cache key does not depend on depth or limit`() {
        val tmp = Files.createTempDirectory("triage-cache-test-").toFile()
        try {
            val repo = tmp.resolve("repo")
            val (parent, commit) = initGitRepo(repo)
            val db = RecordingDatabase()
            val stage = AutomatedTriageStage(
                llmClient = object : SingleShotLlmClient {
                    override suspend fun classifyCommit(prompt: String, model: String, temperature: Double) =
                        error("unused")
                },
                buildRunner = unusedBuildRunner
            )
            val input = AutomatedTriageInput(
                benchmarkId = BenchmarkId.of("demo", CommitHash(commit)),
                worktree = IngestionWorktree(CommitHash(commit), CommitHash(parent), tmp.resolve("wt"), repo),
                commitMetadata = CommitMetadata(commit, listOf(parent), "2026-01-01T00:00:00Z", "fix")
            )
            val k1 = stage.computeCacheKey(
                makeContext(tmp, repo, db, TriageSection(mode = TriageMode.CRAWLER, depth = 10, limit = 1)),
                input
            )
            val k2 = stage.computeCacheKey(
                makeContext(tmp, repo, db, TriageSection(mode = TriageMode.CRAWLER, depth = 50, limit = 10)),
                input
            )
            assertEquals(k1, k2)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `merge commits are discarded without calling LLM`() = runBlocking {
        val tmp = Files.createTempDirectory("triage-merge-test-").toFile()
        try {
            val repo = tmp.resolve("repo")
            val (parent, commit) = initGitRepo(repo)
            val db = RecordingDatabase()
            var llmCalled = false
            val stage = AutomatedTriageStage(
                llmClient = object : SingleShotLlmClient {
                    override suspend fun classifyCommit(prompt: String, model: String, temperature: Double): SingleShotLlmResponse<TriageClassification> {
                        llmCalled = true
                        error("should not be called")
                    }
                },
                buildRunner = unusedBuildRunner
            )
            val input = AutomatedTriageInput(
                benchmarkId = BenchmarkId.of("demo", CommitHash(commit)),
                worktree = IngestionWorktree(CommitHash(commit), CommitHash(parent), tmp.resolve("wt"), repo),
                commitMetadata = CommitMetadata(commit, listOf(parent, "c".repeat(40)), "2026-01-01T00:00:00Z", "merge")
            )
            val res = stage.execute(makeContext(tmp, repo, db), input)
            assertFalse(llmCalled)
            assertTrue(res is StageResult.Discard)
            assertEquals(DiscardReason.MERGE_COMMIT, res.reason)
            assertEquals("discarded_merge_commit", db.updates.last().triageStatus)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `diffs over 20k lines are discarded without calling LLM`() = runBlocking {
        val tmp = Files.createTempDirectory("triage-large-test-").toFile()
        try {
            val repo = tmp.resolve("repo")
            initGitRepo(repo)
            val bigLines = (1..20_500).joinToString("\n") { "// line $it" }
            File(repo, "src/main/java/com/example/Big.java").writeText(bigLines + "\n")
            Git.run(repo, "add", ".")
            Git.run(repo, "commit", "-m", "huge commit")
            val commit = Git.revParse(repo, "HEAD")
            val parent = Git.revParse(repo, "HEAD~1")

            val db = RecordingDatabase()
            var llmCalled = false
            val stage = AutomatedTriageStage(
                llmClient = object : SingleShotLlmClient {
                    override suspend fun classifyCommit(prompt: String, model: String, temperature: Double): SingleShotLlmResponse<TriageClassification> {
                        llmCalled = true
                        error("should not be called")
                    }
                },
                buildRunner = unusedBuildRunner
            )
            val input = AutomatedTriageInput(
                benchmarkId = BenchmarkId.of("demo", CommitHash(commit)),
                worktree = IngestionWorktree(CommitHash(commit), CommitHash(parent), tmp.resolve("wt"), repo),
                commitMetadata = CommitMetadata(commit, listOf(parent), "2026-01-01T00:00:00Z", "huge commit")
            )
            val res = stage.execute(makeContext(tmp, repo, db), input)
            assertFalse(llmCalled)
            assertTrue(res is StageResult.Discard)
            assertEquals(DiscardReason.DIFF_TOO_LARGE, res.reason)
            assertEquals("discarded_diff_too_large", db.updates.last().triageStatus)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `non-bugfix or below-threshold classification is discarded`() = runBlocking {
        val tmp = Files.createTempDirectory("triage-nonbugfix-test-").toFile()
        try {
            val repo = tmp.resolve("repo")
            val (parent, commit) = initGitRepo(repo)
            val db = RecordingDatabase()
            val stage = AutomatedTriageStage(
                llmClient = object : SingleShotLlmClient {
                    override suspend fun classifyCommit(prompt: String, model: String, temperature: Double) =
                        SingleShotLlmResponse(
                            value = TriageClassification(
                                isBugfix = true,
                                bugfixScore = 0.50, // below default 0.75 threshold
                                category = CommitClassification.BUGFIX,
                                rationale = "weak confidence",
                                regressionTest = TestCoordinate(Fqcn("com.example.AppTest"), "testFix")
                            ),
                            usage = TokenUsage(),
                            latencyMs = 10L,
                            transcript = listOf(triageEvent("triage-transcript-marker"))
                        )
                },
                buildRunner = unusedBuildRunner
            )
            val input = AutomatedTriageInput(
                benchmarkId = BenchmarkId.of("demo", CommitHash(commit)),
                worktree = IngestionWorktree(CommitHash(commit), CommitHash(parent), tmp.resolve("wt"), repo),
                commitMetadata = CommitMetadata(commit, listOf(parent), "2026-01-01T00:00:00Z", "maybe fix")
            )
            val res = stage.execute(makeContext(tmp, repo, db), input)
            assertTrue(res is StageResult.Discard)
            assertEquals(DiscardReason.NOT_BUGFIX, res.reason)
            assertEquals("non_bugfix", db.updates.last().triageStatus)
            val transcriptFile = res.bundleDir!!.resolve(ArtifactNames.TRIAGE_TRANSCRIPT)
            assertTrue(transcriptFile.isFile)
            assertTrue(transcriptFile.readText().contains("triage-transcript-marker"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `verified bugfix commit passes C-1 fail and C pass and restores from cache`() = runBlocking {
        val tmp = Files.createTempDirectory("triage-success-test-").toFile()
        try {
            val repo = tmp.resolve("repo")
            val (parent, commit) = initGitRepo(repo)
            val db = RecordingDatabase()
            val testCoord = TestCoordinate(Fqcn("com.example.AppTest"), "testFix")
            val buildRunner = object : BuildRunner {
                override suspend fun compile(worktreeDir: File, checker: pipeline.stages.CheckerConfig?, extraClasspath: List<File>): BuildResult =
                    BuildResult(true, 0, "ok")

                override suspend fun runTests(
                    worktreeDir: File,
                    testFilter: TestCoordinate?,
                    checker: pipeline.stages.CheckerConfig?,
                    extraClasspath: List<File>
                ): TestRunResult {
                    val isC1 = worktreeDir.name == "c1"
                    val status = if (isC1) TestStatus.FAILED else TestStatus.PASSED
                    return TestRunResult(
                        success = !isC1,
                        exitCode = if (isC1) 1 else 0,
                        outcomes = listOf(TestOutcome(testCoord.testClass, testCoord.testMethod, status, 5L)),
                        xmlPaths = emptyList(),
                        compileFailed = false,
                        testsFound = 1,
                        output = ""
                    )
                }

                override suspend fun runChecker(
                    worktreeDir: File,
                    checkerHome: File,
                    customQualsJar: File,
                    processor: String,
                    quals: List<Fqcn>,
                    checkerCategory: CheckerCategory,
                    guardrailsMode: GuardrailsMode
                ): CheckerResult = error("unused")
            }
            val stage = AutomatedTriageStage(
                llmClient = object : SingleShotLlmClient {
                    override suspend fun classifyCommit(prompt: String, model: String, temperature: Double) =
                        SingleShotLlmResponse(
                            value = TriageClassification(
                                isBugfix = true,
                                bugfixScore = 0.92,
                                category = CommitClassification.BUGFIX,
                                rationale = "Fixes return value and adds testFix",
                                regressionTest = testCoord
                            ),
                            usage = TokenUsage(),
                            latencyMs = 10L,
                            transcript = listOf(triageEvent("verified-triage-transcript"))
                        )
                },
                buildRunner = buildRunner
            )
            val input = AutomatedTriageInput(
                benchmarkId = BenchmarkId.of("demo", CommitHash(commit)),
                worktree = IngestionWorktree(CommitHash(commit), CommitHash(parent), tmp.resolve("wt"), repo),
                commitMetadata = CommitMetadata(commit, listOf(parent), "2026-01-01T00:00:00Z", "fix: return 2")
            )
            val res = stage.execute(makeContext(tmp, repo, db), input)
            assertTrue(res is StageResult.Success)
            assertEquals(testCoord, res.data.regressionTest)
            assertTrue(res.data.testPatchFile.isFile)
            assertEquals("verified", db.updates.last().triageStatus)
            val transcriptFile = res.bundleDir!!.resolve(ArtifactNames.TRIAGE_TRANSCRIPT)
            assertTrue(transcriptFile.isFile)
            val transcriptText = transcriptFile.readText()
            assertTrue(transcriptText.contains("verified-triage-transcript"))
            assertTrue(res.artifactsManifest.artifacts.any { it.path == ArtifactNames.TRIAGE_TRANSCRIPT })

            val restored = stage.restoreCached(res.bundleDir!!, res.artifactsManifest)
            assertNotNull(restored)
            assertEquals(res.data.commitHash, restored.commitHash)
            assertEquals(res.data.regressionTest, restored.regressionTest)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `classification failure keeps the transcript for transient failure reporting`() = runBlocking {
        val tmp = Files.createTempDirectory("triage-failure-transcript-test-").toFile()
        try {
            val repo = tmp.resolve("repo")
            val (parent, commit) = initGitRepo(repo)
            val db = RecordingDatabase()
            val events = listOf(triageEvent("failed-triage-transcript"))
            val stage = AutomatedTriageStage(
                llmClient = object : SingleShotLlmClient {
                    override suspend fun classifyCommit(prompt: String, model: String, temperature: Double): SingleShotLlmResponse<TriageClassification> =
                        throw SingleShotLlmException("no JSON", transcript = events, reason = SingleShotFailure.NO_JSON)
                },
                buildRunner = unusedBuildRunner
            )
            val input = AutomatedTriageInput(
                benchmarkId = BenchmarkId.of("demo", CommitHash(commit)),
                worktree = IngestionWorktree(CommitHash(commit), CommitHash(parent), tmp.resolve("wt"), repo),
                commitMetadata = CommitMetadata(commit, listOf(parent), "2026-01-01T00:00:00Z", "fix")
            )
            val res = stage.execute(makeContext(tmp, repo, db), input)
            assertTrue(res is StageResult.TransientFailure)
            assertEquals(events, res.transcript)
            assertTrue(res.error is SingleShotLlmException)
            assertEquals(SingleShotFailure.NO_JSON, res.error.reason)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `OpenCodeSingleShotLlmClient ignores JSON inside INITIAL_PROMPT`() = runBlocking {
        val fakeRunner = object : pipeline.ports.AgentRunner {
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
                onEvent: (suspend (AgentTranscriptEvent) -> Unit)?
            ): pipeline.ports.AgentRunResult = pipeline.ports.AgentRunResult(
                exitCode = 1,
                stepsCounted = 0,
                transcript = listOf(
                    AgentTranscriptEvent(
                        timestamp = "2026-01-01T00:00:00Z",
                        eventType = AgentEventType.INITIAL_PROMPT,
                        turnIndex = 0,
                        content = """{"isBugfix": true, "bugfixScore": 0.87, "category": "bugfix", "rationale": "example", "regressionTest": {"testClass": "com.example.FooTest", "testMethod": "testBar"}}"""
                    ),
                    AgentTranscriptEvent(
                        timestamp = "2026-01-01T00:00:01Z",
                        eventType = AgentEventType.MESSAGE,
                        turnIndex = 1,
                        content = "stderr: error"
                    )
                ),
                timedOut = false
            )
        }
        val client = pipeline.runners.OpenCodeSingleShotLlmClient(agentRunner = fakeRunner)
        var thrown: SingleShotLlmException? = null
        try {
            client.classifyCommit("prompt", "model", 0.0)
        } catch (e: SingleShotLlmException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(2, thrown.transcript.size)
    }

    @Test
    fun `OpenCodeSingleShotLlmClient returns the runner transcript`() = runBlocking {
        val classification = """{"isBugfix": true, "bugfixScore": 0.9, "category": "bugfix", "rationale": "ok", "regressionTest": {"testClass": "com.example.FooTest", "testMethod": "testBar"}}"""
        val events = listOf(
            triageEvent("prompt"),
            AgentTranscriptEvent(
                timestamp = "2026-01-01T00:00:01Z",
                eventType = AgentEventType.MESSAGE,
                turnIndex = 1,
                content = classification
            )
        )
        val fakeRunner = object : pipeline.ports.AgentRunner {
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
                onEvent: (suspend (AgentTranscriptEvent) -> Unit)?
            ) = pipeline.ports.AgentRunResult(
                exitCode = 0,
                stepsCounted = 1,
                transcript = events,
                timedOut = false
            )
        }
        val response = pipeline.runners.OpenCodeSingleShotLlmClient(agentRunner = fakeRunner)
            .classifyCommit("prompt", "model", 0.0)
        assertEquals(events, response.transcript)
        assertEquals("testBar", response.value.regressionTest?.testMethod)
    }
}
