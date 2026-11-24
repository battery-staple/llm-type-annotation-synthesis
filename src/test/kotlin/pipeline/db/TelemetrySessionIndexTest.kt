package pipeline.db

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.stages.AgentEventType
import pipeline.stages.AgentTelemetry
import pipeline.stages.AgentTranscriptEvent
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.RunContext
import pipeline.stages.RunId
import pipeline.stages.StageName
import pipeline.stages.TokenUsage

class TelemetrySessionIndexTest {

    private val telemetryTables = listOf("llm_invocations", "guardrail_events", "compilation_events", "agent_turns")

    private fun idColumn(table: String) = when (table) {
        "llm_invocations" -> "call_id"
        "agent_turns" -> "turn_id"
        else -> "event_id"
    }

    /** Legacy databases have the telemetry tables but no session_index column. */
    @Test
    fun `legacy database gains session_index on all telemetry tables without data loss`() {
        Class.forName("org.sqlite.JDBC")
        val file: File = Files.createTempFile("pipeline-legacy-telemetry-sessions", ".db").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    telemetryTables.forEach { table ->
                        val id = idColumn(table)
                        statement.execute("CREATE TABLE $table ($id TEXT PRIMARY KEY, stage TEXT NOT NULL, turn_index INTEGER)")
                        statement.execute("INSERT INTO $table($id, stage, turn_index) VALUES('legacy-$table', 'application', 1)")
                    }
                }
            }

            SQLiteDatabaseManager(file).use { } // migrate, then create the current schema
            // Reopening must stay idempotent.
            SQLiteDatabaseManager(file).use { database ->
                telemetryTables.forEach { table ->
                    val columns = mutableSetOf<String>()
                    database.jdbc.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                            while (rs.next()) columns += rs.getString("name")
                        }
                    }
                    assertTrue("session_index" in columns, "expected $table.session_index, got: $columns")

                    val id = idColumn(table)
                    database.jdbc.createStatement().use { statement ->
                        statement.executeQuery("SELECT $id, session_index, turn_index FROM $table").use { rs ->
                            assertTrue(rs.next(), "legacy row missing from $table")
                            assertEquals("legacy-$table", rs.getString(1))
                            assertEquals(1, rs.getInt(2))
                            assertEquals(1, rs.getInt(3))
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `two agent sessions persist telemetry with distinct session_index values`() = runBlocking {
        val dir = Files.createTempDirectory("telemetry-session-index").toFile()
        try {
            SQLiteDatabaseManager(File(dir, "pipeline.db")).use { database ->
                val runId = RunId("two-session-run")
                val stepId = "two-session-step"
                database.jdbc.createStatement().use { statement ->
                    statement.execute("INSERT INTO repositories(repo_id,name,git_url,default_branch,config_yaml) VALUES('r','r','u','main','')")
                    statement.execute("INSERT INTO commits(commit_hash,repo_id,parent_commit,author_date,commit_msg,triage_status) VALUES('c','r','p','','m','verified')")
                    statement.execute("INSERT INTO evaluation_runs(run_id,benchmark_id,commit_hash,config_snapshot,guardrails_mode,verification_scope,status,started_at) VALUES('${runId.value}','b','c','{}','lenient','full_suite','running','now')")
                    statement.execute("INSERT INTO step_trajectories(step_id,run_id,step_number,attempt_number,step_name,success) VALUES('$stepId','${runId.value}',1,1,'application',1)")
                }

                val ctx = RunContext(
                    config = ProjectConfig(),
                    build = BuildSection(),
                    sourceCompatibility = "17",
                    targetCompatibility = "17",
                    runId = runId,
                    sampleIndex = 0,
                    sampleTemperature = 0.0,
                    repoDir = File("."),
                    casDir = File("./cas"),
                    worktreeDir = File("."),
                    agentWorkspaceDir = File("./agent"),
                    database = database,
                    stepId = stepId,
                )
                // Each INITIAL_PROMPT starts a new runAgent() call; turn indexes restart.
                val transcript = listOf(
                    AgentTranscriptEvent("t1", AgentEventType.INITIAL_PROMPT, 0, content = "session 1 prompt"),
                    AgentTranscriptEvent("t2", AgentEventType.TOOL_CALL, 1, toolName = "edit"),
                    AgentTranscriptEvent("t3", AgentEventType.STEP_FINISH, 1, tokenUsage = TokenUsage(10, 5, 15)),
                    AgentTranscriptEvent("t4", AgentEventType.INITIAL_PROMPT, 0, content = "session 2 prompt"),
                    AgentTranscriptEvent("t5", AgentEventType.MESSAGE, 1, content = "session 2 answer"),
                    AgentTranscriptEvent("t6", AgentEventType.STEP_FINISH, 1, tokenUsage = TokenUsage(20, 7, 27)),
                )

                database.recordStageTelemetry(
                    TelemetryProjection(
                        agentTurns = AgentTelemetry.turns(ctx, StageName.APPLICATION, transcript),
                        llmInvocations = AgentTelemetry.llmInvocations(ctx, StageName.APPLICATION, transcript, "model", 42L),
                        guardrailEvents = listOf(
                            GuardrailEventRecord(
                                "g1", runId, stepId, "application", 1, "ast_gate",
                                GuardrailViolationCategory.EXECUTABLE_EDIT, true, null, null, "blocked in session 1",
                            ),
                            GuardrailEventRecord(
                                "g2", runId, stepId, "application", 1, "ast_gate",
                                GuardrailViolationCategory.EXECUTABLE_EDIT, true, null, null, "blocked in session 2",
                                sessionIndex = 2,
                            ),
                        ),
                        compilationEvents = listOf(
                            CompilationEventRecord("c1", runId, stepId, null, "synthesis", 1, 0, "session 1 compile"),
                            CompilationEventRecord("c2", runId, stepId, null, "synthesis", 1, 0, "session 2 compile", sessionIndex = 2),
                        ),
                    )
                )

                telemetryTables.forEach { table ->
                    val rows = database.jdbc.prepareStatement("SELECT session_index, turn_index FROM $table ORDER BY session_index").use { statement ->
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.getInt(1) to rs.getInt(2)) }
                        }
                    }
                    assertEquals(listOf(1 to 1, 2 to 1), rows, "$table session indexes / turn indexes")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
