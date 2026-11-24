(() => {
  "use strict";

  const STAGE_ORDER = ["ingestion", "triage", "synthesis", "application", "repair", "verification"];

  const state = {
    runs: [],
    selectedRunId: null,
    runDetail: null,
    compareRunDetail: null,
    activeRunTab: "stages",
    selectedStepName: null,
    selectedStepId: null,
    activeStageTab: "overview",
    configRawMode: false,
    configDiffOnly: false,
    logText: null,
    lastDbMtime: null,
    // The checkbox's DOM state used to be the only source of truth, so any full
    // page load silently dropped Live mode. Keep it in state and persist it.
    autoRefreshEnabled: false,
    autoRefreshTimer: null,
    viewMode: "inspector",
    matrixStage: null,
    highlightedBatchId: null,
    expandedBatches: new Set(),
    collapsedBatches: new Set(),
    // Restored from localStorage at startup so a reload does not silently
    // re-show triage skips in the matrix (see persistHideTriageSkipsPreference).
    hideDiscardedInMatrix: false,
    filters: {
      // Facet key -> Set of selected values. Within a facet values are ORed;
      // different facets are ANDed. Empty sets mean "no constraint".
      facets: {
        batch: new Set(),
        triageStatus: new Set(),
        outcome: new Set(),
        lifecycle: new Set(),
        classification: new Set(),
        triageMode: new Set(),
        guardrails: new Set(),
        verificationScope: new Set(),
        model: new Set(),
        repo: new Set(),
      },
      sort: "newest",
    },
    facetCatalog: {},
    placementDiffText: "",
    placementDiffRequest: 0,
    // Provisional transcript streamed while a step runs (see syncLiveTranscript).
    live: { runId: null, stepId: null, offset: 0, events: [], timer: null, busy: false, missing: 0 },
  };

  const $ = (id) => document.getElementById(id);

  function esc(str) {
    if (str === null || str === undefined) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // ---- Stage attempts vs. agent sessions ----
  // A *stage attempt* is one step_trajectories row: the orchestrator retries the
  // whole stage on a transient failure and records that in retry_events.
  // An *agent session* is one agentRunner.runAgent() call inside a stage attempt.
  // Each session begins with an initial_prompt event, and every session after the
  // first is a feedback retry. They are separate layers and stay labelled apart.
  function isSessionStart(ev) {
    return Boolean(ev) && (ev.event_type === "initial_prompt" || ev.event_type === "prompt" || ev.event_type === "user_prompt");
  }

  /** Prefer the CAS copy, but fall back to the durable DB transcript (CAS may be pruned). */
  function durableTranscript(step) {
    if (step.cas_transcript && step.cas_transcript.length) return step.cas_transcript;
    if (Array.isArray(step.agent_transcript) && step.agent_transcript.length) return step.agent_transcript;
    return null;
  }

  /** Returns null when the transcript is missing or has no session markers. */
  function sessionStats(step) {
    const transcript = durableTranscript(step);
    if (!transcript) return null;
    const sessions = transcript.filter(isSessionStart).length;
    if (!sessions) return null;
    return { sessions, retries: Math.max(0, sessions - 1) };
  }

  /** Turns across all sessions. v_step_summaries.turns_taken is a MAX over a
   *  session-local counter, so it undercounts whenever a session was retried. */
  function totalTurns(step) {
    const transcript = durableTranscript(step);
    if (!transcript) return null;
    let total = 0;
    let sessionMax = 0;
    let sawTurn = false;
    for (const ev of transcript) {
      if (isSessionStart(ev)) {
        total += sessionMax;
        sessionMax = 0;
        sawTurn = false;
        continue;
      }
      const idx = Number(ev.turn_index) || 0;
      if (idx > 0) {
        sessionMax = Math.max(sessionMax, idx);
        sawTurn = true;
      }
    }
    total += sessionMax;
    return sawTurn || total ? total : null;
  }

  // DB telemetry rows (llm_invocations, guardrail_events, compilation_events,
  // checker_runs, ...) carry no session id: they are all projected once at the
  // end of a stage attempt and share one created_at value. They therefore cannot
  // be attributed to a specific agent session. Do not guess from timestamps.

  function stageAttemptCount(run, step) {
    return (run.steps || []).filter((s) => s.step_name === step.step_name).length;
  }

  function retryTotals(run) {
    const events = run.retry_events || [];
    let sessions = 0;
    let sessionRetries = 0;
    let known = false;
    for (const step of run.steps || []) {
      const stats = sessionStats(step);
      if (stats) {
        known = true;
        sessions += stats.sessions;
        sessionRetries += stats.retries;
      }
    }
    return {
      stageRetries: events.filter((e) => e.will_retry).length,
      finalStageFailures: events.filter((e) => !e.will_retry).length,
      sessions,
      sessionRetries,
      known,
    };
  }

  function renderMarkdown(md) {
    if (!md) return "";
    const lines = String(md).split(/\r?\n/);
    const out = [];
    let inCode = false;
    let codeBuf = [];
    let listType = null;

    const closeList = () => {
      if (listType) {
        out.push(`</${listType}>`);
        listType = null;
      }
    };

    const inlineFmt = (s) =>
      esc(s)
        .replace(/`([^`]+)`/g, "<code>$1</code>")
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
        .replace(/\*([^*]+)\*/g, "<em>$1</em>");

    for (const line of lines) {
      if (line.startsWith("```")) {
        closeList();
        if (!inCode) {
          inCode = true;
          codeBuf = [];
        } else {
          out.push(`<pre><code>${esc(codeBuf.join("\n"))}</code></pre>`);
          inCode = false;
        }
        continue;
      }
      if (inCode) {
        codeBuf.push(line);
        continue;
      }
      if (/^\s*$/.test(line)) {
        closeList();
        continue;
      }
      if (/^---+$/.test(line.trim())) {
        closeList();
        out.push("<hr />");
        continue;
      }
      const h = /^(#{1,4})\s+(.*)$/.exec(line);
      if (h) {
        closeList();
        const lvl = h[1].length;
        out.push(`<h${lvl}>${inlineFmt(h[2])}</h${lvl}>`);
        continue;
      }
      if (line.startsWith("> ")) {
        closeList();
        out.push(`<blockquote>${inlineFmt(line.slice(2))}</blockquote>`);
        continue;
      }
      const ul = /^\s*[-*]\s+(.*)$/.exec(line);
      if (ul) {
        if (listType !== "ul") { closeList(); listType = "ul"; out.push("<ul>"); }
        out.push(`<li>${inlineFmt(ul[1])}</li>`);
        continue;
      }
      const ol = /^\s*\d+\.\s+(.*)$/.exec(line);
      if (ol) {
        if (listType !== "ol") { closeList(); listType = "ol"; out.push("<ol>"); }
        out.push(`<li>${inlineFmt(ol[1])}</li>`);
        continue;
      }
      closeList();
      out.push(`<p>${inlineFmt(line)}</p>`);
    }
    if (inCode) out.push(`<pre><code>${esc(codeBuf.join("\n"))}</code></pre>`);
    closeList();
    return out.join("\n");
  }

  function fmtNum(n) {
    if (n === null || n === undefined) return "-";
    return Number(n).toLocaleString();
  }

  function fmtBytes(bytes) {
    if (bytes === null || bytes === undefined) return "-";
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  }

  function fmtDurationMs(ms) {
    if (ms < 1000) return `${ms}ms`;
    const s = Math.floor(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    const remS = s % 60;
    return `${m}m ${remS}s`;
  }

  function fmtDuration(startIso, endIso) {
    if (!startIso || !endIso) return "-";
    const ms = new Date(endIso) - new Date(startIso);
    if (Number.isNaN(ms) || ms < 0) return "-";
    return fmtDurationMs(ms);
  }

  /** Format an elapsed time that may still be growing (used for running stages). */
  function fmtElapsed(startIso, nowMs = Date.now()) {
    if (!startIso) return "-";
    const ms = nowMs - new Date(startIso).getTime();
    if (Number.isNaN(ms)) return "-";
    return fmtDurationMs(Math.max(0, ms));
  }

  /**
   * Elapsed-time markup that keeps ticking. Every [data-live-start] element is
   * refreshed once per second by the live clock, so running stages show a
   * counter without needing a full re-render.
   */
  function liveDurationHtml(startIso) {
    return `<span class="live-duration" data-live-start="${esc(startIso || "")}" title="Elapsed time; updates every second while still running.">${esc(fmtElapsed(startIso))}</span>`;
  }

  function durationHtml(startIso, endIso, live = false) {
    return live ? liveDurationHtml(startIso) : esc(fmtDuration(startIso, endIso));
  }

  function fmtTs(iso) {
    if (!iso) return "-";
    return String(iso).replace("T", " ").replace(/\.\d+Z?$/, "Z");
  }

  const SUCCESS_VERDICTS = new Set(["full_success", "partial_success_regression_only"]);

  function verdictBadgeClass(verdict) {
    switch (verdict) {
      case "full_success": return "badge-green";
      case "partial_success_regression_only": return "badge-blue";
      case "type_success_runtime_fail":
      case "fixer_incomplete":
      case "suite_failure":
      case "regression_introduced": return "badge-yellow";
      case "verification_missing": return "badge-red";
      default: return "badge-muted";
    }
  }

  // Computed server-side: unfinished step (no completed_at) whose run is still 'running'.
  function isStepRunning(step) {
    return !!step && step.in_progress === true;
  }

  function stepState(step) {
    if (isStepRunning(step)) return "running";
    return step.success ? "ok" : "fail";
  }

  const VERDICT_LABELS = {
    full_success: "full success",
    partial_success_regression_only: "partial success",
    type_success_runtime_fail: "type success, runtime fail",
    fixer_incomplete: "fixer incomplete",
    suite_failure: "suite failure",
    regression_introduced: "suite failure (legacy)",
    verification_missing: "verification missing",
  };

  const VERDICT_TOOLTIPS = {
    full_success: "Full success: 0 remaining type errors, regression test passed, and full pre-existing unit suite passed.",
    partial_success_regression_only: "Partial success: 0 remaining type errors and regression test passed (full_suite not run).",
    type_success_runtime_fail: "Type-checks with 0 errors, but the regression test failed at runtime (valid JUnit evidence).",
    fixer_incomplete: "Verification ran, but Checker Framework still reports remaining type errors.",
    suite_failure: "Checker is clean but at least one pre-existing suite test failed. No baseline comparison, so this does not claim the failure was newly introduced.",
    regression_introduced: "Legacy label for suite_failure. Checker is clean but at least one pre-existing suite test failed.",
    verification_missing: "Verification ran, but its evidence is missing or unusable (no checker result, no parseable JUnit report, or no tests found). This is a verification result; it does not mean verification was skipped.",
  };

  // `discarded_build_broken` means the unpatched C-1 baseline itself failed to
  // build. `discarded_non_compiling` means C-1 built, but the regression-test
  // patch extracted from C does not compile against it (usually because the
  // test uses an API introduced by C). Keep those labels distinct so a test
  // portability failure is not reported as a broken baseline build.
  const TRIAGE_SKIP_LABELS = {
    non_bugfix: "not a bugfix",
    pending: "triage incomplete",
    discarded_build_broken: "baseline build broken",
    discarded_no_test: "no regression test",
    discarded_non_compiling: "test patch does not compile",
    discarded_passes_unpatched: "test passes unpatched",
    discarded_invalid_test_patch: "invalid test patch",
    discarded_regression_not_passing_on_fixed_commit: "test fails on fixed commit",
    discarded_fixed_commit_unavailable: "fixed commit unavailable",
    discarded_diff_too_large: "diff too large",
    discarded_merge_commit: "merge commit",
  };

  const TRIAGE_SKIP_TOOLTIPS = {
    discarded_build_broken:
      "The unpatched C-1 baseline failed to build. This is a property of the checked-out revision, not of the regression test.",
    discarded_non_compiling:
      "The unpatched C-1 baseline built successfully, but the regression-test patch from C does not compile on C-1. The test often references an API introduced by C.",
  };

  function verdictLabel(verdict) {
    return VERDICT_LABELS[verdict] || verdict || "unknown";
  }

  function verdictTooltip(verdict) {
    return VERDICT_TOOLTIPS[verdict] || `Evaluation verdict: ${verdict || "unknown"}`;
  }

  function triageSkipLabel(triageStatus) {
    if (TRIAGE_SKIP_LABELS[triageStatus]) return TRIAGE_SKIP_LABELS[triageStatus];
    return String(triageStatus || "skipped").replace(/^discarded_/, "").replace(/_/g, " ");
  }

  function isTriageSkipped(run) {
    const ts = run?.triage_status || "";
    return ts === "non_bugfix" || ts.startsWith("discarded_");
  }

  /** A triage stage that rejects a commit is a filter decision, not a failure. */
  function isSkippedStep(run, step) {
    return Boolean(step && step.step_name === "triage" && !step.success && isTriageSkipped(run));
  }

  /**
   * A stage can complete without achieving its goal. "ok" only means the stage ran
   * to completion; "warn" marks a completed repair/verification stage whose outcome
   * is unsuccessful (type errors remain, or the evaluation verdict is not a success).
   */
  function stageState(run, step) {
    if (isSkippedStep(run, step)) return "skip";
    const base = stepState(step);
    if (base !== "ok" || !run) return base;
    if (step.step_name === "repair" && Number(run.remaining_type_errors) > 0) return "warn";
    if (step.step_name === "verification" && run.verdict && !SUCCESS_VERDICTS.has(run.verdict)) return "warn";
    return base;
  }

  const STAGE_STATE_LABELS = { ok: "ok", fail: "failed", running: "running", skip: "skipped", warn: "incomplete" };

  function triageStatusMeta(triageStatus) {
    if (!triageStatus) {
      return { label: "unknown", cls: "badge-muted", tooltip: "No triage status recorded for this commit." };
    }
    if (triageStatus === "verified") {
      return {
        label: "verified",
        cls: "badge-green",
        tooltip: "Triage admitted the commit; it proceeded to the later pipeline stages.",
      };
    }
    if (triageStatus === "running") {
      return { label: "running", cls: "badge-blue", tooltip: "Triage is currently running for this commit." };
    }
    if (isTriageSkipped({ triage_status: triageStatus })) {
      return {
        label: triageSkipLabel(triageStatus),
        cls: "badge-muted",
        tooltip:
          TRIAGE_SKIP_TOOLTIPS[triageStatus] ||
          `Triage did not admit this commit (${triageStatus}), so the pipeline stopped before synthesis.`,
      };
    }
    if (triageStatus === "pending") {
      return { label: "pending", cls: "badge-yellow", tooltip: "Triage has not completed for this commit." };
    }
    return { label: triageStatus, cls: "badge-muted", tooltip: `Triage status: ${triageStatus}` };
  }

  function failureStage(run) {
    const failed = (run.steps || []).filter((s) => !s.success);
    return failed.length ? failed[failed.length - 1].step_name : null;
  }

  /**
   * What can be concluded about the repaired code: an evaluation verdict, or
   * "not evaluated" when verification never produced usable evidence.
   *
   * The database verdict stays `verification_missing` for every run without
   * verification evidence. That is correct bookkeeping, but a misleading
   * headline: a triage discard or an early stage failure was never evaluated.
   * The viewer therefore shows the reason instead of the raw verdict.
   */
  function deriveOutcome(run) {
    const status = run.status || "unknown";
    const verdict = run.verdict;
    const verificationStep = latestStep(run, "verification");

    if (status === "triage_only" || run.outcome_kind === "triage_only") {
      return { key: "triage_only", kind: "triage_only", label: "not evaluated (downstream limit)", cls: "badge-muted", reasonLabel: "verified, downstream limit reached", tooltip: "Triage verified this run, but a configured downstream limit prevented evaluation. This is not a failed verification." };
    }

    if (status === "running") {
      return {
        key: "running",
        kind: "running",
        label: "running",
        cls: "badge-blue",
        reasonLabel: null,
        tooltip: "The run is still in progress; it has no evaluation outcome yet.",
      };
    }
    if (verificationStep && verdict) {
      return {
        key: verdict,
        kind: "evaluated",
        label: verdictLabel(verdict),
        cls: verdictBadgeClass(verdict),
        reasonLabel: null,
        tooltip: verdictTooltip(verdict),
      };
    }

    const notEvaluated = (kind, reasonLabel, tooltip) => ({
      key: "not_evaluated",
      kind,
      label: "not evaluated",
      cls: kind === "triage_skipped" ? "badge-muted" : "badge-yellow",
      reasonLabel,
      tooltip,
    });

    if (status === "interrupted") {
      return notEvaluated(
        "interrupted",
        "interrupted",
        "The run was interrupted before verification completed, so it has no evaluation outcome. Any completed stages remain inspectable."
      );
    }

    if (status.startsWith("stopped_after_")) {
      const stage = status.slice("stopped_after_".length);
      return notEvaluated(
        "stopped",
        `stopped after ${stage}`,
        `The run was intentionally ended after ${stage} via --stop-after. Verification was not meant to run, so there is no evaluation outcome.`
      );
    }

    if (isTriageSkipped(run)) {
      const reason = triageSkipLabel(run.triage_status);
      const detail = TRIAGE_SKIP_TOOLTIPS[run.triage_status] || "";
      return notEvaluated(
        "triage_skipped",
        `triage: ${reason}`,
        `${detail ? detail + " " : ""}Triage did not admit this commit (${run.triage_status}: ${reason}), so synthesis and verification never ran. The stored "verification_missing" verdict only records that no verification evidence exists; it is not a failed evaluation.`
      );
    }

    if (status === "failed") {
      const stage = failureStage(run);
      return notEvaluated(
        "failed",
        stage ? `failed at ${stage}` : "pipeline failed",
        stage
          ? `The pipeline failed at the ${stage} stage before verification, so there is no evaluation outcome.`
          : "The pipeline failed before verification, so there is no evaluation outcome."
      );
    }

    return notEvaluated(
      "unknown",
      status,
      `No evaluation outcome is available (run status: ${status}).`
    );
  }

  /**
   * Execution lifecycle, separate from the evaluation outcome. A triage
   * discard ends the run as "failed" in the database, but that is a filtering
   * decision, not a pipeline failure, so it is shown as "stopped at triage".
   */
  function deriveLifecycle(run) {
    const status = run.status || "unknown";

    if (status === "triage_only") return { label: "verified · downstream limit", cls: "badge-muted", tooltip: "Triage verified the run; execution stopped at the configured downstream limit." };

    if (status === "running") {
      return {
        label: "running",
        cls: "badge-blue",
        tooltip: "Pipeline execution is currently in progress.",
      };
    }
    if (status === "success") {
      return {
        label: "completed",
        cls: "badge-green",
        tooltip: "All pipeline stages completed. The evaluation outcome is shown separately.",
      };
    }
    if (status === "interrupted") {
      return {
        label: "interrupted",
        cls: "badge-yellow",
        tooltip: "The run was interrupted before it could finish.",
      };
    }
    if (status.startsWith("stopped_after_")) {
      const stage = status.slice("stopped_after_".length);
      return {
        label: `stopped after ${stage}`,
        cls: "badge-muted",
        tooltip: `The run was intentionally ended after ${stage} via --stop-after.`,
      };
    }
    if (status === "failed") {
      if (isTriageSkipped(run)) {
        return {
          label: "stopped at triage",
          cls: "badge-muted",
          tooltip: "Triage rejected the commit, so the pipeline stopped before synthesis. This is a filtering decision, not a pipeline failure.",
        };
      }
      const stage = failureStage(run);
      return {
        label: stage ? `failed at ${stage}` : "failed",
        cls: "badge-red",
        tooltip: stage
          ? `Pipeline execution stopped because the ${stage} stage failed.`
          : "Pipeline execution stopped due to a stage failure.",
      };
    }
    return {
      label: status,
      cls: "badge-muted",
      tooltip: `Pipeline run status: ${status}`,
    };
  }

  function outcomeSummary(run) {
    const outcome = deriveOutcome(run);
    return outcome.reasonLabel
      ? `${outcome.label} (${outcome.reasonLabel})`
      : outcome.label;
  }

  // ---- Faceted Filtering ----
  //
  // Every facet maps a run to one or more filterable values. Selecting several
  // values within a facet keeps runs matching any of them; selections across
  // facets are combined with AND. Badges throughout the UI carry
  // data-facet/data-facet-value so they can toggle a filter on click.

  const TRIAGE_STATUS_ORDER = [
    "verified",
    "running",
    "pending",
    "non_bugfix",
    "discarded_no_test",
    "discarded_non_compiling",
    "discarded_passes_unpatched",
    "discarded_invalid_test_patch",
    "discarded_regression_not_passing_on_fixed_commit",
    "discarded_fixed_commit_unavailable",
    "discarded_diff_too_large",
    "discarded_build_broken",
    "discarded_merge_commit",
    "discarded",
    "unknown",
  ];

  const OUTCOME_ORDER = [
    "full_success",
    "partial_success_regression_only",
    "type_success_runtime_fail",
    "fixer_incomplete",
    "suite_failure",
    "regression_introduced",
    "verification_missing",
    "triage_only",
    "running",
    "not_evaluated",
  ];

  const LIFECYCLE_ORDER = [
    "completed",
    "verified · downstream limit",
    "running",
    "interrupted",
    "stopped at triage",
  ];

  /**
   * Facets and clickable badges must agree on the exact value they represent.
   * Some run-list columns are null for older/interrupted rows, so compute the
   * effective value once and use it everywhere (see the later stage inspector).
   */
  function effectiveTriageStatus(run) {
    if (run.triage_status) return run.triage_status;
    const step = latestStep(run, "triage");
    if (!step) return "pending";
    if (isStepRunning(step)) return "running";
    return step.success ? "verified" : "discarded";
  }

  function effectiveGuardrails(run) {
    return run.key_config?.guardrails_mode || run.guardrails_mode || "unknown";
  }

  function effectiveVerificationScope(run) {
    return run.key_config?.verification_scope || run.verification_scope || "unknown";
  }

  const FACET_DEFS = [
    {
      // Toggled by the "N commits" pill on each batch card rather than by a
      // sidebar select: batch IDs are opaque crawls, not useful dropdown rows.
      key: "batch",
      label: "Run batch",
      shortLabel: "batch",
      values: (r) => (r.batch_id ? [r.batch_id] : []),
      display: (r, value) => String(value).slice(0, 8),
      noSelect: true,
    },
    {
      key: "triageStatus",
      label: "Triage status",
      shortLabel: "triage",
      placeholder: "+ triage status",
      values: (r) => [effectiveTriageStatus(r)],
      display: (r, value) => triageStatusMeta(value).label,
      order: TRIAGE_STATUS_ORDER,
    },
    {
      key: "outcome",
      label: "Outcome",
      shortLabel: "outcome",
      placeholder: "+ outcome",
      values: (r) => [deriveOutcome(r).key],
      display: (r) => deriveOutcome(r).label,
      order: OUTCOME_ORDER,
    },
    {
      key: "lifecycle",
      label: "Run lifecycle",
      shortLabel: "lifecycle",
      placeholder: "+ lifecycle",
      values: (r) => [deriveLifecycle(r).label],
      display: (r) => deriveLifecycle(r).label,
      order: LIFECYCLE_ORDER,
    },
    {
      key: "classification",
      label: "Classification",
      shortLabel: "class",
      placeholder: "+ classification",
      values: (r) => [r.classification || "unknown"],
    },
    {
      key: "triageMode",
      label: "Triage mode",
      shortLabel: "triage mode",
      placeholder: "+ triage mode",
      values: (r) => [r.key_config?.triage_mode || "manifest"],
    },
    {
      key: "guardrails",
      label: "Guardrails",
      shortLabel: "guardrails",
      placeholder: "+ guardrails",
      values: (r) => [effectiveGuardrails(r)],
    },
    {
      key: "verificationScope",
      label: "Verification scope",
      shortLabel: "scope",
      placeholder: "+ scope",
      values: (r) => [effectiveVerificationScope(r)],
    },
    {
      key: "model",
      label: "Model",
      shortLabel: "model",
      placeholder: "+ model",
      values: (r) => [r.key_config?.model_default || "unknown"],
    },
    {
      key: "repo",
      label: "Repository",
      shortLabel: "repo",
      placeholder: "+ repo",
      values: (r) => [r.repo_name || "unknown"],
    },
  ];

  const FACET_BY_KEY = Object.fromEntries(FACET_DEFS.map((def) => [def.key, def]));

  const SORT_OPTIONS = [
    ["newest", "Newest first"],
    ["oldest", "Oldest first"],
    ["benchmark", "Benchmark A–Z"],
    ["tokens_desc", "Most tokens"],
    ["duration_desc", "Longest duration"],
    ["outcome", "Outcome (success first)"],
  ];

  const OUTCOME_SORT_RANK = {
    full_success: 0,
    partial_success_regression_only: 1,
    type_success_runtime_fail: 2,
    fixer_incomplete: 3,
    suite_failure: 4,
    regression_introduced: 4,
    verification_missing: 5,
    triage_only: 6,
    running: 7,
    not_evaluated: 8,
  };

  function facetLabel(key) {
    return FACET_BY_KEY[key]?.label || key;
  }

  function prettifyFacetValue(value) {
    const v = String(value ?? "");
    if (v === "unknown") return "unknown";
    if (v.startsWith("discarded_")) return triageSkipLabel(v);
    return v.replace(/_/g, " ");
  }

  function isFacetActive(key, value) {
    return Boolean(state.filters.facets[key]?.has(value));
  }

  /**
   * Render a clickable badge (or plain-text) control that toggles a facet
   * filter. Uses a button so keyboard users get Enter/Space for free.
   */
  function facetControl(key, value, { cls = "", label = null, tooltip = "", plain = false, inner = null, tabbable = true } = {}) {
    const active = isFacetActive(key, value);
    const text = label === null || label === undefined ? String(value) : String(label);
    const hint = `${active ? "Remove" : "Add"} ${facetLabel(key).toLowerCase()} filter: ${text}`;
    const title = tooltip ? `${tooltip} ${hint}.` : `${hint}.`;
    const classes = plain
      ? `facet-link-plain${active ? " is-active" : ""}`
      : `badge ${cls} facet-link${active ? " is-active" : ""}`.trim();
    const body = inner !== null ? inner : esc(text);
    const tabAttr = tabbable ? "" : ' tabindex="-1"';
    return `<button type="button" class="${esc(classes)}" data-facet="${esc(key)}" data-facet-value="${esc(value)}" data-facet-tip="${esc(tooltip)}"${tabAttr} aria-pressed="${active}" title="${esc(title)}">${body}</button>`;
  }

  /** Re-apply active styling/tooltips without re-rendering a pane. */
  function refreshFacetControl(el) {
    const key = el.dataset.facet;
    const value = el.dataset.facetValue;
    if (!key || value === undefined) return;
    const active = isFacetActive(key, value);
    el.classList.toggle("is-active", active);
    el.setAttribute("aria-pressed", String(active));
    const text = el.textContent.trim();
    const hint = `${active ? "Remove" : "Add"} ${facetLabel(key).toLowerCase()} filter: ${text}`;
    const tip = el.dataset.facetTip || "";
    el.title = tip ? `${tip} ${hint}.` : `${hint}.`;
  }

  function syncFacetControlStates(root = document) {
    root.querySelectorAll("[data-facet][data-facet-value]").forEach(refreshFacetControl);
  }

  function facetBadge(key, value, cls, label, tooltip, tabbable = true) {
    return facetControl(key, value, { cls, label, tooltip, tabbable });
  }

  /**
   * The reason under an outcome badge is usually a triage decision or a
   * lifecycle stop, so make it filter on that more specific facet.
   */
  function outcomeReasonControl(run, outcome, cls = "badge-muted", tabbable = true) {
    if (!outcome.reasonLabel) return "";
    if (outcome.kind === "triage_skipped" && run.triage_status) {
      return facetBadge("triageStatus", run.triage_status, cls, outcome.reasonLabel, outcome.tooltip, tabbable);
    }
    const lifecycle = deriveLifecycle(run);
    return facetBadge("lifecycle", lifecycle.label, cls, outcome.reasonLabel, lifecycle.tooltip, tabbable);
  }

  function resetFacetFilters() {
    for (const set of Object.values(state.filters.facets)) set.clear();
  }

  function activeFilterCount() {
    let count = 0;
    for (const set of Object.values(state.filters.facets)) count += set.size;
    if ($("runSearch") && $("runSearch").value.trim()) count += 1;
    return count;
  }

  function toggleFacet(key, value) {
    const set = state.filters.facets[key];
    if (!set) return;
    if (set.has(value)) {
      set.delete(value);
    } else {
      // The Stage Matrix compares commits within a single crawl. Selecting a
      // batch is exclusive so its runs are never mixed with another batch's.
      if (key === "batch") set.clear();
      set.add(value);
    }
    applyFilters();
  }

  /** Keep keyboard focus on the equivalent control after a filter re-render. */
  function restoreFacetFocus(key, value) {
    const selector = `[data-facet="${CSS.escape(key)}"][data-facet-value="${CSS.escape(value)}"]:not([tabindex="-1"])`;
    const candidates = [...document.querySelectorAll(selector)];
    const el = candidates.find((node) => node.getClientRects().length > 0);
    if (el) {
      el.focus();
      return;
    }
    const chip = document.querySelector(
      `#activeFilters [data-remove-facet="${CSS.escape(key)}"][data-remove-value="${CSS.escape(value)}"]`
    );
    if (chip) chip.focus();
    else $("runSearch").focus();
  }

  /** Build a value -> display label map for each facet from all loaded runs. */
  function buildFacetCatalog() {
    const catalog = {};
    for (const def of FACET_DEFS) {
      const labels = new Map();
      for (const run of state.runs) {
        for (const value of def.values(run)) {
          if (!labels.has(value)) {
            labels.set(value, def.display ? def.display(run, value) : String(value));
          }
        }
      }
      catalog[def.key] = labels;
    }
    state.facetCatalog = catalog;
  }

  function facetValueLabel(key, value) {
    const label = state.facetCatalog?.[key]?.get(value);
    return label === undefined ? prettifyFacetValue(value) : label;
  }

  function runMatchesSearch(run, query) {
    const hay = [
      run.run_id,
      run.benchmark_id,
      run.commit_hash,
      run.pr_title,
      run.pr_number ? `pr#${run.pr_number}` : "",
      run.repo_name,
      run.key_config?.model_default,
      run.key_config?.model_triage,
      run.triage_status,
      run.triage_status ? triageSkipLabel(run.triage_status) : "",
      effectiveTriageStatus(run),
      run.discard_reason,
      run.classification,
      run.verdict,
      run.verdict ? verdictLabel(run.verdict) : "",
      run.key_config?.triage_mode,
      run.guardrails_mode,
      effectiveGuardrails(run),
      run.verification_scope,
      effectiveVerificationScope(run),
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
    return hay.includes(query);
  }

  /**
   * Apply all active filters. Pass ignoreFacet to compute the option list for
   * one facet without letting that facet's own selection hide its alternatives.
   */
  function filterRuns({ ignoreFacet = null } = {}) {
    const queryEl = $("runSearch");
    const query = queryEl ? queryEl.value.trim().toLowerCase() : "";
    return state.runs.filter((run) => {
      for (const def of FACET_DEFS) {
        if (def.key === ignoreFacet) continue;
        const selected = state.filters.facets[def.key];
        if (!selected || selected.size === 0) continue;
        const values = def.values(run);
        if (!values.some((value) => selected.has(value))) return false;
      }
      if (query && !runMatchesSearch(run, query)) return false;
      return true;
    });
  }

  function runDurationMs(run) {
    if (!run.started_at || !run.completed_at) return -1;
    const ms = new Date(run.completed_at) - new Date(run.started_at);
    return Number.isNaN(ms) ? -1 : ms;
  }

  function compareNewest(a, b) {
    return (
      String(b.started_at || "").localeCompare(String(a.started_at || "")) ||
      String(b.run_id || "").localeCompare(String(a.run_id || ""))
    );
  }

  function sortRuns(runs) {
    const sorted = [...runs];
    switch (state.filters.sort) {
      case "oldest":
        sorted.sort((a, b) => -compareNewest(a, b));
        break;
      case "benchmark":
        sorted.sort((a, b) => String(a.benchmark_id || "").localeCompare(String(b.benchmark_id || "")) || compareNewest(a, b));
        break;
      case "tokens_desc":
        sorted.sort((a, b) => (b.llm_stats?.total_tokens || 0) - (a.llm_stats?.total_tokens || 0) || compareNewest(a, b));
        break;
      case "duration_desc":
        sorted.sort((a, b) => runDurationMs(b) - runDurationMs(a) || compareNewest(a, b));
        break;
      case "outcome":
        sorted.sort((a, b) => {
          const rankA = OUTCOME_SORT_RANK[deriveOutcome(a).key] ?? 99;
          const rankB = OUTCOME_SORT_RANK[deriveOutcome(b).key] ?? 99;
          return rankA - rankB || compareNewest(a, b);
        });
        break;
      case "newest":
      default:
        sorted.sort(compareNewest);
        break;
    }
    return sorted;
  }

  function serializeFacets() {
    const out = {};
    for (const [key, set] of Object.entries(state.filters.facets)) {
      if (set.size > 0) out[key] = [...set];
    }
    return out;
  }

  function normalizeFacetState(facets) {
    const out = {};
    for (const key of Object.keys(state.filters.facets)) {
      const values = facets?.[key];
      if (Array.isArray(values) && values.length > 0) {
        out[key] = [...new Set(values)].sort();
      }
    }
    return JSON.stringify(out);
  }

  function restoreFiltersFromUrl(saved) {
    resetFacetFilters();
    const facets = saved?.filters;
    if (facets && typeof facets === "object" && !Array.isArray(facets)) {
      for (const [key, values] of Object.entries(facets)) {
        // JSON.parse can create keys such as "__proto__" or "constructor";
        // only accept keys that are real facets backed by a Set.
        if (!Object.hasOwn(state.filters.facets, key)) continue;
        const set = state.filters.facets[key];
        if (!(set instanceof Set) || !Array.isArray(values)) continue;
        for (const value of values) {
          if (typeof value !== "string") continue;
          // Keep the restored batch selection consistent with toggleFacet:
          // exactly one batch at a time.
          if (key === "batch" && set.size > 0) break;
          set.add(value);
        }
      }
    }
    if (SORT_OPTIONS.some(([value]) => value === saved?.sort)) {
      state.filters.sort = saved.sort;
    }
  }

  const CONFIG_PILL_TOOLTIPS = {
    model: "Default LLM model configured for pipeline stages",
    guardrails: "Guardrail enforcement mode (e.g., lenient vs strict)",
    scope: "Verification test scope (full_suite vs regression_only)",
    freedom: "Synthesis freedom level (e.g., level0)",
    local_vars: "Policy for local variable type annotations during repair",
    temp: "LLM sampling temperature",
    steps: "Max agent step limits (synthesis / application / repair)",
    timeout: "Per-stage agent timeout",
  };

  function saveUrlState() {
    const params = new URLSearchParams();
    if (state.selectedRunId) params.set("run", state.selectedRunId);
    if (state.activeRunTab) params.set("tab", state.activeRunTab);
    if (state.selectedStepName) params.set("stage", state.selectedStepName);
    if (state.activeStageTab) params.set("stageTab", state.activeStageTab);
    const facets = serializeFacets();
    if (Object.keys(facets).length > 0) params.set("f", JSON.stringify(facets));
    if (state.filters.sort && state.filters.sort !== "newest") params.set("sort", state.filters.sort);
    const hash = `#${params.toString()}`;
    if (window.location.hash !== hash) {
      history.replaceState(null, "", hash);
    }
  }

  function readUrlState() {
    const raw = window.location.hash.replace(/^#/, "");
    if (!raw) return {};
    const params = new URLSearchParams(raw);
    let filters = null;
    try {
      filters = params.get("f") ? JSON.parse(params.get("f")) : null;
    } catch {
      filters = null;
    }
    return {
      run: params.get("run") || null,
      tab: params.get("tab") || null,
      stage: params.get("stage") || null,
      stageTab: params.get("stageTab") || null,
      filters,
      sort: params.get("sort") || null,
    };
  }

  async function apiGet(url) {
    const res = await fetch(url);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  async function copyText(text, btnEl) {
    try {
      await navigator.clipboard.writeText(text);
      if (btnEl) {
        const orig = btnEl.textContent;
        btnEl.textContent = "Copied!";
        setTimeout(() => { btnEl.textContent = orig; }, 1200);
      }
    } catch {
      // Fallback ignored
    }
  }

  // ---- Runs List ----
  async function loadRuns(preserveSelection = true) {
    const data = await apiGet("/api/runs");
    state.runs = data.runs || [];
    populateFilterOptions();
    renderRunsList();

    if (preserveSelection && state.selectedRunId) {
      const stillExists = state.runs.some((r) => r.run_id === state.selectedRunId);
      if (stillExists) {
        await selectRun(state.selectedRunId, true);
      }
    } else if (!state.selectedRunId && state.runs.length > 0) {
      const saved = readUrlState();
      const savedRunExists = Boolean(saved.run && state.runs.some((r) => r.run_id === saved.run));
      const filtered = getFilteredRuns();
      const targetRun = savedRunExists
        ? saved.run
        : filtered[0]?.run_id || (activeFilterCount() === 0 ? state.runs[0]?.run_id : null);
      if (!targetRun) return;
      if (saved.tab) state.activeRunTab = saved.tab;
      if (saved.stage) state.selectedStepName = saved.stage;
      if (saved.stageTab) state.activeStageTab = saved.stageTab;
      await selectRun(targetRun, Boolean(saved.run && targetRun === saved.run && (saved.stage || saved.tab || saved.stageTab)));
    }
  }

  function initFilterControls() {
    $("filterFacets").innerHTML = FACET_DEFS.filter((def) => !def.noSelect).map(
      (def) => `<select class="facet-select" data-facet="${esc(def.key)}" aria-label="Add ${esc(def.label.toLowerCase())} filter"><option value="">${esc(def.placeholder)}</option></select>`
    ).join("");
    $("sortSelect").innerHTML = SORT_OPTIONS.map(
      ([value, label]) => `<option value="${esc(value)}">${esc(label)}</option>`
    ).join("");
    $("sortSelect").value = state.filters.sort;
  }

  /** Values and counts for one facet, respecting filters from every other facet. */
  function facetOptions(def) {
    const counts = new Map();
    for (const run of filterRuns({ ignoreFacet: def.key })) {
      for (const value of def.values(run)) {
        counts.set(value, (counts.get(value) || 0) + 1);
      }
    }
    const selected = state.filters.facets[def.key] || new Set();
    const values = [...counts.keys()].filter((value) => !selected.has(value));
    const labelFor = (value) => facetValueLabel(def.key, value);
    values.sort((a, b) => {
      if (def.order) {
        const ai = def.order.indexOf(a);
        const bi = def.order.indexOf(b);
        if (ai !== -1 || bi !== -1) {
          return (ai === -1 ? Number.MAX_SAFE_INTEGER : ai) - (bi === -1 ? Number.MAX_SAFE_INTEGER : bi);
        }
      }
      return labelFor(a).localeCompare(labelFor(b));
    });
    return values.map((value) => ({ value, label: labelFor(value), count: counts.get(value) }));
  }

  /**
   * Update a facet select's options in place. Replacing the whole element (or
   * its innerHTML) during auto-refresh would close an open dropdown and destroy
   * keyboard focus; updating option nodes keeps it usable and never stale.
   */
  function updateFacetSelect(sel, placeholder, options) {
    const first = sel.options[0];
    if (!first) {
      const placeholderOption = document.createElement("option");
      placeholderOption.value = "";
      placeholderOption.textContent = placeholder;
      sel.appendChild(placeholderOption);
    } else {
      first.value = "";
      first.textContent = placeholder;
    }
    while (sel.options.length > 1) sel.remove(1);
    const frag = document.createDocumentFragment();
    for (const opt of options) {
      const optionEl = document.createElement("option");
      optionEl.value = opt.value;
      optionEl.textContent = `${opt.label} (${opt.count})`;
      frag.appendChild(optionEl);
    }
    sel.appendChild(frag);
    sel.value = "";
  }

  function populateFilterOptions() {
    buildFacetCatalog();
    for (const def of FACET_DEFS) {
      const sel = document.querySelector(`#filterFacets select[data-facet="${def.key}"]`);
      if (!sel) continue;
      const selected = state.filters.facets[def.key];
      sel.classList.toggle("has-filter", selected.size > 0);
      sel.setAttribute(
        "aria-label",
        selected.size > 0 ? `${def.label}: ${selected.size} selected` : `Add ${def.label.toLowerCase()} filter`
      );
      const options = facetOptions(def);
      const placeholder = options.length > 0 ? def.placeholder : `No ${def.label.toLowerCase()} values`;
      updateFacetSelect(sel, placeholder, options);
    }
    $("sortSelect").value = state.filters.sort;
    renderActiveFilters();
    updateClearButton();
  }

  function renderActiveFilters() {
    const chips = [];
    const query = $("runSearch").value.trim();
    if (query) {
      chips.push(`
        <span class="filter-chip filter-chip-search">
          <span class="filter-chip-key">search</span>
          <span class="filter-chip-val">${esc(query)}</span>
          <button type="button" class="filter-chip-x" data-clear-search="1" aria-label="Clear search filter" title="Clear search">&times;</button>
        </span>`);
    }
    for (const def of FACET_DEFS) {
      const selected = state.filters.facets[def.key];
      if (!selected || selected.size === 0) continue;
      for (const value of selected) {
        const label = facetValueLabel(def.key, value);
        const known = state.facetCatalog?.[def.key]?.has(value);
        chips.push(`
          <span class="filter-chip${known ? "" : " filter-chip-stale"}"${known ? "" : ` title="This value is not present in the loaded runs"`}>
            <span class="filter-chip-key">${esc(def.shortLabel)}</span>
            <span class="filter-chip-val">${esc(label)}</span>
            <button type="button" class="filter-chip-x" data-remove-facet="${esc(def.key)}" data-remove-value="${esc(value)}" aria-label="Remove ${esc(def.label)} filter ${esc(label)}" title="Remove this filter">&times;</button>
          </span>`);
      }
    }
    if (chips.length > 0) {
      chips.push(`<button type="button" class="filter-chip filter-chip-clear" data-clear-filters="1" title="Clear all filters including search">Clear all</button>`);
    }
    $("activeFilters").innerHTML = chips.join("");
  }

  function updateClearButton() {
    const btn = $("clearFiltersBtn");
    if (btn) btn.disabled = activeFilterCount() === 0;
  }

  function clearAllFilters() {
    resetFacetFilters();
    // "Hide triage skips" is a persisted matrix view preference, not a sidebar
    // filter, so clearing filters must not (temporarily) undo the user's choice.
    const search = $("runSearch");
    if (search) search.value = "";
    applyFilters();
  }

  function applyFilters() {
    populateFilterOptions();
    renderRunsList();
    syncFacetControlStates();
    saveUrlState();
  }

  function handleFacetSelectChange(event) {
    const sel = event.target.closest("select[data-facet]");
    if (!sel || !sel.value) return;
    const value = sel.value;
    sel.value = "";
    toggleFacet(sel.dataset.facet, value);
  }

  function handleActiveFilterClick(event) {
    const keyboardActivation = event.detail === 0;
    if (event.target.closest("[data-clear-filters]")) {
      clearAllFilters();
      if (keyboardActivation) $("runSearch").focus();
      return;
    }
    if (event.target.closest("[data-clear-search]")) {
      $("runSearch").value = "";
      applyFilters();
      if (keyboardActivation) $("runSearch").focus();
      return;
    }
    const btn = event.target.closest("[data-remove-facet]");
    if (!btn) return;
    const set = state.filters.facets[btn.dataset.removeFacet];
    if (set) set.delete(btn.dataset.removeValue);
    applyFilters();
    if (keyboardActivation) {
      const clearBtn = $("clearFiltersBtn");
      (clearBtn.disabled ? $("runSearch") : clearBtn).focus();
    }
  }

  function getFilteredRuns() {
    return sortRuns(filterRuns());
  }

  function renderRunCardHtml(r, isChild = false) {
    const active = r.run_id === state.selectedRunId && !state.highlightedBatchId ? "active" : "";
    const outcome = deriveOutcome(r);
    const dots = STAGE_ORDER.map((stName) => {
      const matches = (r.steps || []).filter((s) => s.step_name === stName);
      const latest = matches[matches.length - 1];
      if (!latest) return `<span class="stage-mini-dot" title="${esc(stName)}: not run"></span>`;
      const cls = stageState(r, latest);
      const cached = latest.cache_hit ? " cached" : "";
      const stateLabel = STAGE_STATE_LABELS[cls];
      return `<span class="stage-mini-dot ${cls}${cached}" title="${esc(stName)}: ${stateLabel}${latest.cache_hit ? " (cached)" : ""}"></span>`;
    }).join("");

    const triageMode = r.key_config?.triage_mode || "manifest";
    const tokens = r.llm_stats?.total_tokens ? `${fmtNum(r.llm_stats.total_tokens)} tok` : "0 tok";
    const reasonShowsTriage = Boolean(outcome.reasonLabel) && outcome.kind === "triage_skipped";
    const triageStatus = effectiveTriageStatus(r);
    const triageMeta = triageStatus !== "unknown" ? triageStatusMeta(triageStatus) : null;
    const triageStatusBadge =
      triageMeta && !reasonShowsTriage
        ? facetBadge("triageStatus", triageStatus, triageMeta.cls, triageMeta.label, triageMeta.tooltip, false)
        : "";
    return `
      <div class="run-card ${isChild ? "batch-child" : ""} ${active}" data-run-id="${esc(r.run_id)}" tabindex="-1">
        <div class="run-card-top">
          <span class="run-card-bench" title="${esc(r.benchmark_id)}">${esc(r.benchmark_id)}</span>
          ${facetBadge("outcome", outcome.key, outcome.cls, outcome.label, outcome.tooltip, false)}
        </div>
        ${outcome.reasonLabel ? `<div class="run-card-outcome-reason">${outcomeReasonControl(r, outcome, "badge-muted", false)}</div>` : ""}
        <div class="run-card-id" title="Started: ${esc(fmtTs(r.started_at))}">${esc(r.run_id.slice(0, 8))} &middot; ${esc(fmtTs(r.started_at))}</div>
        <div class="run-card-meta">
          ${triageStatusBadge}
          ${facetBadge("triageMode", triageMode, "badge-muted", triageMode, `Triage mode: ${triageMode}`, false)}
          ${facetBadge("guardrails", effectiveGuardrails(r), "badge-muted", effectiveGuardrails(r), `Guardrails mode: ${effectiveGuardrails(r)}`, false)}
          ${facetBadge("verificationScope", effectiveVerificationScope(r), "badge-muted", effectiveVerificationScope(r), `Verification scope: ${effectiveVerificationScope(r)}`, false)}
          <span class="muted" style="font-size:11px;" title="Total LLM tokens across all stages">${esc(tokens)}</span>
        </div>
        <div class="run-card-dots">${dots}</div>
      </div>
    `;
  }

  /** Warn when the inspected run is not part of the filtered sidebar list. */
  function updateHiddenRunBanner(filteredRuns) {
    const el = $("filteredOutBanner");
    if (!el) return;
    const hidden = Boolean(
      state.selectedRunId &&
        state.runDetail &&
        !filteredRuns.some((run) => run.run_id === state.selectedRunId)
    );
    el.classList.toggle("hidden", !hidden);
  }

  function renderRunsList() {
    const filtered = getFilteredRuns();
    const filterCount = activeFilterCount();
    $("runsCount").textContent =
      `${filtered.length} of ${state.runs.length} runs` +
      (filterCount > 0 ? ` · ${filterCount} filter${filterCount === 1 ? "" : "s"}` : "");
    updateClearButton();
    updateHiddenRunBanner(filtered);
    if (state.viewMode === "matrix") renderMatrixView();
    const listEl = $("runsList");

    if (filtered.length === 0) {
      listEl.innerHTML = `<div class="runs-empty"><div class="muted">No matching runs.</div><button type="button" class="btn btn-sm" data-clear-filters="1">Clear filters</button></div>`;
      listEl.querySelector("[data-clear-filters]")?.addEventListener("click", clearAllFilters);
      return;
    }

    const searchActive = Boolean($("runSearch").value.trim());
    const facetFilterActive = FACET_DEFS.some((def) => state.filters.facets[def.key]?.size > 0);
    const items = [];
    const batchMap = new Map();

    for (const r of filtered) {
      if (r.batch_id) {
        let group = batchMap.get(r.batch_id);
        if (!group) {
          group = { type: "batch", batchId: r.batch_id, runs: [] };
          batchMap.set(r.batch_id, group);
          items.push(group);
        }
        group.runs.push(r);
      } else {
        items.push({ type: "run", run: r });
      }
    }

    listEl.innerHTML = items.map((item) => {
      if (item.type === "run") {
        return renderRunCardHtml(item.run, false);
      }
      const runs = item.runs;
      const first = runs[0];
      const repoLabel = (first.benchmark_id || "").replace(/-[0-9a-f]{6,}$/i, "") || first.repo_name || "crawl";
      const totalTokens = runs.reduce((acc, r) => acc + (r.llm_stats?.total_tokens || 0), 0);
      const outcomes = runs.map((r) => deriveOutcome(r));
      const passCount = outcomes.filter((o) => o.key === "full_success" || o.key === "partial_success_regression_only").length;
      const failCount = outcomes.filter((o) => o.kind === "evaluated" && o.key !== "full_success" && o.key !== "partial_success_regression_only").length;
      const skippedCount = outcomes.filter((o) => o.kind === "triage_skipped").length;
      const triageOnlyCount = outcomes.filter((o) => o.kind === "triage_only").length;
      const incompleteCount = outcomes.filter((o) => ["failed", "interrupted", "stopped", "unknown"].includes(o.kind)).length;
      const runningCount = outcomes.filter((o) => o.kind === "running").length;
      const hasSelected = runs.some((r) => r.run_id === state.selectedRunId);
      const autoExpanded = searchActive || facetFilterActive || hasSelected;
      const isExpanded =
        state.expandedBatches.has(item.batchId) ||
        (autoExpanded && !state.collapsedBatches.has(item.batchId));
      const isActiveBatch =
        state.highlightedBatchId === item.batchId ||
        Boolean(state.filters.facets.batch?.has(item.batchId));

      const summaryPills = [
        passCount ? `<span class="badge badge-green">${passCount} pass</span>` : "",
        failCount ? `<span class="badge badge-red">${failCount} evaluated fail</span>` : "",
        skippedCount ? `<span class="badge badge-muted">${skippedCount} triage skip</span>` : "",
        triageOnlyCount ? `<span class="badge badge-muted">${triageOnlyCount} triaged · not evaluated</span>` : "",
        incompleteCount ? `<span class="badge badge-yellow">${incompleteCount} incomplete</span>` : "",
        runningCount ? `<span class="badge badge-blue">${runningCount} running</span>` : "",
      ].filter(Boolean).join("");

      const childrenHtml = isExpanded
        ? `<div class="batch-children">${runs.map((r) => renderRunCardHtml(r, true)).join("")}</div>`
        : "";

      return `
        <div class="batch-group">
          <div class="batch-card ${isActiveBatch ? "active" : ""}" data-batch-id="${esc(item.batchId)}" role="button" tabindex="0" aria-expanded="${isExpanded}" title="Click to ${isExpanded ? "collapse" : "expand"} this batch">
            <div class="run-card-top">
              <button class="batch-toggle-btn" data-toggle-batch="${esc(item.batchId)}" tabindex="-1" aria-hidden="true" title="${isExpanded ? "Collapse" : "Expand"} commits">${isExpanded ? "&#x25BE;" : "&#x25B8;"}</button>
              <span class="run-card-bench" title="Crawl batch ${esc(item.batchId)}">${esc(repoLabel)}</span>
              ${facetControl("batch", item.batchId, {
                cls: "badge-muted batch-filter-btn",
                label: `${runs.length} commit${runs.length === 1 ? "" : "s"}`,
                tooltip: `Filter the viewer to run batch ${item.batchId.slice(0, 8)} (${runs.length} commit${runs.length === 1 ? "" : "s"})`,
              })}
            </div>
            <div class="run-card-id" title="Batch ID: ${esc(item.batchId)}">${esc(item.batchId.slice(0, 8))} &middot; ${esc(fmtTs(first.started_at))}</div>
            <div class="run-card-meta">
              ${summaryPills}
              <span class="muted" style="font-size:11px;">${esc(fmtNum(totalTokens))} tok</span>
            </div>
          </div>
          ${childrenHtml}
        </div>
      `;
    }).join("");

    function toggleBatch(batchId, currentlyExpanded, preserveFocus = false) {
      if (preserveFocus) state.highlightedBatchId = batchId;
      if (currentlyExpanded) {
        state.expandedBatches.delete(batchId);
        state.collapsedBatches.add(batchId);
      } else {
        state.expandedBatches.add(batchId);
        state.collapsedBatches.delete(batchId);
      }
      renderRunsList();
      if (preserveFocus) {
        [...listEl.querySelectorAll(".batch-card")]
          .find((card) => card.dataset.batchId === batchId)
          ?.focus();
      }
    }

    listEl.querySelectorAll(".batch-card").forEach((el) => {
      const isExpanded = () => el.getAttribute("aria-expanded") === "true";
      el.addEventListener("focus", () => {
        state.highlightedBatchId = el.dataset.batchId;
        listEl.querySelectorAll(".run-card.active, .batch-card.active").forEach((card) => card.classList.remove("active"));
        el.classList.add("active");
      });
      el.addEventListener("click", () => {
        toggleBatch(el.dataset.batchId, isExpanded(), true);
      });
      el.addEventListener("keydown", (e) => {
        if (e.target === el && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          toggleBatch(el.dataset.batchId, isExpanded(), true);
        } else if (e.target instanceof Element && e.target.closest(".batch-card") === el && (e.key === "ArrowLeft" || e.key === "ArrowRight")) {
          e.preventDefault();
          const shouldExpand = e.key === "ArrowRight";
          if (isExpanded() !== shouldExpand) {
            toggleBatch(el.dataset.batchId, isExpanded(), true);
          }
        }
      });
    });

    listEl.querySelectorAll(".run-card").forEach((el) => {
      el.addEventListener("click", () => {
        if (state.viewMode !== "inspector") setViewMode("inspector");
        const runId = el.dataset.runId;
        selectRun(runId, false);
        [...listEl.querySelectorAll(".run-card")]
          .find((card) => card.dataset.runId === runId)
          ?.focus();
      });
    });
  }

  // ---- Run Selection & Header ----
  async function selectRun(runId, preserveStageAndTab = false) {
    state.selectedRunId = runId;
    state.highlightedBatchId = null;
    renderRunsList();

    const detail = await apiGet(`/api/runs/${encodeURIComponent(runId)}`);
    state.runDetail = detail;
    state.logText = null;

    if (!preserveStageAndTab) {
      state.activeRunTab = "stages";
      state.activeStageTab = "overview";
      // Pick the last executed step (or first) by default so failures are immediately visible
      const steps = detail.steps || [];
      const failed = steps.find((s) => !s.success && !isStepRunning(s));
      const target = failed || steps[steps.length - 1] || null;
      state.selectedStepName = target ? target.step_name : null;
      state.selectedStepId = target ? target.step_id : null;
    } else if (state.selectedStepName) {
      const matching = (detail.steps || []).filter((s) => s.step_name === state.selectedStepName);
      const exact = matching.find((s) => s.step_id === state.selectedStepId) || matching[matching.length - 1];
      state.selectedStepId = exact ? exact.step_id : null;
    }

    // Do not unhide the inspector unconditionally: Live refreshes re-select the
    // current run while the Matrix tab is active, which used to reveal both panes.
    syncViewVisibility();

    renderRunHeader();
    populateCompareDropdown();
    switchRunTab(state.activeRunTab);
    updateHiddenRunBanner(getFilteredRuns());
  }

  function renderRunHeader() {
    const r = state.runDetail;
    const kc = r.key_config || {};

    const outcome = deriveOutcome(r);
    const verdictHost = $("hdrVerdictBadge");
    verdictHost.className = "badge-host";
    verdictHost.innerHTML = facetBadge("outcome", outcome.key, outcome.cls, outcome.label, outcome.tooltip);

    const lifecycle = deriveLifecycle(r);
    const lifecycleHost = $("hdrStatusBadge");
    lifecycleHost.className = "badge-host";
    lifecycleHost.innerHTML = facetBadge("lifecycle", lifecycle.label, lifecycle.cls, `run: ${lifecycle.label}`, lifecycle.tooltip);

    $("hdrBenchmark").textContent = r.benchmark_id;
    $("hdrRunId").textContent = r.run_id;
    $("hdrRunId").onclick = () => copyText(r.run_id, $("hdrRunId"));

    const totalTokens = (r.steps || []).reduce((acc, s) => acc + (s.total_tokens || 0), 0);
    const totalLatency = (r.steps || []).reduce(
      (acc, s) => acc + (s.llm_invocations || []).reduce((a, i) => a + (i.latency_ms || 0), 0),
      0
    );

    const runDuration = durationHtml(r.started_at, r.completed_at, r.status === "running" && !r.completed_at);
    const retries = retryTotals(r);

    $("hdrMetrics").innerHTML = `
      <span>Duration: <strong>${runDuration}</strong></span>
      <span>Tokens: <strong>${esc(fmtNum(totalTokens))}</strong></span>
      <span>LLM Latency: <strong>${esc(fmtNum(totalLatency))} ms</strong></span>
      ${r.remaining_type_errors !== null && r.remaining_type_errors !== undefined ? `<span>Remaining Errors: <strong>${esc(r.remaining_type_errors)}</strong></span>` : ""}
      ${(r.retry_events || []).length ? `<span title="Orchestrator retries of a whole stage (retry_events): a failed attempt that was followed by another.">Stage retries: <strong>${retries.stageRetries}</strong> · final stage failures: <strong>${retries.finalStageFailures}</strong></span>` : ""}
      ${retries.known ? `<span title="Agent sessions are runAgent() calls inside a stage attempt; sessions after the first are feedback retries, not stage retries.">Agent sessions: <strong>${retries.sessions}</strong> · session retries: <strong>${retries.sessionRetries}</strong></span>` : ""}
    `;

    const subprocess = r.subprocess_metrics || [];
    const subprocessPanel = $("hdrSubprocessPanel");
    if (subprocess.length) {
      const totalMs = subprocess.reduce((acc, x) => acc + (Number(x.duration_ms) || 0), 0);
      const failedCount = subprocess.filter(
        (x) => x.timed_out || (x.exit_code !== null && x.exit_code !== undefined && x.exit_code !== 0)
      ).length;
      const slowest = subprocess.reduce(
        (a, b) => ((Number(b.duration_ms) || 0) > (Number(a.duration_ms) || 0) ? b : a),
        subprocess[0]
      );
      $("hdrSubprocessStats").innerHTML = [
        `${subprocess.length} ${subprocess.length === 1 ? "call" : "calls"}`,
        `total ${esc(fmtDurationMs(totalMs))}`,
        `slowest ${esc(fmtDurationMs(Number(slowest.duration_ms) || 0))} (${esc(slowest.kind)})`,
        failedCount ? `${failedCount} failed` : null,
      ].filter(Boolean).join(" · ");
      $("hdrSubprocessBody").innerHTML = `
        <table class="data-table subprocess-table">
          <thead><tr><th>Stage</th><th>Kind</th><th>Duration</th><th>Exit</th><th>Timed out</th></tr></thead>
          <tbody>${subprocess.map((x) => {
            const exit = x.exit_code === null || x.exit_code === undefined
              ? `<span class="muted">-</span>`
              : `<span class="badge ${x.exit_code === 0 ? "badge-green" : "badge-red"}">${esc(x.exit_code)}</span>`;
            return `<tr>
              <td>${esc(x.stage)}</td>
              <td><code>${esc(x.kind)}</code></td>
              <td class="num">${esc(fmtNum(Math.round(Number(x.duration_ms) || 0)))} ms</td>
              <td>${exit}</td>
              <td>${x.timed_out ? `<span class="badge badge-red">timed out</span>` : `<span class="muted">no</span>`}</td>
            </tr>`;
          }).join("")}</tbody>
        </table>
      `;
      subprocessPanel.classList.remove("hidden");
    } else {
      subprocessPanel.classList.add("hidden");
      $("hdrSubprocessStats").textContent = "";
      $("hdrSubprocessBody").innerHTML = "";
    }

    const regTest = r.regression_test_class
      ? `${r.regression_test_class}${r.regression_test_method ? "." + r.regression_test_method : ""}`
      : "-";

    $("hdrCommitMeta").innerHTML = `
      <span>Repo: <strong>${esc(r.repo_name || "-")}</strong></span>
      <span>Commit: <code>${esc((r.commit_hash || "").slice(0, 12))}</code></span>
      ${r.pr_number ? `<span>PR #${esc(r.pr_number)}: ${esc(r.pr_title || "")}</span>` : ""}
      <span>Target test: <code>${esc(regTest)}</code></span>
    `;

    const pills = [
      { k: "triage", v: kc.triage_mode || "manifest", facet: "triageMode" },
      { k: "triage_model", v: kc.triage_mode === "crawler" ? kc.model_triage : null },
      { k: "depth", v: kc.triage_mode === "crawler" ? kc.triage_depth : null },
      { k: "limit", v: kc.triage_mode === "crawler" ? kc.triage_limit : null },
      { k: "threshold", v: kc.triage_mode === "crawler" ? kc.triage_bugfix_threshold : null },
      { k: "model", v: kc.model_default, facet: "model" },
      { k: "guardrails", v: kc.guardrails_mode, facet: "guardrails" },
      { k: "scope", v: kc.verification_scope, facet: "verificationScope" },
      { k: "freedom", v: kc.synthesis_freedom_level },
      { k: "local_vars", v: kc.repair_local_var_annotations },
      { k: "temp", v: kc.sample_temperature },
      { k: "steps", v: kc.agent_steps ? `${kc.agent_steps.synthesis ?? "-"}/${kc.agent_steps.application ?? "-"}/${kc.agent_steps.repair ?? "-"}` : null },
      { k: "timeout", v: kc.agent_timeout_ms ? `${Math.round(kc.agent_timeout_ms / 1000)}s` : null },
    ].filter((pill) => pill.v !== null && pill.v !== undefined && pill.v !== "");

    $("hdrConfigPills").innerHTML = pills.map(({ k, v, facet }) => {
      const inner = `<span class="cfg-pill-k">${esc(k)}:</span><span class="cfg-pill-v">${esc(v)}</span>`;
      const title = CONFIG_PILL_TOOLTIPS[k] || k;
      if (!facet) return `<span class="cfg-pill" title="${esc(title)}">${inner}</span>`;
      const active = isFacetActive(facet, v);
      return `<button type="button" class="cfg-pill facet-link${active ? " is-active" : ""}" data-facet="${esc(facet)}" data-facet-value="${esc(v)}" aria-pressed="${active}" title="${esc(`${title}. Click to ${active ? "remove" : "add"} ${facetLabel(facet).toLowerCase()} filter: ${v}`)}">${inner}</button>`;
    }).join("");

    $("logSizeBadge").textContent = r.log_available ? fmtBytes(r.log_size_bytes) : "none";
  }

  function switchRunTab(tabName) {
    state.activeRunTab = tabName;
    saveUrlState();
    document.querySelectorAll(".run-tab").forEach((b) => {
      b.classList.toggle("active", b.dataset.runTab === tabName);
    });
    $("runTabStages").classList.toggle("hidden", tabName !== "stages");
    $("runTabConfig").classList.toggle("hidden", tabName !== "config");
    $("runTabLog").classList.toggle("hidden", tabName !== "log");

    if (tabName === "stages") {
      renderStageStepper();
      renderStageInspector();
    } else if (tabName === "config") {
      renderConfigTab();
    } else if (tabName === "log") {
      loadAndRenderLog();
    }
  }

  // ---- Stage Stepper & Inspector ----
  /** Phases that actually ran, in display order (not-run placeholders are skipped). */
  function executableStepNames() {
    return [...new Set((state.runDetail?.steps || []).map((s) => s.step_name))];
  }

  /** Focus the card of the currently selected phase after a re-render. */
  function focusSelectedStepCard() {
    const card = [...$("stageStepper").querySelectorAll(".step-card:not(.not-run)")]
      .find((el) => el.dataset.stepName === state.selectedStepName);
    card?.focus();
    card?.scrollIntoView({ block: "nearest" });
  }

  /** Select a phase (latest attempt), optionally preserving keyboard focus on its card. */
  function selectStepByName(name, focusCard = false) {
    const matching = (state.runDetail?.steps || []).filter((s) => s.step_name === name);
    const latest = matching[matching.length - 1];
    state.selectedStepName = name;
    state.selectedStepId = latest ? latest.step_id : null;
    renderStageStepper();
    renderStageInspector();
    if (focusCard) focusSelectedStepCard();
  }

  function renderStageStepper() {
    const steps = state.runDetail.steps || [];
    const byName = {};
    for (const s of steps) {
      (byName[s.step_name] ||= []).push(s);
    }

    const extraNames = steps.map((s) => s.step_name).filter((n) => !STAGE_ORDER.includes(n));
    const allNames = [...STAGE_ORDER, ...new Set(extraNames)];

    $("stageStepper").innerHTML = allNames.map((name) => {
      const attempts = byName[name] || [];
      const latest = attempts[attempts.length - 1];
      const active = state.selectedStepName === name ? "active" : "";
      if (!latest) {
        return `
          <div class="step-card not-run">
            <div class="step-card-top">
              <span class="step-name">${esc(name)}</span>
              <span class="stage-dot"></span>
            </div>
            <div class="step-card-sub"><span>Not executed</span></div>
          </div>
        `;
      }
      const dotCls = stageState(state.runDetail, latest);
      const dur = durationHtml(latest.started_at, latest.completed_at, isStepRunning(latest));
      const computedTurns = totalTurns(latest);
      const turns = computedTurns ?? latest.turns_taken;
      const turnsLabel = turns ? `<span title="${computedTurns !== null ? "Summed over all agent sessions; the DB view only takes a per-session max" : "Turns"}">${esc(turns)}t</span>` : "";
      const sess = sessionStats(latest);
      const stageRetryBadge = attempts.length > 1
        ? `<span class="badge badge-muted" title="${attempts.length - 1} orchestrator retry/retries of this whole stage (stage attempt count — not agent sessions)">${attempts.length} stage att</span>`
        : "";
      const sessionRetryBadge = sess && sess.retries
        ? `<span class="badge badge-yellow" title="${sess.retries} agent feedback retry/retries inside this stage attempt (not stage retries)">${sess.retries} sess retry</span>`
        : "";
      const stateLabel = dotCls === "skip" ? "skipped" : (dotCls === "running" ? "running" : (dotCls === "warn" ? "incomplete" : (latest.cache_hit ? "cached" : (latest.success ? "completed" : "failed"))));
      return `
        <button class="step-card ${active}" data-step-name="${esc(name)}" tabindex="-1" title="${esc(name)}: ${stateLabel}${attempts.length > 1 ? ` · ${attempts.length} stage attempts` : ""}${sess ? ` · ${sess.sessions} agent session(s)` : ""}">
          <div class="step-card-top">
            <span class="step-name">${esc(name)}</span>
            <span class="stage-dot ${dotCls}"></span>
          </div>
          <div class="step-card-sub">
            <span>${stateLabel}</span>
            <span>${stageRetryBadge}${sessionRetryBadge}${turnsLabel} ${dur}</span>
          </div>
        </button>
      `;
    }).join("");

    $("stageStepper").querySelectorAll(".step-card:not(.not-run)").forEach((el) => {
      el.addEventListener("click", () => selectStepByName(el.dataset.stepName, true));
    });
  }

  function getSelectedStep() {
    const steps = state.runDetail?.steps || [];
    return steps.find((s) => s.step_id === state.selectedStepId) || null;
  }

  function renderStageInspector() {
    const step = getSelectedStep();
    const insp = $("stageInspector");
    if (!step) {
      insp.classList.add("hidden");
      return;
    }
    insp.classList.remove("hidden");

    $("stageStatusIcon").className = `stage-dot ${stageState(state.runDetail, step)}`;
    $("stageTitle").textContent = step.step_name;

    const cacheBadge = $("stageCacheBadge");
    if (step.cache_hit) {
      if (step.cache_origin) {
        const orig = step.cache_origin;
        cacheBadge.innerHTML = `CACHE HIT · <button class="link-btn cache-origin-btn" data-origin-run="${esc(orig.run_id)}" data-origin-stage="${esc(step.step_name)}" title="Jump to original run ${esc(orig.run_id)} (${esc(orig.benchmark_id)})">from ${esc(orig.run_id.slice(0, 8))}</button>`;
        cacheBadge.title = `Served from CAS cache; originally produced by run ${orig.run_id} (${orig.benchmark_id})`;
        cacheBadge.querySelector(".cache-origin-btn")?.addEventListener("click", (e) => {
          e.stopPropagation();
          state.selectedStepName = step.step_name;
          state.selectedStepId = null;
          selectRun(orig.run_id, true);
        });
      } else {
        cacheBadge.textContent = "CACHE HIT";
        cacheBadge.title = "Served from CAS cache (origin run not found in current DB)";
      }
      cacheBadge.classList.remove("hidden");
    } else {
      cacheBadge.classList.add("hidden");
    }

    const startedLabel = `(${esc(fmtTs(step.started_at))})`;
    $("stageDuration").innerHTML = isStepRunning(step)
      ? `${liveDurationHtml(step.started_at)} ${startedLabel}`
      : `${esc(fmtDuration(step.started_at, step.completed_at))} ${startedLabel}`;

    const attempts = (state.runDetail.steps || []).filter((s) => s.step_name === step.step_name);
    const sess = sessionStats(step);
    const turns = totalTurns(step) ?? step.turns_taken;
    $("stageStats").textContent = [
      `stage attempt ${step.attempt_number}/${attempts.length}`,
      sess ? `${sess.sessions} agent session${sess.sessions === 1 ? "" : "s"}${sess.retries ? ` (${sess.retries} session retry${sess.retries === 1 ? "" : "s"})` : ""}` : null,
      turns ? `${turns} turns` : null,
      step.total_tokens ? `${fmtNum(step.total_tokens)} tokens` : null,
    ].filter(Boolean).join(" · ");

    // Stage-attempt selector (orchestrator retries only; agent sessions are in the transcript tab)
    const attWrap = $("attemptSelectorWrap");
    if (attempts.length > 1) {
      attWrap.classList.remove("hidden");
      const sel = $("attemptSelector");
      sel.innerHTML = attempts.map((a) => {
        const attemptState = stageState(state.runDetail, a);
        const label = { ok: "ok", fail: "fail", running: "running", skip: "skipped" }[attemptState];
        return `<option value="${esc(a.step_id)}">Stage attempt #${a.attempt_number} (${label})</option>`;
      }).join("");
      sel.value = step.step_id;
    } else {
      attWrap.classList.add("hidden");
    }

    // Tab counts
    const hasPatch = Boolean(step.diff_patch || step.cas_diff_patch || (step.annotation_placements || []).length);
    $("cntPatch").textContent = hasPatch ? (step.annotation_placements?.length || "1") : "";
    const rawEventCount = Math.max(
      (step.agent_turns || []).length,
      (step.cas_transcript || []).length,
      liveEventsFor(step).length,
      (step.agent_transcript || []).length
    );
    const sessInfo = sessionStats(step);
    const cntTranscript = $("cntTranscript");
    cntTranscript.textContent = sessInfo && sessInfo.sessions > 1
      ? `${sessInfo.sessions} sessions`
      : (rawEventCount ? String(rawEventCount) : "");
    cntTranscript.title = sessInfo
      ? `${sessInfo.sessions} agent session(s), ${sessInfo.retries} session retry/retries`
      : "Transcript events recorded";
    const diagCount = (step.checker_runs || []).length + (step.checker_diagnostics || []).length + (step.compilation_events || []).length + (step.compiler_diagnostics ? 1 : 0);
    $("cntDiagnostics").textContent = diagCount ? String(diagCount) : "";
    const testCount = (step.test_executions || []).length || (step.test_runs || []).length;
    $("cntTests").textContent = testCount ? String(testCount) : "";
    $("cntGuardrails").textContent = (step.guardrail_events || []).length ? String(step.guardrail_events.length) : "";

    switchStageTab(state.activeStageTab);
  }

  function switchStageTab(tabName) {
    state.activeStageTab = tabName;
    saveUrlState();
    document.querySelectorAll(".stage-tab").forEach((b) => {
      b.classList.toggle("active", b.dataset.stageTab === tabName);
    });
    const step = getSelectedStep();
    if (!step) return;

    const container = $("stageTabContent");
    switch (tabName) {
      case "overview": container.innerHTML = renderStageOverview(step); bindArtifactLinks(container, step); break;
      case "patch": container.innerHTML = renderStagePatch(step); bindDiffHandlers(container, step); break;
      case "transcript": container.innerHTML = renderStageTranscript(step); bindTranscriptHandlers(container); break;
      case "diagnostics": container.innerHTML = renderStageDiagnostics(step); break;
      case "tests": container.innerHTML = renderStageTests(step); break;
      case "guardrails": container.innerHTML = renderStageGuardrails(step); break;
    }
    syncLiveTranscript(step);
  }

  // ---- Live transcript (in-progress steps) ----
  const LIVE_POLL_MS = 2000;
  const LIVE_MISSING_LIMIT = 3;

  function liveEventsFor(step) {
    const live = state.live;
    return step && isStepRunning(step) && live.stepId === step.step_id ? live.events : [];
  }

  function stopLivePolling() {
    const live = state.live;
    if (live.timer) clearInterval(live.timer);
    Object.assign(live, { runId: null, stepId: null, offset: 0, events: [], timer: null, busy: false, missing: 0 });
  }

  // Poll only while the selected step is running and its transcript tab is open.
  function syncLiveTranscript(step) {
    const live = state.live;
    const wanted = state.activeStageTab === "transcript" && isStepRunning(step) && state.selectedRunId;
    if (!wanted) {
      if (live.stepId) stopLivePolling();
      return;
    }
    if (live.stepId === step.step_id && live.timer) return;
    stopLivePolling();
    live.runId = state.selectedRunId;
    live.stepId = step.step_id;
    live.timer = setInterval(pollLiveTranscript, LIVE_POLL_MS);
    pollLiveTranscript();
  }

  async function pollLiveTranscript() {
    const live = state.live;
    if (live.busy || !live.stepId) return;
    const { runId, stepId } = live;
    live.busy = true;
    try {
      const url = `/api/runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/transcript?after=${live.offset}`;
      const data = await apiGet(url);
      if (live.stepId !== stepId) return; // selection changed mid-flight
      if (data.reset) live.events = [];
      live.offset = data.next_offset;
      if (data.exists) {
        live.missing = 0;
      } else if (live.events.length > 0 && ++live.missing >= LIVE_MISSING_LIMIT) {
        // The pipeline deletes the stream once the durable transcript is saved:
        // the step finished, so reload and show the final transcript.
        stopLivePolling();
        await loadRuns(true); // refreshes the sidebar list and the selected run
        return;
      }
      if (data.reset || data.events.length) {
        live.events.push(...data.events);
        renderLiveTranscript();
      }
    } catch {
      // Ignore transient poll errors; the next tick retries.
    } finally {
      live.busy = false;
    }
  }

  function renderLiveTranscript() {
    const step = getSelectedStep();
    const container = $("stageTabContent");
    if (!step || !container || state.activeStageTab !== "transcript") return;

    const tmp = document.createElement("div");
    tmp.innerHTML = renderStageTranscript(step);
    const fresh = tmp.querySelector("#turnsTimeline");
    const timeline = container.querySelector("#turnsTimeline");
    if (!fresh || !timeline) {
      // First events: swap the "nothing recorded" placeholder for the full view.
      container.innerHTML = tmp.innerHTML;
      bindTranscriptHandlers(container);
      $("cntTranscript").textContent = String(state.live.events.length);
      return;
    }

    const scroller = document.scrollingElement || document.documentElement;
    const nearBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
    const openStates = [...timeline.querySelectorAll("details")].map((d) => d.open);
    const hiddenBodies = [...timeline.querySelectorAll(".turn-body")].map((b) => b.classList.contains("hidden"));

    timeline.innerHTML = fresh.innerHTML;
    timeline.querySelectorAll("details").forEach((d, i) => { if (i < openStates.length) d.open = openStates[i]; });
    timeline.querySelectorAll(".turn-body").forEach((b, i) => {
      if (i < hiddenBodies.length) b.classList.toggle("hidden", hiddenBodies[i]);
    });
    bindTurnHeaders(container);
    container.querySelector("#transcriptSearch")?.dispatchEvent(new Event("input"));
    $("cntTranscript").textContent = String(state.live.events.length);
    if (nearBottom) scroller.scrollTop = scroller.scrollHeight;
  }

  function renderLatticeGraph(qualifiers) {
    if (!qualifiers || qualifiers.length === 0) return "";

    const norm = (s) => String(s || "").trim().replace(/^@+/, "");
    const byName = new Map();
    for (const q of qualifiers) {
      byName.set(norm(q.qualifier_name), q);
    }

    // Parse parent edges (subtypes_of can be a single name or comma-separated)
    const parentsOf = new Map();
    for (const q of qualifiers) {
      const name = norm(q.qualifier_name);
      const parents = (q.subtypes_of || "")
        .split(",")
        .map(norm)
        .filter((p) => p && byName.has(p));
      parentsOf.set(name, parents);
    }

    // Compute topological depth (TOP at 0, children below)
    const depth = new Map();
    const visiting = new Set();
    function getDepth(name) {
      if (depth.has(name)) return depth.get(name);
      if (visiting.has(name)) return 0; // cycle guard
      visiting.add(name);
      const parents = parentsOf.get(name) || [];
      const d = parents.length === 0 ? 0 : 1 + Math.max(...parents.map(getDepth));
      visiting.delete(name);
      depth.set(name, d);
      return d;
    }
    for (const q of qualifiers) getDepth(norm(q.qualifier_name));

    const maxDepth = Math.max(0, ...depth.values());
    const layers = Array.from({ length: maxDepth + 1 }, () => []);
    for (const q of qualifiers) {
      layers[depth.get(norm(q.qualifier_name))].push(q);
    }

    const nodeW = 164;
    const nodeH = 36;
    const hGap = 24;
    const vGap = 46;
    const padX = 24;
    const padY = 20;

    const maxCols = Math.max(1, ...layers.map((l) => l.length));
    const width = Math.max(360, padX * 2 + maxCols * nodeW + (maxCols - 1) * hGap);
    const height = padY * 2 + layers.length * nodeH + (layers.length - 1) * vGap;

    const pos = new Map();
    layers.forEach((layer, li) => {
      const rowW = layer.length * nodeW + (layer.length - 1) * hGap;
      const startX = (width - rowW) / 2;
      const y = padY + li * (nodeH + vGap);
      layer.forEach((q, idx) => {
        pos.set(norm(q.qualifier_name), {
          x: startX + idx * (nodeW + hGap),
          y,
          cx: startX + idx * (nodeW + hGap) + nodeW / 2,
          cy: y + nodeH / 2,
        });
      });
    });

    const edgesSvg = [];
    for (const q of qualifiers) {
      const name = norm(q.qualifier_name);
      const childPos = pos.get(name);
      for (const pName of (parentsOf.get(name) || [])) {
        const parentPos = pos.get(pName);
        if (!parentPos || !childPos) continue;
        const x1 = childPos.cx;
        const y1 = childPos.y;
        const x2 = parentPos.cx;
        const y2 = parentPos.y + nodeH;
        edgesSvg.push(`<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#58a6ff" stroke-opacity="0.65" stroke-width="1.5" marker-end="url(#latticeArrow)" />`);
      }
    }

    const nodesSvg = qualifiers.map((q) => {
      const name = norm(q.qualifier_name);
      const p = pos.get(name);
      const fill = q.is_top ? "#1b2b3a" : (q.is_bottom ? "#2b1f36" : "#1c222b");
      const stroke = q.is_top ? "#58a6ff" : (q.is_bottom ? "#a371f7" : "#30363d");
      const role = q.is_top ? "TOP" : (q.is_bottom ? "BOTTOM" : "");
      return `
        <g class="lattice-node" data-qual="${esc(name)}">
          <rect x="${p.x}" y="${p.y}" width="${nodeW}" height="${nodeH}" rx="7" fill="${fill}" stroke="${stroke}" stroke-width="1.5" />
          <text x="${p.cx}" y="${p.y + (role ? 15 : 22)}" text-anchor="middle" fill="#e6edf3" font-family="ui-monospace, SFMono-Regular, Menlo, monospace" font-size="11.5" font-weight="600">@${esc(name)}</text>
          ${role ? `<text x="${p.cx}" y="${p.y + 28}" text-anchor="middle" fill="#8b949e" font-family="sans-serif" font-size="9.5">${role}</text>` : ""}
        </g>
      `;
    }).join("");

    return `
      <div style="margin-bottom:12px;overflow-x:auto;border:1px solid var(--border-subtle);border-radius:8px;background:var(--bg-elev);padding:8px;">
        <svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" style="display:block;margin:0 auto;">
          <defs>
            <marker id="latticeArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M 0 1 L 10 5 L 0 9 z" fill="#58a6ff" />
            </marker>
          </defs>
          ${edgesSvg.join("")}
          ${nodesSvg}
        </svg>
      </div>
    `;
  }

  // ---- Stage Tab: Overview & Artifacts ----
  function renderStageOverview(step) {
    const manifestArtifacts = step.artifacts_manifest?.artifacts || [];
    const casFiles = step.cas_files || [];
    const sess = sessionStats(step);
    const sessionCell = sess
      ? `${sess.sessions} (${sess.retries ? `${sess.retries} session retr${sess.retries === 1 ? "y" : "ies"}` : "no retries"})`
      : `<span class="muted">unknown — no transcript available</span>`;
    const retryEvents = (state.runDetail?.retry_events || []).filter((e) => e.step_id === step.step_id);
    const retryCell = retryEvents.length
      ? retryEvents.map((e) => `<span class="badge ${e.will_retry ? "badge-yellow" : "badge-red"}" title="${esc(e.error_message || "")}">${e.will_retry ? "scheduled another stage attempt" : "final stage failure"}</span>`).join(" ")
      : `<span class="muted">none</span>`;
    const discardedCount = (step.discarded_attempts || []).length;

    const summaryCard = `
      <div class="card">
        <h4>Stage Metadata</h4>
        <table class="kv-table">
          <tr><th>Step ID</th><td><code>${esc(step.step_id)}</code></td></tr>
          <tr><th>Stage attempt</th><td>${esc(step.attempt_number)} of ${stageAttemptCount(state.runDetail, step)} <span class="muted">(orchestrator retry of the whole stage)</span></td></tr>
          <tr><th>Agent sessions</th><td>${sessionCell}</td></tr>
          <tr><th>Stage retry decision</th><td>${retryCell}</td></tr>
          ${discardedCount ? `<tr><th>Discarded agent attempts</th><td>${discardedCount} (session retries, tracked separately from stage attempts)</td></tr>` : ""}
          <tr><th>Success</th><td>${isSkippedStep(state.runDetail, step)
            ? `<span class="badge badge-muted" title="The stage did not fail; triage rejected the commit, which is a filtering decision.">skipped (triage discard)</span>`
            : isStepRunning(step)
              ? `<span class="badge badge-yellow">running</span>`
              : `<span class="badge ${step.success ? "badge-green" : "badge-red"}">${step.success ? "true" : "false"}</span>`}</td></tr>
          <tr><th>Cache Hit</th><td>${step.cache_hit ? "Yes" : "No"}</td></tr>
          ${step.cache_hit ? `<tr><th>Cache Origin</th><td>${step.cache_origin
            ? `<button class="link-btn cache-origin-link" data-origin-run="${esc(step.cache_origin.run_id)}">${esc(step.cache_origin.run_id.slice(0, 8))}</button> <span class="muted">(${esc(step.cache_origin.benchmark_id)} · ${esc(fmtTs(step.cache_origin.started_at))})</span>`
            : `<span class="muted">Not in current DB (loaded from .cache/stages)</span>`}</td></tr>` : ""}
          <tr><th>CAS Key</th><td><code>${esc(step.cas_key || "-")}</code></td></tr>
          <tr><th>Started</th><td>${esc(fmtTs(step.started_at))}</td></tr>
          <tr><th>Completed</th><td>${esc(fmtTs(step.completed_at))}</td></tr>
        </table>
        <p class="muted" style="font-size:11px;margin:10px 0 0;">Stage attempts are whole-stage retries by the orchestrator (see retry_events). Agent sessions are separate agent runs inside one stage attempt; every session after the first is a feedback retry, shown in the Transcript tab.</p>
      </div>
    `;

    const artifactsCard = `
      <div class="card">
        <h4>CAS Artifacts ${step.cas_dir_exists ? "" : `<span class="badge badge-muted">CAS dir not on disk</span>`}</h4>
        ${casFiles.length > 0 ? `
          <table class="data-table">
            <thead><tr><th>File</th><th>Size</th></tr></thead>
            <tbody>
              ${casFiles.map((f) => `
                <tr>
                  <td>${f.is_text ? `<button class="link-btn artifact-link" data-path="${esc(f.path)}">${esc(f.path)}</button>` : `<code>${esc(f.path)}</code>`}</td>
                  <td>${esc(fmtBytes(f.size_bytes))}</td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        ` : (manifestArtifacts.length > 0 ? `
          <table class="data-table">
            <thead><tr><th>Artifact</th><th>Size</th><th>SHA-256</th></tr></thead>
            <tbody>
              ${manifestArtifacts.map((a) => `
                <tr>
                  <td><code>${esc(a.path)}</code></td>
                  <td>${esc(fmtBytes(a.size_bytes))}</td>
                  <td><code>${esc((a.sha256 || "").slice(0, 12))}</code></td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        ` : `<div class="muted">No artifacts recorded for this stage.</div>`)}
      </div>
    `;

    let stageSpecific = "";
    if ((step.synthesis_runs || []).length > 0 || (step.synthesized_qualifiers || []).length > 0) {
      const sr = step.synthesis_runs[0];
      const quals = step.synthesized_qualifiers || [];
      stageSpecific += `
        <div class="card">
          <h4>Synthesized Type System</h4>
          ${sr ? `
            <table class="kv-table" style="margin-bottom:10px;">
              <tr><th>Processor</th><td><code>${esc(sr.processor)}</code></td></tr>
              <tr><th>Category</th><td>${esc(sr.checker_category)}</td></tr>
              <tr><th>Qualifiers / Helpers / Rules</th><td>${sr.qualifier_count} qualifiers, ${sr.helpers_count} helpers, ${sr.policy_rule_count} policy rules</td></tr>
            </table>
          ` : ""}
          ${quals.length > 0 ? renderLatticeGraph(quals) : ""}
          ${quals.length > 0 ? `
            <table class="data-table">
              <thead><tr><th>Qualifier</th><th>Target Type</th><th>Subtypes Of</th><th>Top / Bottom</th></tr></thead>
              <tbody>
                ${quals.map((q) => {
                  const qName = String(q.qualifier_name || "").trim().replace(/^@+/, "");
                  return `
                  <tr class="lattice-row" data-qual="${esc(qName)}">
                    <td><code>@${esc(qName)}</code></td>
                    <td><code>${esc(q.target_type)}</code></td>
                    <td><code>${esc(q.subtypes_of || "-")}</code></td>
                    <td>${q.is_top ? "TOP" : ""}${q.is_bottom ? "BOTTOM" : ""}${!q.is_top && !q.is_bottom ? "-" : ""}</td>
                  </tr>
                `;
                }).join("")}
              </tbody>
            </table>
          ` : ""}
        </div>
      `;
    }

    if (step.step_name === "triage") {
      const r = state.runDetail || {};
      const tm = step.triage_metadata || {};
      const kc = r.key_config || {};
      const provenance = tm.source_provenance || kc.triage_mode || "manifest";
      const triageStatus = effectiveTriageStatus(r);
      const triageMeta = triageStatusMeta(triageStatus);
      const discardReason = r.discard_reason || tm.discard_reason || "-";
      const classification = r.classification || tm.llm_classification || "-";
      // Only the run-list column is a facet value; metadata-only fallbacks stay plain.
      const classificationCell = r.classification
        ? facetControl("classification", r.classification, { plain: true, inner: `<code>${esc(classification)}</code>`, label: classification, tooltip: "LLM triage classification" })
        : `<code>${esc(classification)}</code>`;
      const score = r.bugfix_score ?? tm.bugfix_score;
      const threshold = kc.triage_bugfix_threshold;
      const regClass = tm.regression_test_class || r.regression_test_class;
      const regMethod = tm.regression_test_method || r.regression_test_method;
      const regTest = regClass ? `${regClass}${regMethod ? "." + regMethod : ""}` : "-";
      const details = tm.details || step.compiler_diagnostics || "";

      stageSpecific += `
        <div class="card">
          <h4><span>Triage Details (triage mode: ${facetBadge("triageMode", provenance, "badge-muted", provenance, "Triage provenance")})</span></h4>
          <table class="data-table">
            <tbody>
              <tr><th>Triage Status</th><td>${facetBadge("triageStatus", triageStatus, triageMeta.cls, triageMeta.label, triageMeta.tooltip)} <code class="muted">${esc(triageStatus)}</code></td><th>Discard Reason</th><td><code>${esc(discardReason)}</code></td></tr>
              <tr><th>Classification</th><td>${classificationCell}</td><th>Bugfix Score</th><td>${score !== null && score !== undefined ? `<code>${esc(score)}</code>${threshold !== null && threshold !== undefined ? ` (threshold: <code>${esc(threshold)}</code>)` : ""}` : "-"}</td></tr>
              <tr><th>Regression Test</th><td colspan="3"><code>${esc(regTest)}</code></td></tr>
              <tr><th>Path Audit</th><td><code>${esc(tm.path_audit_result || "-")}</code></td><th>C-1 Build</th><td><code>${esc(tm.c1_build_status || "-")}</code></td></tr>
              <tr><th>C-1 Test</th><td><code>${esc(tm.c1_test_status || "-")}</code></td><th>C Test</th><td><code>${esc(tm.c_test_status || "-")}</code></td></tr>
            </tbody>
          </table>
          ${tm.llm_rationale ? `<div style="margin-top:10px;"><strong>LLM Rationale:</strong> ${esc(tm.llm_rationale)}</div>` : ""}
          ${details ? `<div style="margin-top:8px;"><strong>Details:</strong> <pre class="code-block" style="margin-top:4px;">${esc(details)}</pre></div>` : ""}
          ${r.commit_compiler_error_log ? `<div style="margin-top:8px;"><strong>Compiler Error Log:</strong> <pre class="code-block" style="margin-top:4px;">${esc(r.commit_compiler_error_log)}</pre></div>` : ""}
        </div>
      `;
    }

    if (step.placement_guide) {
      stageSpecific += `
        <div class="card">
          <h4><span>Placement Guide (<code>placement_guide.md</code>)</span></h4>
          <div class="md-body">${renderMarkdown(step.placement_guide)}</div>
        </div>
      `;
    }

    if ((step.annotation_placements || []).length > 0) {
      stageSpecific += `
        <div class="card">
          <h4>Annotation Placements (${step.annotation_placements.length})</h4>
          <table class="data-table">
            <thead><tr><th>File</th><th>Line</th><th>Symbol</th><th>Qualifier</th><th>Tier</th></tr></thead>
            <tbody>
              ${step.annotation_placements.map((p) => {
                const qName = String(p.qualifier || "").trim().replace(/^@+/, "");
                return `
                <tr class="placement-row" data-placement-id="${esc(p.placement_id)}" tabindex="0" role="button" title="Show the ${esc(p.symbol)} diff for ${esc(p.file_path)}:${esc(p.line_number)}">
                  <td><code>${esc(p.file_path)}</code></td>
                  <td>${esc(p.line_number)}</td>
                  <td><code>${esc(p.symbol)}</code> <span class="placement-diff-link" title="View method diff">&#8599;</span></td>
                  <td><code>@${esc(qName)}</code></td>
                  <td>${esc(p.target_tier ?? "-")}</td>
                </tr>
              `;
              }).join("")}
            </tbody>
          </table>
        </div>
      `;
    }

    return `<div class="grid-2">${summaryCard}${artifactsCard}</div>${stageSpecific}`;
  }

  function bindArtifactLinks(container, step) {
    container.querySelectorAll(".cache-origin-link").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.selectedStepName = step.step_name;
        state.selectedStepId = null;
        selectRun(btn.dataset.originRun, true);
      });
    });

    container.querySelectorAll(".lattice-node, .lattice-row").forEach((el) => {
      const qName = el.dataset.qual;
      if (!qName) return;
      el.addEventListener("mouseenter", () => {
        container.querySelectorAll(`[data-qual="${CSS.escape(qName)}"]`).forEach((m) => m.classList.add("highlight"));
      });
      el.addEventListener("mouseleave", () => {
        container.querySelectorAll(`[data-qual="${CSS.escape(qName)}"]`).forEach((m) => m.classList.remove("highlight"));
      });
    });

    container.querySelectorAll(".placement-row").forEach((row) => {
      const open = () => openPlacementDiff(step, row.dataset.placementId);
      row.addEventListener("click", open);
      row.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          open();
        }
      });
    });

    container.querySelectorAll(".artifact-link").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const relPath = btn.dataset.path;
        const encoded = relPath.split("/").map(encodeURIComponent).join("/");
        try {
          const res = await apiGet(`/api/runs/${encodeURIComponent(state.selectedRunId)}/steps/${encodeURIComponent(step.step_id)}/artifact/${encoded}`);
          $("artifactModalTitle").textContent = relPath;
          const bodyEl = $("artifactModalBody");
          if (relPath.toLowerCase().endsWith(".md")) {
            bodyEl.classList.add("md-body");
            bodyEl.innerHTML = renderMarkdown(res.content);
          } else {
            bodyEl.classList.remove("md-body");
            bodyEl.textContent = res.content;
          }
          $("artifactModal").classList.remove("hidden");
        } catch (err) {
          alert(`Failed to load artifact: ${err.message}`);
        }
      });
    });
  }

  // ---- Stage Tab: Patch / Diff ----
  function parseUnifiedDiff(diffText) {
    const lines = diffText.split(/\r?\n/);
    const files = [];
    let current = null;
    let oldLn = 0;
    let newLn = 0;

    for (const line of lines) {
      if (line.startsWith("diff --git ") || (!current && line.startsWith("--- "))) {
        if (line.startsWith("diff --git ")) {
          const parts = line.split(" ");
          const name = (parts[3] || parts[2] || "patch").replace(/^[ab]\//, "");
          current = { name, adds: 0, dels: 0, rows: [] };
          files.push(current);
          continue;
        }
      }
      if (!current) {
        current = { name: "patch", adds: 0, dels: 0, rows: [] };
        files.push(current);
      }
      if (line.startsWith("+++ ")) {
        const fname = line.slice(4).trim().replace(/^b\//, "");
        if (fname && fname !== "/dev/null") current.name = fname;
        current.rows.push({ type: "meta", oldLn: "", newLn: "", text: line });
      } else if (line.startsWith("--- ") || line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file")) {
        current.rows.push({ type: "meta", oldLn: "", newLn: "", text: line });
      } else if (line.startsWith("@@")) {
        const m = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
        if (m) {
          oldLn = parseInt(m[1], 10);
          newLn = parseInt(m[2], 10);
        }
        current.rows.push({ type: "hunk", oldLn: "...", newLn: "...", text: line });
      } else if (line.startsWith("+")) {
        current.adds++;
        current.rows.push({ type: "add", oldLn: "", newLn: newLn++, text: line });
      } else if (line.startsWith("-")) {
        current.dels++;
        current.rows.push({ type: "del", oldLn: oldLn++, newLn: "", text: line });
      } else {
        current.rows.push({ type: "ctx", oldLn: oldLn ? oldLn++ : "", newLn: newLn ? newLn++ : "", text: line });
      }
    }
    return files;
  }

  function renderStagePatch(step) {
    const patch = step.diff_patch || step.cas_diff_patch;
    if (!patch) {
      return `<div class="card"><div class="muted">No diff patch recorded for this stage.</div></div>`;
    }
    const label = step.diff_patch ? "step_trajectories.diff_patch" : `CAS: ${step.cas_diff_name || "patch"}`;
    const files = parseUnifiedDiff(patch);
    return `
      <div class="diff-toolbar">
        <span class="muted">Source: <code>${esc(label)}</code> &middot; ${files.length} file(s)</span>
        <button id="copyPatchBtn" class="btn">Copy Patch</button>
      </div>
      ${files.map((f, idx) => `
        <div class="diff-file">
          <div class="diff-file-header" data-diff-idx="${idx}">
            <span>${esc(f.name)}</span>
            <span>
              <span class="badge badge-green">+${f.adds}</span>
              <span class="badge badge-red">-${f.dels}</span>
            </span>
          </div>
          <div class="diff-lines" id="diffLines_${idx}">
            ${f.rows.map((r) => `
              <div class="diff-line ${esc(r.type)}">
                <span class="diff-ln">${esc(r.oldLn)}</span>
                <span class="diff-ln">${esc(r.newLn)}</span>
                <span>${esc(r.text)}</span>
              </div>
            `).join("")}
          </div>
        </div>
      `).join("")}
    `;
  }

  function bindDiffHandlers(container, step) {
    const patch = step.diff_patch || step.cas_diff_patch;
    const copyBtn = container.querySelector("#copyPatchBtn");
    if (copyBtn && patch) {
      copyBtn.addEventListener("click", () => copyText(patch, copyBtn));
    }
    container.querySelectorAll(".diff-file-header").forEach((hdr) => {
      hdr.addEventListener("click", () => {
        const body = container.querySelector(`#diffLines_${hdr.dataset.diffIdx}`);
        if (body) body.classList.toggle("hidden");
      });
    });
  }

  // ---- Annotation Placement Method Diff Modal ----
  function renderModalDiff(diffText) {
    const files = parseUnifiedDiff(diffText);
    if (!files.length) {
      return `<div class="placement-diff-note muted">No diff content.</div>`;
    }
    return files.map((f) => `
      <div class="diff-file">
        <div class="diff-file-header">
          <span>${esc(f.name)}</span>
          <span>
            <span class="badge badge-green">+${f.adds}</span>
            <span class="badge badge-red">-${f.dels}</span>
          </span>
        </div>
        <div class="diff-lines">
          ${f.rows.map((r) => `
            <div class="diff-line ${esc(r.type)}">
              <span class="diff-ln">${esc(r.oldLn)}</span>
              <span class="diff-ln">${esc(r.newLn)}</span>
              <span>${esc(r.text)}</span>
            </div>
          `).join("")}
        </div>
      </div>
    `).join("");
  }

  async function openPlacementDiff(step, placementId) {
    const placement = (step.annotation_placements || []).find((p) => p.placement_id === placementId);
    const modal = $("placementDiffModal");
    if (!placement || !modal) return;

    const qName = String(placement.qualifier || "").trim().replace(/^@+/, "");
    const body = $("placementDiffBody");
    modal.classList.remove("hidden");
    $("placementDiffTitle").textContent = `${placement.file_path}:${placement.line_number}`;
    $("placementDiffMeta").textContent = `${placement.symbol} · @${qName}`;
    body.innerHTML = `<div class="placement-diff-note muted">Loading method diff…</div>`;
    state.placementDiffText = "";
    const requestId = state.placementDiffRequest + 1;
    state.placementDiffRequest = requestId;

    const url = `/api/runs/${encodeURIComponent(state.selectedRunId)}/steps/${encodeURIComponent(step.step_id)}/placement-diff?placement_id=${encodeURIComponent(placementId)}`;
    try {
      const res = await apiGet(url);
      if (state.placementDiffRequest !== requestId) return;
      const qualifier = String(res.qualifier || "").trim().replace(/^@+/, "");
      const memberLabel = res.member_kind === "method"
        ? `method ${res.member_name || "?"}()`
        : `${res.member_kind || "member"} ${res.member_name || "?"}`;
      const qualifierLabel = qualifier ? `@${qualifier}` : null;
      const symbolLabel = res.symbol && res.symbol !== res.member_kind ? res.symbol : null;
      $("placementDiffTitle").textContent = `${res.file_path}:${res.line_number}`;
      $("placementDiffMeta").textContent = [memberLabel, qualifierLabel, symbolLabel].filter(Boolean).join(" · ");

      if (res.has_diff && res.diff) {
        body.innerHTML = renderModalDiff(res.diff);
        state.placementDiffText = res.diff;
      } else {
        const note = res.note || "No diff is available for this placement.";
        const snippet = res.snippet
          ? `<pre class="code-block placement-snippet">${esc(res.snippet)}</pre>`
          : "";
        body.innerHTML = `<div class="placement-diff-note muted">${esc(note)}</div>${snippet}`;
      }
    } catch (err) {
      if (state.placementDiffRequest !== requestId) return;
      body.innerHTML = `<div class="placement-diff-note muted">Failed to load diff: ${esc(err.message)}</div>`;
    }
  }

  // ---- Stage Tab: Transcript & Turns ----
  function buildInvocations(step) {
    // Durable transcripts win (CAS first, then the DB copy); while a step runs,
    // fall back to the provisional live stream.
    const events = durableTranscript(step) || liveEventsFor(step);

    if (events.length > 0) {
      const invocations = [];
      let current = { index: 1, timestamp: null, prompt: null, events: [] };

      for (const ev of events) {
        if (isSessionStart(ev)) {
          if (current.prompt !== null || current.events.length > 0) {
            invocations.push(current);
            current = { index: invocations.length + 1, timestamp: null, prompt: null, events: [] };
          }
          current.timestamp = ev.timestamp || null;
          current.prompt = ev.content || ev.prompt || "";
        } else {
          current.events.push(ev);
        }
      }
      if (current.prompt !== null || current.events.length > 0) {
        invocations.push(current);
      }

      return invocations.map((inv) => ({
        ...inv,
        // Session boundaries only exist in the transcript; DB telemetry rows are
        // not session-tagged, so keep them with session #1 rather than guessing.
        turns: buildTurnsFromEvents(inv.events, inv.index === 1 ? step : null),
      }));
    }

    // No transcript at all: one synthetic session from the DB-only telemetry.
    const turns = buildTurnTimeline(step);
    return [{
      index: 1,
      timestamp: step.started_at || null,
      prompt: step.initial_prompt || null,
      turns,
    }];
  }

  function buildTurnsFromEvents(casEvents, correlated) {
    const byTurn = new Map();
    const ensureTurn = (idx) => {
      const key = idx ?? 0;
      if (!byTurn.has(key)) {
        byTurn.set(key, { turnIndex: key, events: [], llmCalls: [] });
      }
      return byTurn.get(key);
    };

    for (const ev of casEvents) {
      const t = ensureTurn(ev.turn_index ?? 0);
      t.events.push({
        kind: ev.event_type || "event",
        timestamp: ev.timestamp,
        toolName: ev.tool_name,
        arguments: ev.tool_arguments,
        result: ev.tool_result,
        content: ev.content,
        thinking: ev.thinking ?? ev.reasoning ?? ev.thought ?? null,
      });
    }

    if (correlated) {
      for (const llm of (correlated.llm_invocations || [])) {
        ensureTurn(llm.turn_index ?? 0).llmCalls.push(llm);
      }
      for (const g of (correlated.guardrail_events || [])) {
        ensureTurn(g.turn_index ?? 0).events.push({ kind: "guardrail", timestamp: g.created_at, guard: g });
      }
      for (const ce of (correlated.compilation_events || [])) {
        ensureTurn(ce.turn_index ?? 0).events.push({ kind: "compilation", timestamp: ce.created_at, compilation: ce });
      }
    }

    const turns = [...byTurn.values()].sort((a, b) => a.turnIndex - b.turnIndex);
    for (const t of turns) {
      const merged = [];
      for (const ev of t.events) {
        const prev = merged[merged.length - 1];
        if (
          ev.kind === "tool_result" &&
          prev &&
          prev.kind === "tool_call" &&
          prev.result === undefined &&
          (!ev.toolName || !prev.toolName || ev.toolName === prev.toolName)
        ) {
          prev.result = ev.result;
          prev.resultTimestamp = ev.timestamp;
          prev.paired = true;
        } else {
          merged.push(ev);
        }
      }
      t.events = merged;
    }
    return turns;
  }

  function buildTurnTimeline(step) {
    const byTurn = new Map();
    const ensureTurn = (idx) => {
      const key = idx ?? 0;
      if (!byTurn.has(key)) {
        byTurn.set(key, {
          turnIndex: key,
          events: [],
          llmCalls: [],
        });
      }
      return byTurn.get(key);
    };

    if ((step.agent_turns || []).length > 0) {
      for (const at of step.agent_turns) {
        const t = ensureTurn(at.turn_index);
        t.events.push({
          kind: at.action_type || "turn",
          timestamp: at.created_at,
          toolName: at.tool_name,
          arguments: at.tool_arguments_parsed ?? at.tool_arguments,
          result: at.tool_result_summary,
        });
      }
    } else if (Array.isArray(step.agent_transcript) && step.agent_transcript.length > 0) {
      step.agent_transcript.forEach((ev, i) => {
        if (ev.event_type === "initial_prompt" || ev.event_type === "prompt" || ev.event_type === "user_prompt") {
          return;
        }
        const t = ensureTurn(ev.turn_index ?? i + 1);
        t.events.push({
          kind: ev.event_type || "transcript",
          timestamp: ev.timestamp,
          toolName: ev.tool_name,
          arguments: ev.tool_arguments,
          result: ev.tool_result ?? ev.content,
          thinking: ev.thinking ?? ev.reasoning ?? ev.thought ?? null,
        });
      });
    }

    for (const llm of (step.llm_invocations || [])) {
      ensureTurn(llm.turn_index ?? 0).llmCalls.push(llm);
    }

    for (const g of (step.guardrail_events || [])) {
      ensureTurn(g.turn_index ?? 0).events.push({
        kind: "guardrail",
        timestamp: g.created_at,
        guard: g,
      });
    }

    for (const ce of (step.compilation_events || [])) {
      ensureTurn(ce.turn_index ?? 0).events.push({
        kind: "compilation",
        timestamp: ce.created_at,
        compilation: ce,
      });
    }

    const turns = [...byTurn.values()].sort((a, b) => a.turnIndex - b.turnIndex);
    for (const t of turns) {
      const merged = [];
      for (const ev of t.events) {
        const prev = merged[merged.length - 1];
        if (
          ev.kind === "tool_result" &&
          prev &&
          prev.kind === "tool_call" &&
          prev.result === undefined &&
          (!ev.toolName || !prev.toolName || ev.toolName === prev.toolName)
        ) {
          prev.result = ev.result;
          prev.resultTimestamp = ev.timestamp;
          prev.paired = true;
        } else {
          merged.push(ev);
        }
      }
      t.events = merged;
    }
    return turns;
  }

  function renderTurnGroup(t) {
    const tok = t.llmCalls.reduce((a, c) => a + (c.total_tokens || 0), 0);
    const lat = t.llmCalls.reduce((a, c) => a + (c.latency_ms || 0), 0);
    const tools = [...new Set(t.events.map((e) => e.toolName).filter(Boolean))];
    const hasGuard = t.events.some((e) => e.kind === "guardrail");

    return `
      <div class="turn-group" data-turn="${t.turnIndex}">
        <div class="turn-header">
          <div class="turn-header-left">
            <strong title="Turn numbering restarts at 1 in every agent session">Turn ${esc(t.turnIndex)}</strong>
            <span class="badge badge-muted">${t.events.length} events</span>
            ${tools.map((tn) => `<span class="badge badge-blue">${esc(tn)}</span>`).join("")}
            ${hasGuard ? `<span class="badge badge-red">guardrail</span>` : ""}
          </div>
          <div class="muted" style="font-size:11px;">
            ${tok ? `${fmtNum(tok)} tok` : ""}${lat ? ` · ${fmtNum(lat)} ms` : ""}
          </div>
        </div>
        <div class="turn-body">
          ${t.events.map((ev) => renderTimelineEvent(ev)).join("")}
        </div>
      </div>
    `;
  }

  function renderDiscardedAttempts(step) {
    const rows = step.discarded_attempts || [];
    if (!rows.length) return "";
    return `
      <div class="card" style="margin-bottom:12px;">
        <h4>Discarded agent attempts (${rows.length})</h4>
        <p class="muted" style="font-size:11px;">Agent sessions whose output was discarded. “Stage attempt” is the orchestrator counter; “internal attempt” is the session retry counter inside that stage attempt.</p>
        <table class="data-table">
          <thead><tr><th>Stage attempt</th><th>Internal attempt (session)</th><th>Termination</th><th>Discard check</th><th>Steps</th><th>Failure</th></tr></thead>
          <tbody>${rows.map((d) => `
            <tr>
              <td>${esc(d.stage_attempt_number)}</td>
              <td>${esc(d.internal_attempt_number)}${Number(d.internal_attempt_number) > 1 ? ` <span class="badge badge-yellow">session retry ${Number(d.internal_attempt_number) - 1}</span>` : ""}</td>
              <td>${esc(d.termination)}</td>
              <td><code>${esc(d.discard_check)}</code></td>
              <td>${esc(d.steps_counted)}</td>
              <td>${d.agent_failure_message ? `<pre class="event-pre">${esc(String(d.agent_failure_message).slice(0, 400))}</pre>` : `<span class="muted">-</span>`}</td>
            </tr>
          `).join("")}</tbody>
        </table>
      </div>
    `;
  }

  function renderStageTranscript(step) {
    const invocations = buildInvocations(step);
    const hasAnyTurns = invocations.some((inv) => inv.turns.length > 0 || inv.prompt);

    if (!hasAnyTurns) {
      return `<div class="card"><div class="muted">No agent turns or transcript recorded for this stage.</div></div>`;
    }

    const hasTranscript = Boolean(durableTranscript(step));
    const intro = hasTranscript
      ? `Each agent session is one <code>runAgent()</code> call inside stage attempt ${esc(step.attempt_number)}. Sessions after #1 are feedback retries within this attempt — not orchestrator stage retries. DB telemetry rows are not session-tagged, so LLM/guardrail/compilation data is only shown under session #1.`
      : `No durable transcript is available, so DB telemetry is shown as one synthetic session; the real session count is unknown.`;
    const bodyHtml = invocations.map((inv) => {
      const retryBadge = inv.index > 1
        ? `<span class="badge badge-yellow" title="Feedback retry: a later runAgent() call inside this same stage attempt, not an orchestrator stage retry">session retry ${inv.index - 1}</span>`
        : `<span class="badge badge-muted" title="First agent run in this stage attempt">initial run</span>`;
      const promptHtml = inv.prompt
        ? `<div style="margin-bottom:10px;">${renderCollapsibleCode(`Agent session #${inv.index} prompt`, inv.prompt, false)}</div>`
        : "";
      return `
        <div class="card" style="margin-bottom:14px;">
          <h4>
            <span>Agent session #${inv.index}</span>
            ${retryBadge}
            <span class="muted" style="font-weight:400;font-size:11px;">${inv.timestamp ? esc(fmtTs(inv.timestamp)) : ""} &middot; ${inv.turns.length} turn(s)</span>
          </h4>
          ${promptHtml}
          ${inv.turns.map((t) => renderTurnGroup(t)).join("")}
        </div>
      `;
    }).join("");

    return `
      ${renderDiscardedAttempts(step)}
      <div class="card" style="margin-bottom:12px;"><div class="muted" style="font-size:11px;">${intro}</div></div>
      <div class="transcript-toolbar">
        <input id="transcriptSearch" type="search" placeholder="Search transcript..." />
        <select id="transcriptKindFilter">
          <option value="">All event types</option>
          <option value="tool_call">tool_call</option>
          <option value="tool_result">tool_result</option>
          <option value="guardrail">guardrail</option>
          <option value="compilation">compilation</option>
        </select>
        <button id="expandAllTurnsBtn" class="btn">Expand All</button>
        <button id="collapseAllTurnsBtn" class="btn">Collapse All</button>
      </div>
      <div id="turnsTimeline">${bodyHtml}</div>
    `;
  }

  function renderCollapsibleCode(label, text, open = true) {
    const lines = String(text).split(/\r?\n/).length;
    return `
      <details class="code-collapse" ${open ? "open" : ""}>
        <summary>${esc(label)} <span class="muted">(${lines} line${lines === 1 ? "" : "s"})</span></summary>
        <pre class="event-pre">${esc(text)}</pre>
      </details>
    `;
  }

  function parseToolArguments(value) {
    if (value && typeof value === "object" && !Array.isArray(value)) return value;
    if (typeof value !== "string") return null;
    try {
      const parsed = JSON.parse(value);
      return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }

  function stringifyToolValue(value) {
    if (typeof value === "string") return value;
    try {
      return JSON.stringify(value, null, 2) ?? String(value);
    } catch {
      return String(value);
    }
  }

  function formatToolResponse(value) {
    if (typeof value !== "string") return stringifyToolValue(value);
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === "object") return JSON.stringify(parsed, null, 2);
    } catch {
      // Keep ordinary text output unchanged.
    }
    return value;
  }

  function toolArgumentPath(value) {
    const args = parseToolArguments(value);
    if (!args) return "";
    for (const key of ["filePath", "file_path", "path", "target", "file", "filename", "absolutePath"]) {
      if (typeof args[key] === "string" && args[key].trim()) return args[key].trim();
    }
    return "";
  }

  function toolResultSummary(value) {
    let result = value;
    if (typeof value === "string") {
      try {
        result = JSON.parse(value);
      } catch {
        return "";
      }
    }
    if (!result || typeof result !== "object" || Array.isArray(result)) return "";
    for (const key of ["title", "message", "summary"]) {
      if (typeof result[key] === "string" && result[key].trim()) return result[key].trim();
    }
    return "";
  }

  function toolArgumentLabel(key) {
    const labels = {
      filePath: "File path",
      file_path: "File path",
      path: "Path",
      oldString: "Replace this text",
      old_string: "Replace this text",
      newString: "With this text",
      new_string: "With this text",
      replaceAll: "Replace all matches",
      replace_all: "Replace all matches",
      workdir: "Working directory",
      cwd: "Working directory",
      include: "File filter",
    };
    return labels[key] || key.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());
  }

  function canonicalToolName(toolName) {
    const name = String(toolName || "").toLowerCase().replace(/[^a-z0-9]/g, "");
    if (["write", "writefile", "filewrite"].includes(name)) return "write";
    if (["edit", "editfile", "fileedit", "replace"].includes(name)) return "edit";
    if (["read", "readfile", "fileread"].includes(name)) return "read";
    if (["bash", "shell", "shellcommand", "runcommand"].includes(name)) return "bash";
    if (["glob", "findfiles"].includes(name)) return "glob";
    if (["grep", "search", "ripgrep", "rg"].includes(name)) return "search";
    return "other";
  }

  function renderToolArgumentField(key, value, toolType) {
    const label = toolArgumentLabel(key);
    const text = stringifyToolValue(value);
    const isEditText = toolType === "edit" && ["oldString", "old_string", "newString", "new_string"].includes(key);
    const isLongText = typeof value === "string" && (text.length > 150 || text.includes("\n"));
    const isStructured = value !== null && typeof value === "object";
    const isWriteContent = toolType === "write" && ["content", "contents", "fileContents", "file_content", "text"].includes(key);
    const isCommand = toolType === "bash" && key === "command";
    if (isEditText || isLongText || isStructured || isWriteContent || isCommand) {
      const lines = text.split(/\r?\n/).length;
      const open = text.length <= 700 && lines <= 10;
      return `<div class="tool-arg-block">${renderCollapsibleCode(label, text, open)}</div>`;
    }
    return `
      <div class="tool-arg-row">
        <span class="tool-arg-label">${esc(label)}</span>
        <span class="tool-arg-value">${esc(text)}</span>
      </div>
    `;
  }

  function renderToolArguments(toolName, value) {
    const args = parseToolArguments(value);
    if (!args) {
      const text = stringifyToolValue(value);
      const lines = text.split(/\r?\n/).length;
      return `<div class="tool-call-arguments"><div class="tool-args-title">Input</div>${renderCollapsibleCode("Value", text, text.length <= 700 && lines <= 10)}</div>`;
    }

    const toolType = canonicalToolName(toolName);
    const preferredKeys = {
      write: ["filePath", "file_path", "path", "file", "content", "contents", "fileContents", "file_content", "text"],
      edit: ["filePath", "file_path", "path", "oldString", "old_string", "newString", "new_string", "replaceAll", "replace_all"],
      read: ["filePath", "file_path", "path", "file", "offset", "limit"],
      bash: ["command", "workdir", "cwd", "timeout", "description"],
      glob: ["pattern", "path"],
      search: ["pattern", "path", "include"],
    }[toolType] || [];
    const keys = [
      ...preferredKeys.filter((key) => Object.prototype.hasOwnProperty.call(args, key)),
      ...Object.keys(args).filter((key) => !preferredKeys.includes(key)),
    ];
    const fields = keys.map((key) => renderToolArgumentField(key, args[key], toolType)).join("");
    return `
      <div class="tool-call-arguments">
        <div class="tool-args-title">Arguments</div>
        ${fields || `<span class="muted">No arguments</span>`}
      </div>
    `;
  }

  function renderToolResponse(value) {
    const text = formatToolResponse(value);
    const lines = text.split(/\r?\n/).length;
    return renderCollapsibleCode("Response", text, text.length <= 700 && lines <= 10);
  }

  function renderTimelineEvent(ev) {
    if (ev.kind === "guardrail") {
      const g = ev.guard;
      return `
        <div class="event-item guardrail" data-kind="guardrail">
          <div class="event-top">
            <span><strong>GUARDRAIL</strong>: ${esc(g.guard_type)} / ${esc(g.violation_category)} (${g.action_blocked ? "BLOCKED" : "WARNED"})</span>
            <span>${esc(g.file_path || "")}</span>
          </div>
          ${renderCollapsibleCode("Feedback", g.feedback_message)}
          ${g.diff_snippet ? renderCollapsibleCode("Diff Snippet", g.diff_snippet) : ""}
        </div>
      `;
    }
    if (ev.kind === "compilation") {
      const ce = ev.compilation;
      return `
        <div class="event-item compilation" data-kind="compilation">
          <div class="event-top">
            <span><strong>COMPILATION</strong>: exit=${esc(ce.exit_code)}</span>
            <span>${esc(fmtTs(ce.created_at))}</span>
          </div>
          ${ce.diagnostics_text ? renderCollapsibleCode("Diagnostics", ce.diagnostics_text) : ""}
        </div>
      `;
    }

    const isToolEvent = ev.kind === "tool_call" || ev.kind === "tool_result" || ev.paired;
    let body = "";
    const thinkingText = ev.thinking || ((ev.kind === "thinking" || ev.kind === "reasoning") ? ev.content : null);
    if (thinkingText) {
      body += renderCollapsibleCode("Thinking", thinkingText, false);
    }
    if (ev.arguments !== undefined && ev.arguments !== null) {
      body += isToolEvent
        ? renderToolArguments(ev.toolName, ev.arguments)
        : renderCollapsibleCode("Call", stringifyToolValue(ev.arguments));
    }
    if (ev.result !== undefined && ev.result !== null && ev.result !== "") {
      if (isToolEvent) {
        const summary = toolResultSummary(ev.result);
        if (summary) body += `<div class="tool-result-summary">${esc(summary)}</div>`;
        body += renderToolResponse(ev.result);
      } else {
        body += renderCollapsibleCode("Result", stringifyToolValue(ev.result));
      }
    }
    if (!body && ev.content && ev.content !== ev.toolName) {
      body += renderCollapsibleCode("Output", ev.content);
    }

    const toolPath = isToolEvent ? toolArgumentPath(ev.arguments) : "";
    const kindLabel = isToolEvent
      ? (ev.paired ? "Tool call + response" : ev.kind === "tool_result" ? "Tool response" : "Tool call")
      : esc(ev.kind);
    const toolPathHtml = toolPath ? `<code class="tool-path" title="${esc(toolPath)}">${esc(toolPath)}</code>` : "";
    return `
      <div class="event-item ${esc(ev.kind)} ${isToolEvent ? "tool-invocation" : ""}" data-kind="${ev.paired ? "tool_call tool_result" : esc(ev.kind)}">
        <div class="event-top">
          <span class="event-heading"><strong>${kindLabel}</strong>${ev.toolName ? ` <code>${esc(ev.toolName)}</code>` : ""}${toolPathHtml}</span>
          <span>${esc(fmtTs(ev.timestamp))}</span>
        </div>
        ${body}
      </div>
    `;
  }

  function bindTurnHeaders(container) {
    container.querySelectorAll(".turn-header").forEach((hdr) => {
      hdr.addEventListener("click", () => {
        const body = hdr.nextElementSibling;
        if (body) body.classList.toggle("hidden");
      });
    });
  }

  function bindTranscriptHandlers(container) {
    bindTurnHeaders(container);

    const expandBtn = container.querySelector("#expandAllTurnsBtn");
    const collapseBtn = container.querySelector("#collapseAllTurnsBtn");
    if (expandBtn) expandBtn.onclick = () => container.querySelectorAll(".turn-body").forEach((b) => b.classList.remove("hidden"));
    if (collapseBtn) collapseBtn.onclick = () => container.querySelectorAll(".turn-body").forEach((b) => b.classList.add("hidden"));

    const applyFilter = () => {
      const q = (container.querySelector("#transcriptSearch")?.value || "").trim().toLowerCase();
      const kind = container.querySelector("#transcriptKindFilter")?.value || "";
      container.querySelectorAll(".turn-group").forEach((tg) => {
        let anyVisible = false;
        tg.querySelectorAll(".event-item").forEach((ev) => {
          const matchKind = !kind || (ev.dataset.kind || "").split(/\s+/).includes(kind);
          const matchQ = !q || ev.textContent.toLowerCase().includes(q);
          const show = matchKind && matchQ;
          ev.classList.toggle("hidden", !show);
          if (show) anyVisible = true;
        });
        tg.classList.toggle("hidden", !anyVisible);
      });
    };

    container.querySelector("#transcriptSearch")?.addEventListener("input", applyFilter);
    container.querySelector("#transcriptKindFilter")?.addEventListener("change", applyFilter);
  }

  // ---- Stage Tab: Diagnostics & Checker ----
  function renderStageDiagnostics(step) {
    const checkerRuns = step.checker_runs || [];
    const diags = step.checker_diagnostics || [];
    const compEvents = step.compilation_events || [];
    const rawDiag = step.compiler_diagnostics;

    if (!checkerRuns.length && !diags.length && !compEvents.length && !rawDiag) {
      return `<div class="card"><div class="muted">No checker runs or compiler diagnostics recorded for this stage.</div></div>`;
    }

    let html = "";
    if (checkerRuns.length) {
      html += `
        <div class="card">
          <h4>Checker Runs (${checkerRuns.length})</h4>
          <table class="data-table">
            <thead><tr><th>Stage</th><th>Stage attempt</th><th>Processor</th><th>Invoked</th><th>Success</th><th>Errors</th><th>Warnings</th></tr></thead>
            <tbody>
              ${checkerRuns.map((cr) => `
                <tr>
                  <td>${esc(cr.stage)}</td>
                  <td>${esc(cr.attempt_number)}</td>
                  <td><code>${esc(cr.processor)}</code></td>
                  <td>${cr.checker_invoked ? "yes" : "no"}</td>
                  <td><span class="badge ${cr.success ? "badge-green" : "badge-red"}">${cr.success ? "ok" : "fail"}</span></td>
                  <td>${esc(cr.error_count)}</td>
                  <td>${esc(cr.warning_count)}</td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        </div>
      `;
    }

    if (diags.length) {
      html += `
        <div class="card">
          <h4>Checker Diagnostics (${diags.length})</h4>
          <table class="data-table">
            <thead><tr><th>Turn</th><th>Severity</th><th>Category</th><th>Location</th><th>Message</th></tr></thead>
            <tbody>
              ${diags.map((d) => `
                <tr>
                  <td title="Turn numbering restarts at 1 in every agent session">${esc(d.turn_index ?? "-")}</td>
                  <td><span class="badge ${d.severity === "error" ? "badge-red" : "badge-yellow"}">${esc(d.severity)}</span></td>
                  <td><code>${esc(d.error_category)}</code></td>
                  <td><code>${esc(d.file_path)}:${esc(d.line_number)}</code>${d.enclosing_method ? `<div class="muted">${esc(d.enclosing_method)}</div>` : ""}</td>
                  <td>${esc(d.message)}</td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        </div>
      `;
    }

    if (compEvents.length) {
      html += `
        <div class="card">
          <h4>Compilation Events (${compEvents.length})</h4>
          ${compEvents.map((ce) => `
            <div style="margin-bottom:10px;">
              <div class="muted" style="margin-bottom:4px;">Turn ${esc(ce.turn_index)} &middot; exit=${esc(ce.exit_code)}</div>
              <pre class="event-pre">${esc(ce.diagnostics_text || "(no output)")}</pre>
            </div>
          `).join("")}
        </div>
      `;
    }

    if (rawDiag) {
      html += `
        <div class="card">
          <h4>Stage Compiler Diagnostics</h4>
          <pre class="event-pre">${esc(rawDiag)}</pre>
        </div>
      `;
    }

    return `<div class="card"><p class="muted" style="font-size:11px;margin:0;">Turn indices restart at 1 in every agent session, and the DB telemetry rows below are not tagged with a session, so a turn number alone does not identify which session produced it. Session boundaries are in the Transcript tab.</p></div>${html}`;
  }

  // ---- Stage Tab: Tests ----
  function renderStageTests(step) {
    const runs = step.test_runs || [];
    const execs = step.test_executions || [];
    if (!runs.length && !execs.length) {
      return `<div class="card"><div class="muted">No test runs recorded for this stage.</div></div>`;
    }

    let html = "";
    if (runs.length) {
      html += `
        <div class="card">
          <h4>Test Runs (${runs.length})</h4>
          <table class="data-table">
            <thead><tr><th>Suite Kind</th><th>Scope</th><th>Found</th><th>Passed</th><th>Failed</th><th>Skipped</th><th>Status</th></tr></thead>
            <tbody>
              ${runs.map((tr) => `
                <tr>
                  <td>${esc(tr.suite_kind)}</td>
                  <td>${esc(tr.scope)}</td>
                  <td>${esc(tr.tests_found)}</td>
                  <td>${esc(tr.passed_count)}</td>
                  <td>${esc(tr.failed_count)}</td>
                  <td>${esc(tr.skipped_count)}</td>
                  <td><span class="badge ${tr.success ? "badge-green" : "badge-red"}">${tr.compile_failed ? "compile_failed" : (tr.success ? "passed" : "failed")}</span></td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        </div>
      `;
    }

    if (execs.length) {
      html += `
        <div class="card">
          <h4>Test Executions (${execs.length})</h4>
          <table class="data-table">
            <thead><tr><th>Type</th><th>Test</th><th>Status</th><th>Duration</th><th>Failure</th></tr></thead>
            <tbody>
              ${execs.map((te) => {
                const badgeCls = te.status === "PASSED" ? "badge-green" : (te.status === "SKIPPED" ? "badge-muted" : "badge-red");
                return `
                  <tr>
                    <td>${te.is_regression_test ? `<span class="badge badge-purple">T_reg</span>` : `<span class="badge badge-muted">unit</span>`}</td>
                    <td><code>${esc(te.test_class)}.${esc(te.test_method)}</code></td>
                    <td><span class="badge ${badgeCls}">${esc(te.status)}</span></td>
                    <td>${esc(te.duration_ms)} ms</td>
                    <td>${te.failure_message ? `<pre class="event-pre">${esc(te.failure_message)}</pre>` : "-"}</td>
                  </tr>
                `;
              }).join("")}
            </tbody>
          </table>
        </div>
      `;
    }

    return html;
  }

  // ---- Stage Tab: Guardrails ----
  function renderStageGuardrails(step) {
    const events = step.guardrail_events || [];
    if (!events.length) {
      return `<div class="card"><div class="muted">No guardrail interventions recorded for this stage.</div></div>`;
    }
    return `<div class="card"><p class="muted" style="font-size:11px;margin:0;">Turn indices restart at 1 in each agent session and guardrail rows are not session-tagged; a turn number here does not identify a session.</p></div>` + events.map((g) => `
      <div class="card">
        <h4>
          <span>${esc(g.guard_type)} &middot; ${esc(g.violation_category)}</span>
          <span class="badge ${g.action_blocked ? "badge-red" : "badge-yellow"}" title="Session-local turn number; session not recorded on this row">${g.action_blocked ? "BLOCKED" : "WARNED"} (turn ${esc(g.turn_index)})</span>
        </h4>
        ${g.file_path ? `<div class="muted" style="margin-bottom:6px;">File: <code>${esc(g.file_path)}</code></div>` : ""}
        <pre class="event-pre">${esc(g.feedback_message)}</pre>
        ${g.diff_snippet ? `<pre class="event-pre" style="margin-top:8px;">${esc(g.diff_snippet)}</pre>` : ""}
      </div>
    `).join("");
  }

  // ---- Configuration Tab ----
  function populateCompareDropdown() {
    const sel = $("configCompareSelect");
    const prev = sel.value;
    sel.innerHTML = `<option value="">None (view current run only)</option>` +
      state.runs
        .filter((r) => r.run_id !== state.selectedRunId)
        .map((r) => `<option value="${esc(r.run_id)}">${esc(r.benchmark_id)} (${esc(r.run_id.slice(0, 8))} · ${esc(outcomeSummary(r))})</option>`)
        .join("");
    sel.value = prev && state.runs.some((r) => r.run_id === prev) ? prev : "";
  }

  function flattenObject(obj, prefix = "") {
    const out = {};
    if (obj === null || obj === undefined || typeof obj !== "object" || Array.isArray(obj)) {
      out[prefix || "value"] = obj;
      return out;
    }
    for (const [k, v] of Object.entries(obj)) {
      const key = prefix ? `${prefix}.${k}` : k;
      if (v !== null && typeof v === "object" && !Array.isArray(v)) {
        Object.assign(out, flattenObject(v, key));
      } else {
        out[key] = v;
      }
    }
    return out;
  }

  function renderConfigTab() {
    const cfg = state.runDetail?.config_snapshot || {};
    const container = $("configContent");

    if (state.configRawMode) {
      container.innerHTML = `<div class="card"><pre class="event-pre" style="max-height:none;">${esc(JSON.stringify(cfg, null, 2))}</pre></div>`;
      return;
    }

    const otherCfg = state.compareRunDetail?.config_snapshot || null;
    const otherFlat = otherCfg ? flattenObject(otherCfg) : null;

    const sections = Object.keys(cfg);
    if (sections.length === 0) {
      container.innerHTML = `<div class="card"><div class="muted">No config_snapshot stored for this run.</div></div>`;
      return;
    }

    container.innerHTML = sections.map((sec) => {
      const flat = flattenObject(cfg[sec], sec);
      const rows = Object.entries(flat).filter(([k, v]) => {
        if (!state.configDiffOnly || !otherFlat) return true;
        return JSON.stringify(v) !== JSON.stringify(otherFlat[k]);
      });
      if (rows.length === 0) return "";
      return `
        <div class="card">
          <h4>${esc(sec)}</h4>
          <table class="data-table">
            <thead>
              <tr>
                <th style="width:36%;">Parameter</th>
                <th>Current Run</th>
                ${otherFlat ? `<th>Compared Run</th>` : ""}
              </tr>
            </thead>
            <tbody>
              ${rows.map(([k, v]) => {
                const curStr = JSON.stringify(v);
                const othVal = otherFlat ? otherFlat[k] : undefined;
                const changed = otherFlat && curStr !== JSON.stringify(othVal);
                return `
                  <tr class="${changed ? "cfg-diff-changed" : ""}">
                    <td><code>${esc(k)}</code></td>
                    <td><code>${esc(curStr)}</code></td>
                    ${otherFlat ? `<td><code>${esc(othVal === undefined ? "(missing)" : JSON.stringify(othVal))}</code></td>` : ""}
                  </tr>
                `;
              }).join("")}
            </tbody>
          </table>
        </div>
      `;
    }).join("") || `<div class="card"><div class="muted">No configuration differences found.</div></div>`;
  }

  // ---- Run Log Tab ----
  async function loadAndRenderLog() {
    const r = state.runDetail;
    if (!r) return;
    if (!r.log_available) {
      $("logContent").textContent = "No per-run log file found on disk for this run.";
      return;
    }
    if (state.logText === null) {
      const data = await apiGet(`/api/runs/${encodeURIComponent(r.run_id)}/log`);
      state.logText = data.content || "";
    }
    renderLogLines();
  }

  function renderLogLines() {
    const text = state.logText || "";
    const q = $("logSearchInput").value.trim().toLowerCase();
    const lvl = $("logLevelFilter").value;
    const lines = text.split(/\r?\n/).filter((line) => {
      if (lvl && !line.includes(` ${lvl} `) && !line.includes(`[${lvl}]`)) return false;
      if (q && !line.toLowerCase().includes(q)) return false;
      return true;
    });

    $("logContent").innerHTML = lines.map((line) => {
      let cls = "";
      if (line.includes(" ERROR ")) cls = "log-line-error";
      else if (line.includes(" WARN ")) cls = "log-line-warn";
      return `<div class="${cls}">${esc(line)}</div>`;
    }).join("");
  }

  // ---- Live duration clock ----
  // Running stages render [data-live-start] placeholders. One timer updates
  // them in place, so counters advance without a re-render (which would reset
  // scroll position and focus).
  const LIVE_CLOCK_MS = 1000;

  function tickLiveDurations() {
    const now = Date.now();
    document.querySelectorAll("[data-live-start]").forEach((el) => {
      el.textContent = fmtElapsed(el.dataset.liveStart, now);
    });
  }

  function startLiveClock() {
    tickLiveDurations();
    setInterval(tickLiveDurations, LIVE_CLOCK_MS);
  }

  // ---- Auto-refresh ----
  const AUTO_REFRESH_KEY = "viewer.autoRefresh";

  function readAutoRefreshPreference() {
    try {
      return localStorage.getItem(AUTO_REFRESH_KEY) === "1";
    } catch {
      return false; // storage disabled (private mode, file://, etc.)
    }
  }

  function persistAutoRefreshPreference(enabled) {
    try {
      localStorage.setItem(AUTO_REFRESH_KEY, enabled ? "1" : "0");
    } catch {
      // Non-fatal: the toggle still works for this page's lifetime.
    }
  }

  function startAutoRefresh() {
    if (state.autoRefreshTimer) clearInterval(state.autoRefreshTimer);
    state.autoRefreshTimer = setInterval(async () => {
      try {
        const health = await apiGet("/api/health");
        // live_runs changes when a pipeline process dies without writing
        // to the DB (e.g. kill -9), which db_mtime alone would miss.
        const fingerprint = `${health.db_mtime}|${(health.live_runs || []).join(",")}`;
        if (health.db_mtime && fingerprint !== state.lastDbMtime) {
          state.lastDbMtime = fingerprint;
          await loadRuns(true);
        }
      } catch {
        // Ignore transient poll errors
      }
    }, 5000);
  }

  function stopAutoRefresh() {
    if (state.autoRefreshTimer) {
      clearInterval(state.autoRefreshTimer);
      state.autoRefreshTimer = null;
    }
  }

  function setupAutoRefresh() {
    const toggle = $("autoRefreshToggle");

    // Restore the preference before wiring the listener: the checkbox starts
    // unchecked in the markup, so a plain reload used to clear Live mode.
    state.autoRefreshEnabled = readAutoRefreshPreference();
    toggle.checked = state.autoRefreshEnabled;
    toggle.classList.toggle("active", state.autoRefreshEnabled);
    if (state.autoRefreshEnabled) startAutoRefresh();

    toggle.addEventListener("change", () => {
      state.autoRefreshEnabled = toggle.checked;
      toggle.classList.toggle("active", state.autoRefreshEnabled);
      persistAutoRefreshPreference(state.autoRefreshEnabled);
      if (state.autoRefreshEnabled) startAutoRefresh();
      else stopAutoRefresh();
    });
  }

  /** Focus the selected run in the left bar (or its batch card when collapsed). */
  function focusSelectedRunCard() {
    const list = $("runsList");
    const cards = [...list.querySelectorAll(".run-card")];
    let target = cards.find((el) => el.dataset.runId === state.selectedRunId);
    if (!target && state.selectedRunId) {
      const batchId = state.runs.find((r) => r.run_id === state.selectedRunId)?.batch_id;
      if (batchId) {
        target = [...list.querySelectorAll(".batch-card")].find((el) => el.dataset.batchId === batchId);
      }
    }
    if (!target && state.highlightedBatchId) {
      target = [...list.querySelectorAll(".batch-card")].find((el) => el.dataset.batchId === state.highlightedBatchId);
    }
    target ||= list.querySelector(".batch-card, .run-card");
    if (target?.classList.contains("run-card")) {
      state.highlightedBatchId = null;
      list.querySelectorAll(".batch-card.active").forEach((el) => el.classList.remove("active"));
      target.classList.add("active");
    }
    target?.focus();
    target?.scrollIntoView({ block: "nearest" });
  }

  function handleKeyDown(e) {
    if (e.key === "Escape") {
      if (!$("artifactModal").classList.contains("hidden")) {
        $("artifactModal").classList.add("hidden");
        return;
      }
      if (!$("placementDiffModal").classList.contains("hidden")) {
        $("placementDiffModal").classList.add("hidden");
        return;
      }
    }

    const hasModifiers = e.metaKey || e.ctrlKey || e.altKey;
    const target = e.target instanceof Element ? e.target : null;
    const inRunsList = Boolean(target?.closest("#runsList"));
    const inStepper = Boolean(target?.closest("#stageStepper"));
    const phasesAvailable = state.activeRunTab === "stages" && Boolean(state.runDetail);
    const phasesVisible = phasesAvailable && state.viewMode === "inspector";

    // Tab toggles focus between the sidebar run list and the phase stepper.
    if (e.key === "Tab" && !hasModifiers && phasesVisible && (inRunsList || inStepper)) {
      e.preventDefault();
      e.stopPropagation();
      if (inRunsList) focusSelectedStepCard();
      else focusSelectedRunCard();
      return;
    }

    // With a phase focused, arrow keys select the previous/next phase.
    const isArrow = ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key);
    if (isArrow && !hasModifiers && phasesVisible && inStepper) {
      const names = executableStepNames();
      if (names.length > 1) {
        e.preventDefault();
        e.stopPropagation();
        const idx = names.indexOf(state.selectedStepName);
        const goesBackward = e.key === "ArrowLeft" || e.key === "ArrowUp";
        const nextIdx = goesBackward
          ? Math.max(0, idx === -1 ? 0 : idx - 1)
          : Math.min(names.length - 1, idx + 1);
        selectStepByName(names[nextIdx], true);
      }
      return;
    }

    // Keep Ctrl+Shift+Arrow as a global fallback for changing phases.
    const stageArrowFallback = e.ctrlKey && e.shiftKey && !e.metaKey && !e.altKey && (e.key === "ArrowLeft" || e.key === "ArrowRight");
    if (stageArrowFallback && phasesAvailable) {
      const names = executableStepNames();
      if (names.length) {
        e.preventDefault();
        e.stopPropagation();
        const idx = names.indexOf(state.selectedStepName);
        const nextIdx = e.key === "ArrowLeft"
          ? Math.max(0, idx === -1 ? 0 : idx - 1)
          : Math.min(names.length - 1, idx + 1);
        selectStepByName(names[nextIdx], inStepper);
      }
      return;
    }

    const tag = (e.target?.tagName || "").toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea" || e.metaKey || e.ctrlKey || e.altKey) {
      return;
    }
    // Arrow keys on filter controls should not navigate the run list.
    if (e.target instanceof Element && e.target.closest(".sidebar-filters, [data-facet][data-facet-value]")) {
      return;
    }
    // Keep arrow keys available for scrolling inside the matrix view.
    if (state.viewMode === "matrix" && target?.closest("#matrixView")) {
      return;
    }

    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      const cards = [...$("runsList").querySelectorAll(".batch-card, .run-card")];
      if (!cards.length) return;
      e.preventDefault();
      const currentCard = e.target instanceof Element ? e.target.closest(".batch-card, .run-card") : null;
      let idx = cards.indexOf(currentCard);
      if (idx === -1 && state.highlightedBatchId) {
        idx = cards.findIndex((card) => card.dataset.batchId === state.highlightedBatchId);
      }
      if (idx === -1 && state.selectedRunId) {
        idx = cards.findIndex((card) => card.dataset.runId === state.selectedRunId);
        if (idx === -1) {
          const selectedRun = state.runs.find((run) => run.run_id === state.selectedRunId);
          if (selectedRun?.batch_id) {
            idx = cards.findIndex((card) => card.dataset.batchId === selectedRun.batch_id);
          }
        }
      }
      const nextIdx = e.key === "ArrowDown"
        ? Math.min(cards.length - 1, idx + 1)
        : Math.max(0, idx === -1 ? 0 : idx - 1);
      const nextCard = cards[nextIdx];
      if (nextCard?.dataset.batchId) {
        state.highlightedBatchId = nextCard.dataset.batchId;
        renderRunsList();
        const target = [...$("runsList").querySelectorAll(".batch-card")]
          .find((card) => card.dataset.batchId === state.highlightedBatchId);
        target?.focus();
        target?.scrollIntoView({ block: "nearest" });
      } else if (nextCard?.dataset.runId) {
        const runId = nextCard.dataset.runId;
        const needsRender = Boolean(state.highlightedBatchId);
        if (runId !== state.selectedRunId) {
          selectRun(runId, false).then(() => {
            [...$("runsList").querySelectorAll(".run-card")]
              .find((card) => card.dataset.runId === runId)
              ?.scrollIntoView({ block: "nearest" });
          });
        } else if (needsRender) {
          state.highlightedBatchId = null;
          renderRunsList();
        }
        const target = [...$("runsList").querySelectorAll(".run-card")]
          .find((card) => card.dataset.runId === runId);
        target?.focus();
        target?.scrollIntoView({ block: "nearest" });
      }
      return;
    }
  }

  // ---- Matrix View (Compare Across Commits) ----
  /** Show exactly one main pane for the current mode and selection. */
  function syncViewVisibility() {
    const matrix = state.viewMode === "matrix";
    const inspector = !matrix && Boolean(state.runDetail);
    $("matrixView").classList.toggle("hidden", !matrix);
    $("runView").classList.toggle("hidden", !inspector);
    $("emptyState").classList.toggle("hidden", matrix || inspector);
    $("viewModeInspector").classList.toggle("active", !matrix);
    $("viewModeMatrix").classList.toggle("active", matrix);
  }

  function setViewMode(mode) {
    state.viewMode = mode;
    syncViewVisibility();
    if (mode === "matrix") renderMatrixView();
  }

  function latestStep(run, stageName) {
    const matches = (run.steps || []).filter((s) => s.step_name === stageName);
    return matches.length ? matches[matches.length - 1] : null;
  }

  async function jumpToRunStage(runId, stageName) {
    setViewMode("inspector");
    state.activeRunTab = "stages";
    state.activeStageTab = "overview";
    await selectRun(runId, true);
    if (stageName && state.runDetail) {
      const matching = (state.runDetail.steps || []).filter((s) => s.step_name === stageName);
      const latest = matching[matching.length - 1];
      if (latest) {
        state.selectedStepName = stageName;
        state.selectedStepId = latest.step_id;
        renderStageStepper();
        renderStageInspector();
      }
    }
  }

  function renderMatrixCell(run, stageName) {
    const st = latestStep(run, stageName);
    if (!st) return `<td class="matrix-cell"><span class="muted">-</span></td>`;
    const stageStatus = stageState(run, st);
    const cls = { ok: "badge-green", fail: "badge-red", running: "badge-yellow", skip: "badge-muted" }[stageStatus];
    const label = stageStatus;
    const cached = st.cache_hit ? " (C)" : "";
    const tok = st.total_tokens ? ` · ${fmtNum(st.total_tokens)}t` : "";
    const timing = isStepRunning(st) ? ` · ${liveDurationHtml(st.started_at)}` : "";
    return `
      <td class="matrix-cell">
        <button class="matrix-cell-btn" data-run-id="${esc(run.run_id)}" data-stage="${esc(stageName)}" title="Inspect ${esc(stageName)} for ${esc(run.benchmark_id)}">
          <span class="badge ${cls}">${label}${cached}</span>${tok || timing ? `<span class="muted" style="font-size:11px;">${esc(tok)}${timing}</span>` : ""}
        </button>
      </td>
    `;
  }

  // ---- Matrix "Hide triage skips" preference ----
  const HIDE_TRIAGE_SKIPS_KEY = "viewer.hideTriageSkips";

  function readHideTriageSkipsPreference() {
    try {
      return localStorage.getItem(HIDE_TRIAGE_SKIPS_KEY) === "1";
    } catch {
      return false; // storage disabled (private mode, file://, etc.)
    }
  }

  function persistHideTriageSkipsPreference(hide) {
    try {
      localStorage.setItem(HIDE_TRIAGE_SKIPS_KEY, hide ? "1" : "0");
    } catch {
      // Non-fatal: the checkbox still works for this page's lifetime.
    }
  }

  function renderMatrixView() {
    const el = $("matrixView");
    let runs = getFilteredRuns();
    const batchId = state.filters.facets.batch?.values().next().value || null;
    if (batchId) {
      // A batch is one crawl; the matrix must never mix in runs from other
      // batches (including legacy runs that carry no batch_id at all).
      runs = runs.filter((r) => r.batch_id === batchId);
    }
    if (state.hideDiscardedInMatrix) {
      runs = runs.filter((r) => !isTriageSkipped(r));
    }
    const stage = state.matrixStage;

    const stagePills = [
      `<button class="stage-pill ${stage === null ? "active" : ""}" data-matrix-stage="">All Stages</button>`,
      ...STAGE_ORDER.map((s) => `<button class="stage-pill ${stage === s ? "active" : ""}" data-matrix-stage="${esc(s)}">${esc(s)}</button>`),
    ].join("");

    let tableHtml = "";
    if (runs.length === 0) {
      tableHtml = `<div class="muted" style="padding:16px;">No runs match the current sidebar filters.</div>`;
    } else if (stage === null) {
      tableHtml = `
        <table class="data-table matrix-table">
          <thead>
            <tr>
              <th>Commit / Benchmark</th>
              <th>Triage</th>
              ${STAGE_ORDER.map((s) => `<th><button class="matrix-col-btn" data-matrix-stage="${esc(s)}" title="Focus on ${esc(s)} across all commits">${esc(s)}</button></th>`).join("")}
              <th>Outcome</th>
              <th title="Orchestrator retries of a whole stage, not agent-session retries">Stage retries</th>
              <th>Tokens</th>
            </tr>
          </thead>
          <tbody>
            ${runs.map((r) => {
              const mode = r.key_config?.triage_mode || "manifest";
              const outcome = deriveOutcome(r);
              const tokens = r.llm_stats?.total_tokens ? fmtNum(r.llm_stats.total_tokens) : "0";
              return `
                <tr>
                  <td>
                    <button class="matrix-row-btn" data-run-id="${esc(r.run_id)}">
                      <strong>${esc(r.benchmark_id)}</strong>
                      <span class="muted" style="font-size:11px;">${esc((r.commit_hash || "").slice(0, 8))}</span>
                    </button>
                  </td>
                  <td>${facetBadge("triageMode", mode, "badge-muted", mode, `Triage mode: ${mode}`)}</td>
                  ${STAGE_ORDER.map((s) => renderMatrixCell(r, s)).join("")}
                  <td>
                    ${facetBadge("outcome", outcome.key, outcome.cls, outcome.label, outcome.tooltip)}
                    ${outcome.reasonLabel ? `<div class="muted" style="font-size:11px;margin-top:2px;">${outcomeReasonControl(r, outcome)}</div>` : ""}
                  </td>
                  <td>${r.stage_retries ? `<span class="badge badge-yellow" title="${r.stage_retries} orchestrator stage retry/retries; ${r.final_stage_failures} final stage failure(s)">${esc(r.stage_retries)}</span>` : `<span class="muted">0</span>`}</td>
                  <td>${esc(tokens)}</td>
                </tr>
              `;
            }).join("")}
          </tbody>
        </table>
      `;
    } else if (stage === "triage") {
      tableHtml = `
        <table class="data-table matrix-table">
          <thead>
            <tr>
              <th>Commit / Benchmark</th>
              <th>Mode</th>
              <th>Triage Status</th>
              <th>Classification</th>
              <th>Score</th>
              <th>Discard Reason</th>
              <th>Regression Test</th>
              <th>Cache</th>
            </tr>
          </thead>
          <tbody>
            ${runs.map((r) => {
              const st = latestStep(r, "triage");
              const mode = r.key_config?.triage_mode || "manifest";
              const triageStatus = effectiveTriageStatus(r);
              const triageMeta = triageStatusMeta(triageStatus);
              const classification = r.classification || "";
              const regTest = r.regression_test_class
                ? `${r.regression_test_class.split(".").pop()}.${r.regression_test_method || ""}`
                : "-";
              return `
                <tr class="matrix-focus-row" data-run-id="${esc(r.run_id)}" data-stage="triage">
                  <td><strong>${esc(r.benchmark_id)}</strong> <span class="muted" style="font-size:11px;">${esc((r.commit_hash || "").slice(0, 8))}</span></td>
                  <td>${facetBadge("triageMode", mode, "badge-muted", mode, `Triage mode: ${mode}`)}</td>
                  <td>${facetBadge("triageStatus", triageStatus, triageMeta.cls, triageMeta.label, triageMeta.tooltip)}</td>
                  <td>${classification ? facetControl("classification", classification, { plain: true, inner: `<code>${esc(classification)}</code>`, label: classification, tooltip: "LLM triage classification" }) : `<span class="muted">-</span>`}</td>
                  <td>${r.bugfix_score !== null && r.bugfix_score !== undefined ? `<code>${esc(r.bugfix_score)}</code>` : "-"}</td>
                  <td><code>${esc(r.discard_reason || "-")}</code></td>
                  <td><code>${esc(regTest)}</code></td>
                  <td>${st ? (st.cache_hit ? '<span class="badge badge-muted">hit</span>' : "miss") : "-"}</td>
                </tr>
              `;
            }).join("")}
          </tbody>
        </table>
      `;
    } else {
      tableHtml = `
        <table class="data-table matrix-table">
          <thead>
            <tr>
              <th>Commit / Benchmark</th>
              <th>Status</th>
              <th>Cache</th>
              <th>Turns</th>
              <th>Tokens</th>
              <th>Checker Runs</th>
              <th>Diagnostics</th>
              <th>Test Runs</th>
              <th>Outcome / Notes</th>
            </tr>
          </thead>
          <tbody>
            ${runs.map((r) => {
              const st = latestStep(r, stage);
              const outcome = deriveOutcome(r);
              const statusBadge = !st
                ? `<span class="muted">not run</span>`
                : `<span class="badge ${{ ok: "badge-green", fail: "badge-red", running: "badge-yellow", skip: "badge-muted" }[stageState(r, st)]}">${stageState(r, st)}</span>`;
              const note = st?.compiler_diagnostics ? st.compiler_diagnostics.slice(0, 80) : [outcome.label, outcome.reasonLabel].filter(Boolean).join(" · ");
              return `
                <tr class="matrix-focus-row" data-run-id="${esc(r.run_id)}" data-stage="${esc(stage)}">
                  <td><strong>${esc(r.benchmark_id)}</strong> <span class="muted" style="font-size:11px;">${esc((r.commit_hash || "").slice(0, 8))}</span></td>
                  <td>${statusBadge}</td>
                  <td>${st ? (st.cache_hit ? '<span class="badge badge-muted">hit</span>' : "miss") : "-"}</td>
                  <td>${st?.turns_taken ?? "-"}</td>
                  <td>${st?.total_tokens !== null && st?.total_tokens !== undefined ? fmtNum(st.total_tokens) : "-"}</td>
                  <td>${st?.checker_invocations ?? "-"}</td>
                  <td>${st?.checker_diagnostic_count ?? "-"}</td>
                  <td>${st?.test_invocations ?? "-"}</td>
                  <td class="muted" style="font-size:12px;">${esc(note)}</td>
                </tr>
              `;
            }).join("")}
          </tbody>
        </table>
      `;
    }

    const telemetry = state.telemetry || { db_operations: [], retries: [], subprocesses: [], recent_retries: [], notes: [] };
    const telemetryHtml = `<div class="grid-2 telemetry-grid">
      <div class="card"><h3>Database operation performance</h3>${telemetry.db_operations.length ? `<table class="data-table"><thead><tr><th>Operation</th><th>Calls</th><th>Failures</th><th>Avg ms</th><th>Max ms</th></tr></thead><tbody>${telemetry.db_operations.map((x) => `<tr><td>${esc(x.operation)}</td><td>${esc(fmtNum(x.calls))}</td><td>${esc(fmtNum(x.failures))}</td><td>${esc(Number(x.avg_duration_ms || 0).toFixed(2))}</td><td>${esc(Number(x.max_duration_ms || 0).toFixed(2))}</td></tr>`).join("")}</tbody></table>` : `<p class="muted">No DB operation telemetry${telemetry.notes.length ? ` · ${esc(telemetry.notes.join(" "))}` : ""}</p>`}</div>
      <div class="card"><h3>Subprocess timing</h3><p class="muted">Compare Gradle compile/test and checker time with LLM latency in run details.</p>${telemetry.subprocesses.length ? `<table class="data-table"><thead><tr><th>Stage</th><th>Kind</th><th>Calls</th><th>Failures</th><th>Timeouts</th><th>Avg ms</th><th>Max ms</th><th>Total ms</th></tr></thead><tbody>${telemetry.subprocesses.map((x) => `<tr><td>${esc(x.stage)}</td><td>${esc(x.kind)}</td><td>${esc(fmtNum(x.calls))}</td><td>${esc(fmtNum(x.failures))}</td><td>${esc(fmtNum(x.timeouts))}</td><td>${esc(Number(x.avg_duration_ms || 0).toFixed(2))}</td><td>${esc(fmtNum(x.max_duration_ms))}</td><td>${esc(fmtNum(x.total_duration_ms))}</td></tr>`).join("")}</tbody></table>` : `<p class="muted">No subprocess timing telemetry${telemetry.notes.length ? ` · ${esc(telemetry.notes.join(" "))}` : ""}</p>`}</div>
      <div class="card">
        <h3>Stage retry telemetry</h3>
        <p class="muted" style="font-size:11px;">Orchestrator retries of whole stages, from <code>retry_events</code>. Agent-session feedback retries are shown per stage in the Transcript tab and are not part of this table.</p>
        ${telemetry.retries.length ? `<table class="data-table"><thead><tr><th>Stage</th><th>Error class</th><th title="Number of failed stage attempts recorded in retry_events">Failed stage attempts</th><th title="Failed stage attempts that were followed by another attempt">Stage retries</th><th title="Failed stage attempts that were not retried">Final stage failures</th></tr></thead><tbody>${telemetry.retries.map((x) => `<tr><td>${esc(x.stage)}</td><td>${esc(x.error_class)}</td><td>${esc(fmtNum(x.attempts))}</td><td>${esc(fmtNum(x.retries))}</td><td>${esc(fmtNum(x.attempts - x.retries))}</td></tr>`).join("")}</tbody></table>` : `<p class="muted">No stage retry telemetry${telemetry.notes.length ? ` · ${esc(telemetry.notes.join(" "))}` : ""}</p>`}
        ${telemetry.recent_retries.length ? `<h4>Recent stage retry events</h4><table class="data-table"><thead><tr><th>Stage</th><th>Error class</th><th>Stage attempt</th><th>Disposition</th><th>Delay ms</th><th>Last message</th></tr></thead><tbody>${telemetry.recent_retries.map((x) => `<tr><td>${esc(x.stage)}</td><td>${esc(x.error_class)}</td><td>${esc(x.attempt_number)}</td><td>${x.will_retry ? "stage retry" : "final stage failure"}</td><td>${esc(fmtNum(x.delay_ms))}</td><td>${esc(x.error_message)}</td></tr>`).join("")}</tbody></table>` : ""}
      </div>
    </div>`;
    el.innerHTML = `
      <div class="matrix-toolbar">
        <div>
          <h2 style="margin:0 0 4px 0;">Stage Matrix <span class="muted" style="font-size:13px;font-weight:400;">(${runs.length} commit${runs.length === 1 ? "" : "s"})</span>${batchId ? `<span class="badge badge-muted" style="margin-left:8px;">Batch: <code>${esc(batchId.slice(0, 8))}</code></span>` : ""}</h2>
          <div class="muted" style="font-size:12px;">Click a stage column or pill to compare one stage across all commits; click any row or cell to inspect that commit. “Stage retries” are whole-stage orchestrator retries; agent session retries are shown per stage in the Transcript tab.</div>
        </div>
        <div class="matrix-stage-pills">
          <label class="checkbox-inline" style="margin-right:8px;font-size:12px;">
            <input type="checkbox" id="matrixHideDiscarded" ${state.hideDiscardedInMatrix ? "checked" : ""} />
            <span>Hide triage skips</span>
          </label>
          ${stagePills}
        </div>
      </div>
      <div class="card" style="overflow-x:auto;">${tableHtml}</div>
      ${telemetryHtml}
    `;

    const hideDiscardedEl = $("matrixHideDiscarded");
    if (hideDiscardedEl) {
      hideDiscardedEl.addEventListener("change", (e) => {
        state.hideDiscardedInMatrix = e.target.checked;
        persistHideTriageSkipsPreference(state.hideDiscardedInMatrix);
        renderMatrixView();
      });
    }

    el.querySelectorAll("[data-matrix-stage]").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.matrixStage = btn.dataset.matrixStage || null;
        renderMatrixView();
      });
    });
    el.querySelectorAll(".matrix-cell-btn, .matrix-focus-row").forEach((btn) => {
      btn.addEventListener("click", () => jumpToRunStage(btn.dataset.runId, btn.dataset.stage));
    });
    el.querySelectorAll(".matrix-row-btn").forEach((btn) => {
      btn.addEventListener("click", () => jumpToRunStage(btn.dataset.runId, null));
    });
  }

  // ---- Event Wiring ----
  function init() {
    window.addEventListener("keydown", handleKeyDown, true);
    $("refreshBtn").addEventListener("click", () => loadRuns(true));

    initFilterControls();
    restoreFiltersFromUrl(readUrlState());
    $("runSearch").addEventListener("input", () => applyFilters());
    $("filterFacets").addEventListener("change", handleFacetSelectChange);
    $("activeFilters").addEventListener("click", handleActiveFilterClick);
    $("clearFiltersBtn").addEventListener("click", clearAllFilters);
    $("showAllRunsBtn").addEventListener("click", clearAllFilters);
    $("sortSelect").addEventListener("change", (e) => {
      state.filters.sort = e.target.value;
      renderRunsList();
      saveUrlState();
    });

    window.addEventListener("hashchange", () => {
      const saved = readUrlState();
      if (
        normalizeFacetState(saved.filters) === normalizeFacetState(serializeFacets()) &&
        (saved.sort || "newest") === state.filters.sort
      ) {
        return;
      }
      restoreFiltersFromUrl(saved);
      applyFilters();
    });

    // Any badge/control carrying a facet value toggles that filter. Capture
    // phase stops the click from also navigating (e.g. selecting a run card).
    document.addEventListener(
      "click",
      (event) => {
        const target = event.target.closest("[data-facet][data-facet-value]");
        if (!target) return;
        event.preventDefault();
        event.stopPropagation();
        const key = target.dataset.facet;
        const value = target.dataset.facetValue;
        const keyboardActivation = event.detail === 0;
        toggleFacet(key, value);
        if (keyboardActivation) restoreFacetFocus(key, value);
      },
      true
    );

    $("viewModeInspector").addEventListener("click", () => setViewMode("inspector"));
    $("viewModeMatrix").addEventListener("click", () => setViewMode("matrix"));

    document.querySelectorAll(".run-tab").forEach((btn) => {
      btn.addEventListener("click", () => switchRunTab(btn.dataset.runTab));
    });

    document.querySelectorAll(".stage-tab").forEach((btn) => {
      btn.addEventListener("click", () => switchStageTab(btn.dataset.stageTab));
    });

    $("attemptSelector").addEventListener("change", (e) => {
      state.selectedStepId = e.target.value;
      renderStageInspector();
    });

    $("configCompareSelect").addEventListener("change", async (e) => {
      const otherId = e.target.value;
      state.compareRunDetail = otherId ? await apiGet(`/api/runs/${encodeURIComponent(otherId)}`) : null;
      renderConfigTab();
    });

    $("configDiffOnlyToggle").addEventListener("change", (e) => {
      state.configDiffOnly = e.target.checked;
      renderConfigTab();
    });

    $("configRawToggle").addEventListener("click", () => {
      state.configRawMode = !state.configRawMode;
      $("configRawToggle").textContent = state.configRawMode ? "Structured View" : "Raw JSON";
      renderConfigTab();
    });

    $("configCopyBtn").addEventListener("click", () => {
      copyText(JSON.stringify(state.runDetail?.config_snapshot || {}, null, 2), $("configCopyBtn"));
    });

    $("logSearchInput").addEventListener("input", renderLogLines);
    $("logLevelFilter").addEventListener("change", renderLogLines);
    $("logRefreshBtn").addEventListener("click", () => {
      state.logText = null;
      loadAndRenderLog();
    });
    $("logBottomBtn").addEventListener("click", () => {
      const el = $("logContent");
      el.scrollTop = el.scrollHeight;
    });

    $("artifactCloseBtn").addEventListener("click", () => $("artifactModal").classList.add("hidden"));
    $("artifactModal").querySelector(".modal-backdrop").addEventListener("click", () => $("artifactModal").classList.add("hidden"));
    $("artifactCopyBtn").addEventListener("click", () => copyText($("artifactModalBody").textContent, $("artifactCopyBtn")));

    $("placementDiffCloseBtn").addEventListener("click", () => $("placementDiffModal").classList.add("hidden"));
    $("placementDiffModal").querySelector(".modal-backdrop").addEventListener("click", () => $("placementDiffModal").classList.add("hidden"));
    $("placementDiffCopyBtn").addEventListener("click", () => copyText(state.placementDiffText || "", $("placementDiffCopyBtn")));

    // Restore persisted view preferences before any render: the matrix checkbox
    // starts unchecked in the markup, so a reload used to quietly reset it.
    state.hideDiscardedInMatrix = readHideTriageSkipsPreference();
    setupAutoRefresh();
    startLiveClock();
    apiGet("/api/telemetry").then((data) => { state.telemetry = data; if (state.viewMode === "matrix") renderMatrixView(); }).catch(() => {});
    loadRuns(false).catch((err) => {
      $("runsCount").textContent = `Error: ${err.message}`;
    });
  }

  document.addEventListener("DOMContentLoaded", init);
})();
