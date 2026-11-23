package pipeline.db

import java.sql.Connection
import java.sql.Types

object TelemetryProjector {
    fun project(connection: Connection, p: TelemetryProjection) {
        insertSynthesisRun(connection, p.synthesisRun)
        p.qualifiers.forEach { insertQualifier(connection, it) }
        p.helpers.forEach { insertHelper(connection, it) }
        // checker_runs must precede compilation_events: the latter may carry a
        // checker_run_id foreign key.
        p.checkerRuns.forEach { insertCheckerRun(connection, it) }
        p.compilationEvents.forEach { insertCompilationEvent(connection, it) }
        p.agentTurns.forEach { insertAgentTurn(connection, it) }
        p.annotationPlacements.forEach { insertAnnotationPlacement(connection, it) }
        p.guardrailEvents.forEach { insertGuardrailEvent(connection, it) }
        p.checkerDiagnostics.forEach { insertCheckerDiagnostic(connection, it) }
        p.testRuns.forEach { insertTestRun(connection, it) }
        p.testExecutions.forEach { insertTestExecution(connection, it) }
        p.llmInvocations.forEach { insertLlmInvocation(connection, it) }
    }

    private fun insertSynthesisRun(c: Connection, r: SynthesisRunRecord?) {
        if (r == null) return
        c.prepareStatement(
            """INSERT OR REPLACE INTO synthesis_runs
               (synthesis_run_id, run_id, step_id, attempt_number, custom_quals_jar_path,
                placement_guide_path, qualifier_count, helpers_count, policy_rule_count,
                processor, checker_category, transcript_path, metrics_path, decision_source,
                decision_rationale, fallback_occurred)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.synthesisRunId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setInt(4, r.attemptNumber)
            s.setString(5, r.customQualsJarPath)
            s.setString(6, r.placementGuidePath)
            s.setInt(7, r.qualifierCount)
            s.setInt(8, r.helpersCount)
            s.setInt(9, r.policyRuleCount)
            s.setString(10, r.processor)
            s.setString(11, r.checkerCategory.name.lowercase())
            s.setString(12, r.transcriptPath)
            s.setString(13, r.metricsPath)
            s.setString(14, r.decisionSource)
            s.setString(15, r.decisionRationale)
            if (r.fallbackOccurred == null) s.setNull(16, Types.BOOLEAN) else s.setBoolean(16, r.fallbackOccurred)
            s.executeUpdate()
        }
    }

    private fun insertQualifier(c: Connection, r: QualifierRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO synthesized_qualifiers
               (qualifier_id, run_id, step_id, qualifier_name, target_type, subtypes_of, is_top, is_bottom)
               VALUES (?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.qualifierId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.qualifierName)
            s.setString(5, r.targetType)
            s.setString(6, r.subtypesOf)
            s.setInt(7, if (r.isTop) 1 else 0)
            s.setInt(8, if (r.isBottom) 1 else 0)
            s.executeUpdate()
        }
    }

    private fun insertHelper(c: Connection, r: HelperRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO synthesized_helpers
               (helper_id, run_id, step_id, helper_name, helper_type, signature, is_pure, call_sites_count)
               VALUES (?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.helperId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.helperName)
            s.setString(5, r.helperType)
            s.setString(6, r.signature)
            s.setInt(7, if (r.isPure) 1 else 0)
            s.setInt(8, r.callSitesCount)
            s.executeUpdate()
        }
    }

    private fun insertCompilationEvent(c: Connection, r: CompilationEventRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO compilation_events
               (event_id, run_id, step_id, checker_run_id, stage, turn_index, exit_code, diagnostics_text)
               VALUES (?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.eventId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.checkerRunId)
            s.setString(5, r.stage)
            s.setInt(6, r.turnIndex)
            s.setInt(7, r.exitCode)
            s.setString(8, r.diagnosticsText)
            s.executeUpdate()
        }
    }

    private fun insertAgentTurn(c: Connection, r: AgentTurnRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO agent_turns
               (turn_id, run_id, step_id, stage, turn_index, action_type, tool_name, tool_arguments, tool_result_summary)
               VALUES (?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.turnId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.stage)
            s.setInt(5, r.turnIndex)
            s.setString(6, r.actionType)
            s.setString(7, r.toolName)
            s.setString(8, r.toolArguments)
            s.setString(9, r.toolResultSummary)
            s.executeUpdate()
        }
    }

    private fun insertAnnotationPlacement(c: Connection, r: AnnotationPlacementRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO annotation_placements
               (placement_id, run_id, step_id, file_path, line_number, symbol, qualifier, target_tier)
               VALUES (?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.placementId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.filePath)
            s.setInt(5, r.lineNumber)
            s.setString(6, r.symbol)
            s.setString(7, r.qualifier)
            if (r.targetTier == null) s.setNull(8, Types.INTEGER) else s.setInt(8, r.targetTier)
            s.executeUpdate()
        }
    }

    private fun insertGuardrailEvent(c: Connection, r: GuardrailEventRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO guardrail_events
               (event_id, run_id, step_id, stage, turn_index, guard_type, violation_category,
                action_blocked, file_path, diff_snippet, feedback_message)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.eventId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.stage)
            s.setInt(5, r.turnIndex)
            s.setString(6, r.guardType)
            s.setString(7, r.violationCategory.name.lowercase())
            s.setInt(8, if (r.actionBlocked) 1 else 0)
            s.setString(9, r.filePath)
            s.setString(10, r.diffSnippet)
            s.setString(11, r.feedbackMessage)
            s.executeUpdate()
        }
    }

    private fun insertCheckerRun(c: Connection, r: CheckerRunRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO checker_runs
               (checker_run_id, run_id, step_id, stage, attempt_number, processor, checker_category,
                quals, exit_code, success, checker_invoked, error_count, warning_count, diagnostics_log_path, timed_out)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.checkerRunId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.stage)
            s.setInt(5, r.attemptNumber)
            s.setString(6, r.processor)
            s.setString(7, r.checkerCategory.name.lowercase())
            s.setString(8, r.quals)
            if (r.exitCode == null) s.setNull(9, Types.INTEGER) else s.setInt(9, r.exitCode)
            s.setInt(10, if (r.success) 1 else 0)
            s.setInt(11, if (r.checkerInvoked) 1 else 0)
            s.setInt(12, r.errorCount)
            s.setInt(13, r.warningCount)
            s.setString(14, r.diagnosticsLogPath)
            s.setInt(15, if (r.timedOut) 1 else 0)
            s.executeUpdate()
        }
    }

    private fun insertCheckerDiagnostic(c: Connection, r: CheckerDiagnosticRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO checker_diagnostics
               (diagnostic_id, run_id, step_id, checker_run_id, stage, turn_index, severity,
                checker_name, error_category, file_path, line_number, enclosing_method, message,
                localization_tier, is_baseline_error)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.diagnosticId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.checkerRunId)
            s.setString(5, r.stage)
            if (r.turnIndex == null) s.setNull(6, Types.INTEGER) else s.setInt(6, r.turnIndex)
            s.setString(7, r.severity)
            s.setString(8, r.checkerName)
            s.setString(9, r.errorCategory)
            s.setString(10, r.filePath)
            s.setInt(11, r.lineNumber)
            s.setString(12, r.enclosingMethod)
            s.setString(13, r.message)
            if (r.localizationTier == null) s.setNull(14, Types.INTEGER) else s.setInt(14, r.localizationTier)
            s.setInt(15, if (r.isBaselineError) 1 else 0)
            s.executeUpdate()
        }
    }

    private fun insertTestRun(c: Connection, r: TestRunRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO test_runs
               (test_run_id, run_id, step_id, stage, suite_kind, attempt_number, scope,
                exit_code, compile_failed, tests_found, passed_count, failed_count,
                skipped_count, success, timed_out, reported_failed_count, xml_path)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.testRunId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.stage)
            s.setString(5, r.suiteKind)
            s.setInt(6, r.attemptNumber)
            s.setString(7, r.scope.name.lowercase())
            s.setInt(8, r.exitCode)
            s.setInt(9, if (r.compileFailed) 1 else 0)
            s.setInt(10, r.testsFound)
            s.setInt(11, r.passedCount)
            s.setInt(12, r.failedCount)
            s.setInt(13, r.skippedCount)
            s.setInt(14, if (r.success) 1 else 0)
            s.setInt(15, if (r.timedOut) 1 else 0)
            s.setInt(16, r.reportedFailedCount)
            s.setString(17, r.xmlPath)
            s.executeUpdate()
        }
    }

    private fun insertTestExecution(c: Connection, r: TestExecutionRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO test_executions
               (execution_id, run_id, step_id, test_run_id, stage, turn_index, test_suite,
                test_class, test_method, is_regression_test, status, duration_ms, failure_message)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.executionId)
            s.setString(2, r.runId.value)
            s.setString(3, r.stepId)
            s.setString(4, r.testRunId)
            s.setString(5, r.stage)
            if (r.turnIndex == null) s.setNull(6, Types.INTEGER) else s.setInt(6, r.turnIndex)
            s.setString(7, r.testSuite)
            s.setString(8, r.testClass)
            s.setString(9, r.testMethod)
            s.setInt(10, if (r.isRegressionTest) 1 else 0)
            s.setString(11, r.status.name)
            s.setLong(12, r.durationMs)
            s.setString(13, r.failureMessage)
            s.executeUpdate()
        }
    }

    private fun insertLlmInvocation(c: Connection, r: LlmInvocationRecord) {
        c.prepareStatement(
            """INSERT OR REPLACE INTO llm_invocations
               (call_id, run_id, step_id, repo_id, stage, turn_index, model_name,
                prompt_tokens, completion_tokens, total_tokens, latency_ms)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { s ->
            s.setString(1, r.callId)
            s.setString(2, r.runId?.value)
            s.setString(3, r.stepId)
            s.setString(4, r.repoId)
            s.setString(5, r.stage)
            if (r.turnIndex == null) s.setNull(6, Types.INTEGER) else s.setInt(6, r.turnIndex)
            s.setString(7, r.modelName)
            s.setInt(8, r.promptTokens)
            s.setInt(9, r.completionTokens)
            s.setInt(10, r.totalTokens)
            s.setLong(11, r.latencyMs)
            s.executeUpdate()
        }
    }
}
