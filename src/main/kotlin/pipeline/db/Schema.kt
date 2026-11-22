package pipeline.db

object Schema {
    val DDL: String = """
-- Evaluated Git Repositories
CREATE TABLE IF NOT EXISTS repositories (
    repo_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    git_url TEXT NOT NULL,
    default_branch TEXT NOT NULL,
    config_yaml TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Commits Evaluated in Ingestion & Triage Stage
CREATE TABLE IF NOT EXISTS commits (
    commit_hash TEXT PRIMARY KEY,
    repo_id TEXT NOT NULL REFERENCES repositories(repo_id),
    parent_commit TEXT NOT NULL,
    author_date TEXT NOT NULL,
    commit_msg TEXT NOT NULL,
    pr_number INTEGER,
    pr_title TEXT,
    pr_body TEXT,
    classification TEXT,
    bugfix_score REAL,
    regression_tests_found INTEGER NOT NULL DEFAULT 0,
    test_methods_extracted INTEGER NOT NULL DEFAULT 0,
    regression_test_class TEXT,
    regression_test_method TEXT,
    test_patch TEXT,
    triage_status TEXT NOT NULL,
    discard_reason TEXT,
    compiler_error_log TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Pipeline Evaluation Runs
CREATE TABLE IF NOT EXISTS evaluation_runs (
    run_id TEXT PRIMARY KEY,
    benchmark_id TEXT NOT NULL,
    commit_hash TEXT NOT NULL REFERENCES commits(commit_hash),
    sample_index INTEGER NOT NULL DEFAULT 0,
    sample_temperature REAL NOT NULL DEFAULT 0.0,
    cas_cache_key TEXT,
    config_snapshot JSON NOT NULL,
    guardrails_mode TEXT NOT NULL,
    verification_scope TEXT NOT NULL DEFAULT 'full_suite',
    status TEXT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    batch_id TEXT,
    owner_pid INTEGER
);

CREATE INDEX IF NOT EXISTS idx_evaluation_runs_benchmark ON evaluation_runs(benchmark_id);
CREATE INDEX IF NOT EXISTS idx_evaluation_runs_batch ON evaluation_runs(batch_id);

-- Individual Step Trajectories
CREATE TABLE IF NOT EXISTS step_trajectories (
    step_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_number INTEGER NOT NULL,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    step_name TEXT NOT NULL,
    cas_key TEXT,
    cache_hit BOOLEAN NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL,
    artifacts_manifest JSON,
    diff_patch TEXT,
    compiler_diagnostics TEXT,
    agent_transcript JSON,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id, step_name, attempt_number)
);

-- Synthesis Artifacts & Metrics
CREATE TABLE IF NOT EXISTS synthesis_runs (
    synthesis_run_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    custom_quals_jar_path TEXT NOT NULL,
    placement_guide_path TEXT NOT NULL,
    qualifier_count INTEGER NOT NULL,
    helpers_count INTEGER NOT NULL DEFAULT 0,
    policy_rule_count INTEGER NOT NULL DEFAULT 0,
    processor TEXT NOT NULL,
    checker_category TEXT NOT NULL,
    transcript_path TEXT,
    metrics_path TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Checker Framework Invocations
CREATE TABLE IF NOT EXISTS checker_runs (
    checker_run_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    stage TEXT NOT NULL,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    processor TEXT NOT NULL,
    checker_category TEXT NOT NULL,
    quals TEXT NOT NULL,
    exit_code INTEGER,
    success BOOLEAN NOT NULL DEFAULT 0,
    checker_invoked BOOLEAN NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    warning_count INTEGER NOT NULL DEFAULT 0,
    diagnostics_log_path TEXT,
    timed_out BOOLEAN NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Test Invocations
CREATE TABLE IF NOT EXISTS test_runs (
    test_run_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    stage TEXT NOT NULL,
    suite_kind TEXT NOT NULL,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    scope TEXT NOT NULL,
    exit_code INTEGER NOT NULL,
    compile_failed BOOLEAN NOT NULL DEFAULT 0,
    tests_found INTEGER NOT NULL DEFAULT 0,
    passed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT 0,
    timed_out BOOLEAN NOT NULL DEFAULT 0,
    reported_failed_count INTEGER NOT NULL DEFAULT 0,
    xml_path TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Synthesized Qualifiers in Type Lattice
CREATE TABLE IF NOT EXISTS synthesized_qualifiers (
    qualifier_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    qualifier_name TEXT NOT NULL,
    target_type TEXT NOT NULL,
    subtypes_of TEXT,
    is_top BOOLEAN NOT NULL DEFAULT 0,
    is_bottom BOOLEAN NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Synthesized Validator/Coercion Pure Helpers
CREATE TABLE IF NOT EXISTS synthesized_helpers (
    helper_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    helper_name TEXT NOT NULL,
    helper_type TEXT NOT NULL,
    signature TEXT NOT NULL,
    is_pure BOOLEAN NOT NULL DEFAULT 1,
    call_sites_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Compiler Feedback Passes
CREATE TABLE IF NOT EXISTS compilation_events (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    checker_run_id TEXT REFERENCES checker_runs(checker_run_id),
    stage TEXT NOT NULL,
    turn_index INTEGER NOT NULL DEFAULT 1,
    exit_code INTEGER NOT NULL,
    diagnostics_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Turn-by-Turn Agent Trajectories
CREATE TABLE IF NOT EXISTS agent_turns (
    turn_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    stage TEXT NOT NULL,
    turn_index INTEGER NOT NULL,
    action_type TEXT NOT NULL,
    tool_name TEXT,
    tool_arguments JSON,
    tool_result_summary TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Concrete Annotation Placements
CREATE TABLE IF NOT EXISTS annotation_placements (
    placement_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    file_path TEXT NOT NULL,
    line_number INTEGER NOT NULL,
    symbol TEXT NOT NULL,
    qualifier TEXT NOT NULL,
    target_tier INTEGER DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Anti-Cheat and AST Gate Interventions
CREATE TABLE IF NOT EXISTS guardrail_events (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    stage TEXT NOT NULL,
    turn_index INTEGER NOT NULL,
    guard_type TEXT NOT NULL,
    violation_category TEXT NOT NULL,
    action_blocked BOOLEAN NOT NULL DEFAULT 1,
    file_path TEXT,
    diff_snippet TEXT,
    feedback_message TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Checker Framework Diagnostics
CREATE TABLE IF NOT EXISTS checker_diagnostics (
    diagnostic_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    checker_run_id TEXT NOT NULL REFERENCES checker_runs(checker_run_id),
    stage TEXT NOT NULL,
    turn_index INTEGER,
    severity TEXT NOT NULL DEFAULT 'error',
    checker_name TEXT NOT NULL,
    error_category TEXT NOT NULL,
    file_path TEXT NOT NULL,
    line_number INTEGER NOT NULL,
    enclosing_method TEXT,
    message TEXT NOT NULL,
    localization_tier INTEGER,
    is_baseline_error BOOLEAN NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Granular Test Executions
CREATE TABLE IF NOT EXISTS test_executions (
    execution_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES evaluation_runs(run_id),
    step_id TEXT NOT NULL REFERENCES step_trajectories(step_id),
    test_run_id TEXT NOT NULL REFERENCES test_runs(test_run_id),
    stage TEXT NOT NULL,
    turn_index INTEGER,
    test_suite TEXT NOT NULL,
    test_class TEXT NOT NULL,
    test_method TEXT NOT NULL,
    is_regression_test BOOLEAN NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    failure_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Fine-Grained LLM Invocations
CREATE TABLE IF NOT EXISTS llm_invocations (
    call_id TEXT PRIMARY KEY,
    run_id TEXT REFERENCES evaluation_runs(run_id),
    step_id TEXT REFERENCES step_trajectories(step_id),
    repo_id TEXT REFERENCES repositories(repo_id),
    stage TEXT NOT NULL,
    turn_index INTEGER,
    model_name TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens INTEGER NOT NULL,
    latency_ms INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS db_operation_metrics (
    metric_id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation TEXT NOT NULL,
    duration_ms REAL NOT NULL,
    success BOOLEAN NOT NULL,
    affected_rows INTEGER,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS retry_events (
    retry_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    stage TEXT NOT NULL,
    attempt_number INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    will_retry BOOLEAN NOT NULL,
    delay_ms INTEGER NOT NULL,
    error_class TEXT NOT NULL,
    error_message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS subprocess_metrics (
    metric_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, step_id TEXT NOT NULL, stage TEXT NOT NULL,
    kind TEXT NOT NULL, tool TEXT NOT NULL, duration_ms INTEGER NOT NULL, exit_code INTEGER,
    timed_out BOOLEAN NOT NULL, success BOOLEAN NOT NULL, error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_subprocess_metrics_run_stage_kind ON subprocess_metrics(run_id, stage, kind);
CREATE VIEW IF NOT EXISTS v_subprocess_summary AS
SELECT stage, kind, COUNT(*) calls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) failures,
 SUM(CASE WHEN timed_out=1 THEN 1 ELSE 0 END) timeouts,
 AVG(duration_ms) avg_duration_ms, MAX(duration_ms) max_duration_ms, SUM(duration_ms) total_duration_ms
FROM subprocess_metrics GROUP BY stage, kind;
CREATE VIEW IF NOT EXISTS v_db_operation_summary AS
SELECT operation, COUNT(*) AS calls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) AS failures,
       AVG(duration_ms) AS avg_duration_ms, MAX(duration_ms) AS max_duration_ms
FROM db_operation_metrics GROUP BY operation;
CREATE VIEW IF NOT EXISTS v_retry_summary AS
SELECT stage, error_class, COUNT(*) AS attempts,
       SUM(CASE WHEN will_retry=1 THEN 1 ELSE 0 END) AS retries
FROM retry_events GROUP BY stage, error_class;

DROP VIEW IF EXISTS v_evaluation_verdicts;
CREATE VIEW v_evaluation_verdicts AS
WITH latest_final_checker AS (
    SELECT run_id, checker_run_id, error_count, checker_invoked, success
    FROM (
        SELECT run_id, checker_run_id, error_count, checker_invoked, success, attempt_number,
               ROW_NUMBER() OVER (
                   PARTITION BY run_id
                   ORDER BY attempt_number DESC, created_at DESC, checker_run_id DESC
               ) AS rn
        FROM checker_runs
        WHERE stage = 'verification_final'
    )
    WHERE rn = 1
),
latest_regression_run AS (
    SELECT run_id, test_run_id, success, tests_found, compile_failed, timed_out, reported_failed_count
    FROM (
        SELECT run_id, test_run_id, success, tests_found, compile_failed, timed_out,
               reported_failed_count, attempt_number,
               ROW_NUMBER() OVER (
                   PARTITION BY run_id
                   ORDER BY attempt_number DESC, created_at DESC, test_run_id DESC
               ) AS rn
        FROM test_runs
        WHERE stage = 'verification' AND suite_kind = 'regression_test'
    )
    WHERE rn = 1
),
latest_suite_run AS (
    SELECT run_id, test_run_id, success, tests_found, compile_failed, timed_out, reported_failed_count
    FROM (
        SELECT run_id, test_run_id, success, tests_found, compile_failed, timed_out,
               reported_failed_count, attempt_number,
               ROW_NUMBER() OVER (
                   PARTITION BY run_id
                   ORDER BY attempt_number DESC, created_at DESC, test_run_id DESC
               ) AS rn
        FROM test_runs
        WHERE stage = 'verification' AND suite_kind = 'pre_existing_suite'
    )
    WHERE rn = 1
),
regression AS (
    SELECT lr.run_id,
           lr.success AS run_success,
           lr.tests_found AS tests_found,
           lr.compile_failed AS compile_failed,
           lr.timed_out AS timed_out,
           lr.reported_failed_count AS reported_failed_count,
           COUNT(te.execution_id) AS result_rows,
           CASE
               WHEN lr.tests_found = 0 OR lr.compile_failed = 1 OR lr.timed_out = 1
                    OR (lr.success = 0 AND lr.reported_failed_count = 0)
                    OR COUNT(te.execution_id) = 0 THEN NULL
               WHEN COUNT(*) FILTER (WHERE te.status IN ('FAILED', 'ERROR')) > 0 THEN 0
               WHEN COUNT(*) FILTER (WHERE te.status = 'PASSED') > 0 THEN 1
               ELSE NULL
           END AS reg_test_passed
    FROM latest_regression_run lr
    LEFT JOIN test_executions te
      ON te.test_run_id = lr.test_run_id AND te.is_regression_test = 1
    GROUP BY lr.run_id, lr.success, lr.tests_found, lr.compile_failed, lr.timed_out, lr.reported_failed_count
),
unit AS (
    SELECT ls.run_id,
           ls.success AS run_success,
           ls.tests_found AS tests_found,
           ls.compile_failed AS compile_failed,
           ls.timed_out AS timed_out,
           ls.reported_failed_count AS reported_failed_count,
           COUNT(*) FILTER (WHERE te.status IN ('PASSED', 'FAILED', 'ERROR')) AS executed,
           COUNT(*) FILTER (WHERE te.status IN ('FAILED', 'ERROR')) AS failed,
           COUNT(*) FILTER (WHERE te.status = 'SKIPPED') AS skipped
    FROM latest_suite_run ls
    LEFT JOIN test_executions te
      ON te.test_run_id = ls.test_run_id AND te.is_regression_test = 0
    GROUP BY ls.run_id, ls.success, ls.tests_found, ls.compile_failed, ls.timed_out, ls.reported_failed_count
)
SELECT
    r.run_id,
    r.benchmark_id,
    r.commit_hash,
    r.guardrails_mode,
    r.verification_scope,
    r.sample_index,
    r.status,
    CASE WHEN r.status = 'triage_only' THEN 'triage_only'
         WHEN r.status IN ('success') AND r.completed_at IS NOT NULL THEN 'evaluated'
         WHEN r.status LIKE 'stopped_after_%' THEN 'not_evaluated'
         ELSE 'not_evaluated' END AS outcome_kind,
    fc.error_count AS remaining_type_errors,
    CASE
        WHEN u.executed > 0 AND u.tests_found > 0 AND u.compile_failed = 0 AND u.timed_out = 0
             AND (u.run_success = 1 OR u.reported_failed_count > 0 OR reg.reg_test_passed = 0)
        THEN (u.failed = 0)
        ELSE NULL
    END AS unit_tests_passed,
    reg.reg_test_passed,
    COALESCE(u.skipped, 0) AS skipped_tests,
    CASE
        WHEN r.status = 'triage_only' THEN NULL
        WHEN r.status <> 'success' OR r.completed_at IS NULL THEN 'verification_missing'
        WHEN fc.checker_run_id IS NULL
             OR fc.checker_invoked = 0
             OR fc.success = 0 THEN 'verification_missing'
        WHEN reg.reg_test_passed IS NULL
             OR reg.tests_found = 0
             OR reg.result_rows = 0
             OR reg.compile_failed = 1
             OR reg.timed_out = 1
             OR (reg.run_success = 0 AND reg.reported_failed_count = 0)
             THEN 'verification_missing'
        WHEN r.verification_scope = 'full_suite'
             AND (
                 u.tests_found IS NULL OR u.tests_found = 0 OR
                 u.executed IS NULL OR u.executed = 0 OR
                 u.compile_failed = 1 OR u.timed_out = 1 OR
                 (u.run_success = 0 AND u.reported_failed_count = 0 AND reg.reg_test_passed = 1)
             )
             THEN 'verification_missing'
        WHEN fc.error_count > 0 THEN 'fixer_incomplete'
        WHEN r.verification_scope = 'full_suite' AND u.failed > 0 THEN 'suite_failure'
        WHEN r.verification_scope = 'regression_only'
             AND reg.reg_test_passed = 1 THEN 'partial_success_regression_only'
        WHEN r.verification_scope = 'full_suite'
             AND reg.reg_test_passed = 1
             AND u.executed > 0
             AND u.failed = 0 THEN 'full_success'
        WHEN reg.reg_test_passed = 0
             AND (r.verification_scope = 'regression_only' OR u.failed = 0)
             THEN 'type_success_runtime_fail'
        ELSE 'verification_missing'
    END AS verdict
FROM evaluation_runs r
LEFT JOIN latest_final_checker fc ON r.run_id = fc.run_id
LEFT JOIN regression reg ON r.run_id = reg.run_id
LEFT JOIN unit u ON r.run_id = u.run_id;

CREATE VIEW IF NOT EXISTS v_step_summaries AS
SELECT
    st.step_id,
    st.run_id,
    st.step_number,
    st.attempt_number,
    st.step_name,
    st.success,
    st.cache_hit,
    st.started_at,
    st.completed_at,
    COALESCE(t.turns_taken, 0) AS turns_taken,
    COALESCE(llm.total_prompt_tokens, 0) AS total_prompt_tokens,
    COALESCE(llm.total_completion_tokens, 0) AS total_completion_tokens,
    COALESCE(llm.total_tokens, 0) AS total_tokens,
    COALESCE(c.checker_invocations, 0) AS checker_invocations,
    COALESCE(tr.test_invocations, 0) AS test_invocations,
    COALESCE(cd.diagnostic_count, 0) AS checker_diagnostic_count
FROM step_trajectories st
LEFT JOIN (
    SELECT step_id, MAX(turn_index) AS turns_taken
    FROM agent_turns
    GROUP BY step_id
) t ON st.step_id = t.step_id
LEFT JOIN (
    SELECT step_id, SUM(prompt_tokens) AS total_prompt_tokens,
           SUM(completion_tokens) AS total_completion_tokens,
           SUM(total_tokens) AS total_tokens
    FROM llm_invocations
    WHERE step_id IS NOT NULL
    GROUP BY step_id
) llm ON st.step_id = llm.step_id
LEFT JOIN (
    SELECT step_id, COUNT(*) AS checker_invocations
    FROM checker_runs
    GROUP BY step_id
) c ON st.step_id = c.step_id
LEFT JOIN (
    SELECT step_id, COUNT(*) AS test_invocations
    FROM test_runs
    GROUP BY step_id
) tr ON st.step_id = tr.step_id
LEFT JOIN (
    SELECT step_id, COUNT(*) AS diagnostic_count
    FROM checker_diagnostics
    GROUP BY step_id
) cd ON st.step_id = cd.step_id;
"""
}
