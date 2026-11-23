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
import pipeline.stages.RunId
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
        // The retry row is already telemetry; measuring its own insert would add
        // a second commit per retry without useful signal.
        transaction("record_retry", recordMetric = false) { c ->
            c.prepareStatement("""INSERT INTO retry_events(retry_id,run_id,step_id,stage,attempt_number,max_attempts,will_retry,delay_ms,error_class,error_message) VALUES(?,?,?,?,?,?,?,?,?,?)""").use { s ->
                s.setString(1, event.retryId); s.setString(2, event.runId); s.setString(3, event.stepId)
                s.setString(4, event.stage); s.setInt(5, event.attemptNumber); s.setInt(6, event.maxAttempts)
                s.setInt(7, if (event.willRetry) 1 else 0); s.setLong(8, event.delayMs)
                s.setString(9, event.errorClass); s.setString(10, event.errorMessage.take(1000)); s.executeUpdate()
            }
        }
    }

    override suspend fun recordPreflight(result: PreflightResultRecord) {
        val r = result.report
        transaction("record_preflight") { c ->
            c.prepareStatement(
                """INSERT INTO preflight_results
                   (preflight_id, run_id, benchmark_id, commit_hash, parent_commit, status, failed_check,
                    reason_code, message, gradle_version, runtime_jdk, compile_toolchain_jdk,
                    test_toolchain_jdk, source_level_declared, source_level_observed,
                    source_level_configured, test_class, suite_duration_ms, suite_tests, suite_failures,
                    backend, image_identity, duration_ms, cache_hit, cache_key, log_path, report)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
            ).use { s ->
                var i = 0
                fun str(v: String?) = s.setString(++i, v)
                fun num(v: Number?) { i++; if (v == null) s.setNull(i, Types.INTEGER) else s.setLong(i, v.toLong()) }
                str(result.preflightId); str(result.runId?.value); str(result.benchmarkId)
                str(result.commitHash); str(result.parentCommit); str(r.status); str(r.failedCheck)
                str(r.reasonCode); str(r.message.take(4000)); str(r.gradleVersion); str(r.runtimeJdk)
                str(r.compileToolchainJdk); str(r.testToolchainJdk); str(r.sourceLevelDeclared)
                str(r.sourceLevelObserved); str(r.sourceLevelConfigured); str(r.testClass)
                num(r.suiteDurationMs); num(r.suiteTests); num(r.suiteFailures); str(r.backend)
                str(r.imageIdentity); num(r.durationMs); num(if (result.cacheHit) 1 else 0)
                str(r.cacheKey); str(result.logPath)
                str(PipelineJson.compact.encodeToString(pipeline.stages.PreflightReport.serializer(), r))
                s.executeUpdate()
            }
        }
    }

    override suspend fun recordSubprocess(metric: SubprocessMetricRecord) {
        // The subprocess row is telemetry; don't double-commit to measure itself.
        transaction("record_subprocess", recordMetric = false) { c ->
            c.prepareStatement("""INSERT INTO subprocess_metrics(metric_id,run_id,step_id,stage,kind,tool,duration_ms,exit_code,timed_out,success,error_message) VALUES(?,?,?,?,?,?,?,?,?,?,?)""").use { s ->
                s.setString(1, metric.metricId); s.setString(2, metric.runId.value); s.setString(3, metric.stepId)
                s.setString(4, metric.stage); s.setString(5, metric.kind); s.setString(6, metric.tool)
                s.setLong(7, metric.durationMs); if (metric.exitCode == null) s.setNull(8, Types.INTEGER) else s.setInt(8, metric.exitCode)
                s.setInt(9, if (metric.timedOut) 1 else 0); s.setInt(10, if (metric.success) 1 else 0)
                s.setString(11, metric.errorMessage?.take(1000)); s.executeUpdate()
            }
        }
    }

    override suspend fun recordDiscardedAttempt(record: DiscardedAttemptRecord) {
        // A diagnostic write must be committed immediately: the worktree reset
        // that follows may destroy the only copy of the transcript source.
        transaction("record_discarded_attempt", recordMetric = false) { c ->
            c.prepareStatement(
                """INSERT INTO discarded_attempts(
                     attempt_id, run_id, step_id, stage, stage_attempt_number, internal_attempt_number,
                     started_at, completed_at, termination, discard_check, feedback, agent_failure_message,
                     exit_code, steps_counted, timed_out, prompt_path, transcript_path, patch_path,
                     patch_omitted_reason, diagnostics_path)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
            ).use { s ->
                s.setString(1, record.attemptId)
                s.setString(2, record.runId.value)
                s.setString(3, record.stepId)
                s.setString(4, record.stage)
                s.setInt(5, record.stageAttemptNumber)
                s.setInt(6, record.internalAttemptNumber)
                s.setString(7, record.startedAt)
                s.setString(8, record.completedAt)
                s.setString(9, record.termination.name.lowercase())
                s.setString(10, record.discardCheck.name.lowercase())
                s.setString(11, record.feedback)
                s.setString(12, record.agentFailureMessage)
                if (record.exitCode == null) s.setNull(13, Types.INTEGER) else s.setInt(13, record.exitCode)
                s.setInt(14, record.stepsCounted)
                s.setInt(15, if (record.timedOut) 1 else 0)
                s.setString(16, record.promptPath)
                s.setString(17, record.transcriptPath)
                s.setString(18, record.patchPath)
                s.setString(19, record.patchOmittedReason)
                s.setString(20, record.diagnosticsPath)
                s.executeUpdate()
            }
        }
    }

    override suspend fun readDiscardedAttempts(
        runId: RunId,
        stage: String?,
        stageAttemptNumber: Int?,
        internalAttemptNumber: Int?
    ): List<DiscardedAttemptRecord> = read("read_discarded_attempts") { c ->
        val filters = mutableListOf("run_id = ?")
        val values = mutableListOf<Any?>(runId.value)
        if (stage != null) { filters += "stage = ?"; values += stage }
        if (stageAttemptNumber != null) { filters += "stage_attempt_number = ?"; values += stageAttemptNumber }
        if (internalAttemptNumber != null) { filters += "internal_attempt_number = ?"; values += internalAttemptNumber }
        c.prepareStatement(
            """SELECT attempt_id, step_id, stage, stage_attempt_number, internal_attempt_number,
                      started_at, completed_at, termination, discard_check, feedback, agent_failure_message,
                      exit_code, steps_counted, timed_out, prompt_path, transcript_path, patch_path,
                      patch_omitted_reason, diagnostics_path
               FROM discarded_attempts WHERE ${filters.joinToString(" AND ")}
               ORDER BY stage_attempt_number, internal_attempt_number, started_at, attempt_id"""
        ).use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val exitCode = rs.getInt("exit_code").takeUnless { rs.wasNull() }
                        add(
                            DiscardedAttemptRecord(
                                attemptId = rs.getString("attempt_id"),
                                runId = runId,
                                stepId = rs.getString("step_id"),
                                stage = rs.getString("stage"),
                                stageAttemptNumber = rs.getInt("stage_attempt_number"),
                                internalAttemptNumber = rs.getInt("internal_attempt_number"),
                                startedAt = rs.getString("started_at"),
                                completedAt = rs.getString("completed_at"),
                                termination = runCatching {
                                    AgentSessionTermination.valueOf(rs.getString("termination").uppercase())
                                }.getOrDefault(AgentSessionTermination.NORMAL_EXIT),
                                discardCheck = runCatching {
                                    DiscardCheck.valueOf(rs.getString("discard_check").uppercase())
                                }.getOrDefault(DiscardCheck.OTHER),
                                feedback = rs.getString("feedback"),
                                agentFailureMessage = rs.getString("agent_failure_message"),
                                exitCode = exitCode,
                                stepsCounted = rs.getInt("steps_counted"),
                                timedOut = rs.getInt("timed_out") != 0,
                                promptPath = rs.getString("prompt_path"),
                                transcriptPath = rs.getString("transcript_path"),
                                patchPath = rs.getString("patch_path"),
                                patchOmittedReason = rs.getString("patch_omitted_reason"),
                                diagnosticsPath = rs.getString("diagnostics_path"),
                            )
                        )
                    }
                }
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
                    config_snapshot, guardrails_mode, verification_scope, status, started_at, batch_id, owner_pid)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"""
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
                s.setLong(13, ProcessHandle.current().pid())
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
     * Reconcile runs left `running` by a process that no longer exists, so
     * telemetry does not report phantom live runs. Several pipeline processes
     * may share this database concurrently, so a run is stale only when its
     * recorded `owner_pid` is dead; legacy rows without an owner are treated
     * as stale.
     */
    fun markStaleRunningRuns() {
        val now = Instant.now().toString()
        val stale = mutableListOf<String>()
        jdbc.prepareStatement("SELECT run_id, owner_pid FROM evaluation_runs WHERE status='running'").use { s ->
            s.executeQuery().use { rs ->
                while (rs.next()) {
                    val pid = rs.getLong(2).takeUnless { rs.wasNull() }
                    val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                    if (!alive) stale += rs.getString(1)
                }
            }
        }
        for (runId in stale) {
            jdbc.prepareStatement(
                "UPDATE evaluation_runs SET status='interrupted', completed_at=? WHERE run_id=?"
            ).use { s ->
                s.setString(1, now)
                s.setString(2, runId)
                s.executeUpdate()
            }
            jdbc.prepareStatement(
                "UPDATE step_trajectories SET success=0, completed_at=? WHERE run_id=? AND completed_at IS NULL"
            ).use { s ->
                s.setString(1, now)
                s.setString(2, runId)
                s.executeUpdate()
            }
        }
    }

    /**
     * Mark runs this process still owns as `interrupted`. Meant for a JVM
     * shutdown hook (Ctrl+C / SIGTERM): the orchestrator never reaches
     * [finishRun], and [markStaleRunningRuns] would only reconcile the row on
     * the next pipeline start, so the viewer would keep showing it as running.
     *
     * Uses its own connection because the shared one may be mid-transaction on
     * another thread, or already closed, when shutdown hooks run.
     */
    fun markOwnRunsInterrupted() {
        val now = Instant.now().toString()
        val pid = ProcessHandle.current().pid()
        openConnection(databaseFile).use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            c.prepareStatement(
                """UPDATE step_trajectories SET success=0, completed_at=?
                   WHERE completed_at IS NULL AND run_id IN
                     (SELECT run_id FROM evaluation_runs WHERE status='running' AND owner_pid=?)"""
            ).use { s ->
                s.setString(1, now)
                s.setLong(2, pid)
                s.executeUpdate()
            }
            c.prepareStatement(
                "UPDATE evaluation_runs SET status='interrupted', completed_at=? WHERE status='running' AND owner_pid=?"
            ).use { s ->
                s.setString(1, now)
                s.setLong(2, pid)
                s.executeUpdate()
            }
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
            // Make this run's buffered metrics visible without waiting for the
            // global threshold or process shutdown.
            flushOperationMetrics()
        }
    }

    override suspend fun readVerdict(runId: pipeline.stages.RunId): EvalVerdict? = read("read_verdict") { c ->
        c.prepareStatement("SELECT verdict FROM v_evaluation_verdicts WHERE run_id=?").use { s ->
            s.setString(1, runId.value)
            s.executeQuery().use { rs ->
                // triage_only and unsupported runs have a NULL verdict.
                if (!rs.next()) null else rs.getString(1)?.let(::verdictFromString)
            }
        }
    }

    override suspend fun updateCommit(update: CommitTriageUpdate) {
        transaction("update_commit") { c ->
            // Each entry owns exactly one '?' placeholder, so the bind ordinal
            // is always the entry's position + 1. Nulls are bound explicitly
            // with setNull, never skipped, which lets a verified update clear
            // stale discard metadata without shifting later placeholders.
            val columns = mutableListOf<Pair<String, Any?>>()
            fun add(column: String, value: Any?) {
                if (value != null) columns += column to value
            }
            add("triage_status", update.triageStatus)
            if (update.triageStatus == "verified") {
                // A commit discarded on an early triage attempt may be verified
                // on retry; discard metadata from that attempt must not linger.
                columns += "discard_reason" to null
                columns += "compiler_error_log" to null
            } else {
                add("discard_reason", update.discardReason)
                add("compiler_error_log", update.compilerErrorLog)
            }
            add("classification", update.classification?.name?.lowercase())
            add("bugfix_score", update.bugfixScore)
            add("regression_tests_found", update.regressionTestsFound)
            add("test_methods_extracted", update.testMethodsExtracted)
            add("regression_test_class", update.regressionTestClass)
            add("regression_test_method", update.regressionTestMethod)
            add("test_patch", update.testPatch)
            add("author_date", update.authorDate)
            add("commit_msg", update.commitMsg)
            if (columns.isEmpty()) return@transaction
            val sql = "UPDATE commits SET ${columns.joinToString(",") { "${it.first}=?" }} WHERE commit_hash=?"
            c.prepareStatement(sql).use { s ->
                columns.forEachIndexed { position, (_, value) ->
                    val index = position + 1
                    when (value) {
                        null -> s.setNull(index, Types.VARCHAR)
                        is Int -> s.setInt(index, value)
                        is Double -> s.setDouble(index, value)
                        else -> s.setString(index, value.toString())
                    }
                }
                s.setString(columns.size + 1, update.commitHash)
                s.executeUpdate()
            }
        }
    }

    fun connectionForReads(): Connection = jdbc

    private fun verdictFromString(raw: String): EvalVerdict {
        return EvalVerdict.fromStoredName(raw) ?: EvalVerdict.VERIFICATION_MISSING
    }
}
