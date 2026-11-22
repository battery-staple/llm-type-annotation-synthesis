package pipeline.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import pipeline.stages.AgentTranscriptEvent
import pipeline.stages.EvalVerdict
import pipeline.util.PipelineJson

data class RetryEvent(
    val retryId: String, val runId: String, val stepId: String, val stage: String,
    val attemptNumber: Int, val maxAttempts: Int, val willRetry: Boolean, val delayMs: Long,
    val errorClass: String, val errorMessage: String
)

class SQLiteDatabaseManager(private val databaseFile: File) :
    DatabaseManager(openConnection(databaseFile)),
    pipeline.ports.DatabasePort {

    override suspend fun recordRetry(event: RetryEvent) {
        transaction("record_retry") { c ->
            c.prepareStatement("""INSERT INTO retry_events(retry_id,run_id,step_id,stage,attempt_number,max_attempts,will_retry,delay_ms,error_class,error_message) VALUES(?,?,?,?,?,?,?,?,?,?)""").use { s ->
                s.setString(1, event.retryId); s.setString(2, event.runId); s.setString(3, event.stepId)
                s.setString(4, event.stage); s.setInt(5, event.attemptNumber); s.setInt(6, event.maxAttempts)
                s.setInt(7, if (event.willRetry) 1 else 0); s.setLong(8, event.delayMs)
                s.setString(9, event.errorClass); s.setString(10, event.errorMessage.take(1000)); s.executeUpdate()
            }
        }
    }

    override suspend fun recordSubprocess(metric: SubprocessMetricRecord) {
        transaction("record_subprocess") { c ->
            c.prepareStatement("""INSERT INTO subprocess_metrics(metric_id,run_id,step_id,stage,kind,tool,duration_ms,exit_code,timed_out,success,error_message) VALUES(?,?,?,?,?,?,?,?,?,?,?)""").use { s ->
                s.setString(1, metric.metricId); s.setString(2, metric.runId.value); s.setString(3, metric.stepId)
                s.setString(4, metric.stage); s.setString(5, metric.kind); s.setString(6, metric.tool)
                s.setLong(7, metric.durationMs); if (metric.exitCode == null) s.setNull(8, Types.INTEGER) else s.setInt(8, metric.exitCode)
                s.setInt(9, if (metric.timedOut) 1 else 0); s.setInt(10, if (metric.success) 1 else 0)
                s.setString(11, metric.errorMessage?.take(1000)); s.executeUpdate()
            }
        }
    }

    companion object {
        private fun openConnection(file: File): Connection {
            file.parentFile?.mkdirs()
            Class.forName("org.sqlite.JDBC")
            return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        }
    }

    override suspend fun recordRunStart(run: EvaluationRunRecord) {
        transaction("record_run_start") { c ->
            c.prepareStatement(
                """INSERT OR IGNORE INTO repositories
                   (repo_id, name, git_url, default_branch, config_yaml)
                   VALUES (?,?,?,?,?)"""
            ).use { s ->
                s.setString(1, run.repoId)
                s.setString(2, run.repoName)
                s.setString(3, run.gitUrl)
                s.setString(4, run.defaultBranch)
                s.setString(5, run.configYaml)
                s.executeUpdate()
            }
            c.prepareStatement(
                """INSERT OR IGNORE INTO commits
                   (commit_hash, repo_id, parent_commit, author_date, commit_msg, triage_status)
                   VALUES (?,?,?,?,?,?)"""
            ).use { s ->
                s.setString(1, run.commitHash)
                s.setString(2, run.repoId)
                s.setString(3, run.parentCommit)
                s.setString(4, run.authorDate)
                s.setString(5, run.commitMsg)
                s.setString(6, "pending")
                s.executeUpdate()
            }
            c.prepareStatement(
                """INSERT OR IGNORE INTO evaluation_runs
                   (run_id, benchmark_id, commit_hash, sample_index, sample_temperature, cas_cache_key,
                    config_snapshot, guardrails_mode, verification_scope, status, started_at, batch_id)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"""
            ).use { s ->
                s.setString(1, run.runId.value)
                s.setString(2, run.benchmarkId)
                s.setString(3, run.commitHash)
                s.setInt(4, run.sampleIndex)
                s.setDouble(5, run.sampleTemperature)
                s.setString(6, run.casCacheKey)
                s.setString(7, run.configSnapshot)
                s.setString(8, run.guardrailsMode.name.lowercase())
                s.setString(9, run.verificationScope.name.lowercase())
                s.setString(10, run.status)
                s.setString(11, run.startedAt)
                s.setString(12, run.batchId)
                s.executeUpdate()
            }
        }
    }

    override suspend fun recordStep(step: StepTrajectoryRecord) {
        transaction("record_step") { c ->
            c.prepareStatement(
                """INSERT INTO step_trajectories
                   (step_id, run_id, step_number, attempt_number, step_name, cas_key, cache_hit,
                    success, artifacts_manifest, diff_patch, compiler_diagnostics, agent_transcript,
                    started_at, completed_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(step_id) DO UPDATE SET
                     cas_key=excluded.cas_key,
                     cache_hit=excluded.cache_hit,
                     success=excluded.success,
                     artifacts_manifest=excluded.artifacts_manifest,
                     diff_patch=excluded.diff_patch,
                     compiler_diagnostics=excluded.compiler_diagnostics,
                     agent_transcript=excluded.agent_transcript,
                     completed_at=excluded.completed_at"""
            ).use { s ->
                s.setString(1, step.stepId)
                s.setString(2, step.runId.value)
                s.setInt(3, step.stepNumber)
                s.setInt(4, step.attemptNumber)
                s.setString(5, step.stepName)
                s.setString(6, step.casKey)
                s.setInt(7, if (step.cacheHit) 1 else 0)
                s.setInt(8, if (step.success) 1 else 0)
                s.setString(9, step.artifactsManifest)
                s.setString(10, step.diffPatch)
                s.setString(11, step.compilerDiagnostics)
                s.setString(
                    12,
                    step.agentTranscript?.let {
                        PipelineJson.compact.encodeToString(ListSerializer(AgentTranscriptEvent.serializer()), it)
                    }
                )
                s.setString(13, step.startedAt)
                s.setString(14, step.completedAt)
                s.executeUpdate()
            }
        }
    }

    override suspend fun recordStageTelemetry(projection: TelemetryProjection) {
        transaction("record_stage_telemetry") { c -> TelemetryProjector.project(c, projection) }
    }

    override suspend fun recordVerdict(runId: pipeline.stages.RunId, verdict: EvalVerdict) {
        val viewVerdict = readVerdict(runId)
        if (viewVerdict != verdict) {
            mu.KotlinLogging.logger { }.warn {
                "Verdict mismatch for $runId: stage=$verdict view=$viewVerdict"
            }
        }
    }

    /**
     * The MVP executes one sample at a time, so any run still marked `running`
     * when a new run starts was killed or interrupted. Reconcile those rows so
     * telemetry does not report phantom live runs.
     */
    fun markStaleRunningRuns() {
        val now = Instant.now().toString()
        jdbc.prepareStatement(
            "UPDATE evaluation_runs SET status='interrupted', completed_at=? WHERE status='running'"
        ).use { statement ->
            statement.setString(1, now)
            statement.executeUpdate()
        }
        jdbc.prepareStatement(
            "UPDATE step_trajectories SET success=0, completed_at=? WHERE completed_at IS NULL"
        ).use { statement ->
            statement.setString(1, now)
            statement.executeUpdate()
        }
    }

    override suspend fun finishRun(runId: pipeline.stages.RunId, status: String) {
        // Run finalization must survive coroutine cancellation; otherwise an
        // interrupted run stays "running" in telemetry forever.
        withContext(NonCancellable) {
            transaction("finish_run") { c ->
                c.prepareStatement("UPDATE evaluation_runs SET status=?, completed_at=? WHERE run_id=?")
                    .use { s ->
                        s.setString(1, status)
                        s.setString(2, Instant.now().toString())
                        s.setString(3, runId.value)
                        s.executeUpdate()
                    }
            }
        }
    }

    override suspend fun readVerdict(runId: pipeline.stages.RunId): EvalVerdict? = read("read_verdict") { c ->
        c.prepareStatement("SELECT verdict FROM v_evaluation_verdicts WHERE run_id=?").use { s ->
            s.setString(1, runId.value)
            s.executeQuery().use { rs ->
                if (!rs.next()) null else verdictFromString(rs.getString(1))
            }
        }
    }

    override suspend fun updateCommit(update: CommitTriageUpdate) {
        transaction("update_commit") { c ->
            val assignments = mutableListOf<String>()
            val values = mutableListOf<Pair<Int, Any?>>()
            fun add(column: String, value: Any?) {
                if (value != null) {
                    assignments += "$column=?"
                    values += (assignments.size) to value
                }
            }
            add("triage_status", update.triageStatus)
            add("discard_reason", update.discardReason)
            add("compiler_error_log", update.compilerErrorLog)
            add("classification", update.classification?.name?.lowercase())
            add("bugfix_score", update.bugfixScore)
            add("regression_tests_found", update.regressionTestsFound)
            add("test_methods_extracted", update.testMethodsExtracted)
            add("regression_test_class", update.regressionTestClass)
            add("regression_test_method", update.regressionTestMethod)
            add("test_patch", update.testPatch)
            add("author_date", update.authorDate)
            add("commit_msg", update.commitMsg)
            if (assignments.isEmpty()) return@transaction
            val sql = "UPDATE commits SET ${assignments.joinToString(",")} WHERE commit_hash=?"
            c.prepareStatement(sql).use { s ->
                values.forEach { (index, value) ->
                    when (value) {
                        is Int -> s.setInt(index, value)
                        is Double -> s.setDouble(index, value)
                        else -> s.setString(index, value.toString())
                    }
                }
                s.setString(values.size + 1, update.commitHash)
                s.executeUpdate()
            }
        }
    }

    fun connectionForReads(): Connection = jdbc

    private fun verdictFromString(raw: String): EvalVerdict {
        return EvalVerdict.fromStoredName(raw) ?: EvalVerdict.VERIFICATION_MISSING
    }
}
