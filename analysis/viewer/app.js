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
    autoRefreshTimer: null,
    viewMode: "inspector",
    matrixStage: null,
    selectedBatchId: null,
    expandedBatches: new Set(),
    hideDiscardedInMatrix: false,
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

  function fmtDuration(startIso, endIso) {
    if (!startIso || !endIso) return "-";
    const ms = new Date(endIso) - new Date(startIso);
    if (Number.isNaN(ms) || ms < 0) return "-";
    if (ms < 1000) return `${ms}ms`;
    const s = Math.floor(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    const remS = s % 60;
    return `${m}m ${remS}s`;
  }

  function fmtTs(iso) {
    if (!iso) return "-";
    return String(iso).replace("T", " ").replace(/\.\d+Z?$/, "Z");
  }

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

  // A step row is written when the step starts; completed_at stays null until it finishes.
  function isStepRunning(step) {
    return !!step && !step.completed_at && !step.success;
  }

  function stepState(step) {
    if (isStepRunning(step)) return "running";
    return step.success ? "ok" : "fail";
  }

  function statusBadgeClass(status) {
    switch (status) {
      case "success": return "badge-green";
      case "failed": return "badge-red";
      case "running": return "badge-blue";
      default: return "badge-muted";
    }
  }

  const VERDICT_TOOLTIPS = {
    full_success: "Full success: 0 remaining type errors, regression test passed, and full pre-existing unit suite passed.",
    partial_success_regression_only: "Partial success: 0 remaining type errors and regression test passed (full_suite not run).",
    type_success_runtime_fail: "Type-checks with 0 errors, but the regression test failed at runtime (valid JUnit evidence).",
    fixer_incomplete: "Verification ran, but Checker Framework still reports remaining type errors.",
    suite_failure: "Checker is clean but at least one pre-existing suite test failed. No baseline comparison, so this does not claim the failure was newly introduced.",
    regression_introduced: "Legacy label for suite_failure. Checker is clean but at least one pre-existing suite test failed.",
    verification_missing: "Required verification evidence is missing or unusable (no checker result, no parseable JUnit report, or no tests found). Assertion failures with valid reports are substantive failures, not missing evidence.",
  };

  function verdictTooltip(verdict) {
    return VERDICT_TOOLTIPS[verdict] || `Evaluation verdict: ${verdict || "unknown"}`;
  }

  function statusTooltip(status) {
    switch (status) {
      case "success": return "Pipeline execution completed all stages (lifecycle). See the separate evaluation verdict for what the repaired code achieved.";
      case "failed": return "Pipeline execution stopped due to a stage failure (lifecycle). The evaluation verdict is verification_missing because verification evidence is incomplete.";
      case "running": return "Pipeline execution is currently in progress.";
      default: return `Pipeline run status: ${status || "unknown"}`;
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
    const hash = `#${params.toString()}`;
    if (window.location.hash !== hash) {
      history.replaceState(null, "", hash);
    }
  }

  function readUrlState() {
    const raw = window.location.hash.replace(/^#/, "");
    if (!raw) return {};
    const params = new URLSearchParams(raw);
    return {
      run: params.get("run") || null,
      tab: params.get("tab") || null,
      stage: params.get("stage") || null,
      stageTab: params.get("stageTab") || null,
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
      const targetRun = saved.run && state.runs.some((r) => r.run_id === saved.run)
        ? saved.run
        : state.runs[0].run_id;
      if (saved.tab) state.activeRunTab = saved.tab;
      if (saved.stage) state.selectedStepName = saved.stage;
      if (saved.stageTab) state.activeStageTab = saved.stageTab;
      await selectRun(targetRun, Boolean(saved.run && targetRun === saved.run && (saved.stage || saved.tab || saved.stageTab)));
    }
  }

  function populateFilterOptions() {
    const verdicts = [...new Set(state.runs.map((r) => r.verdict || "unknown"))].sort();
    const guardrails = [...new Set(state.runs.map((r) => r.guardrails_mode || "unknown"))].sort();
    const triageModes = [...new Set(state.runs.map((r) => r.key_config?.triage_mode || "manifest"))].sort();

    const vf = $("verdictFilter");
    const prevV = vf.value;
    vf.innerHTML = `<option value="">All verdicts</option>` + verdicts.map((v) => `<option value="${esc(v)}">${esc(v)}</option>`).join("");
    vf.value = verdicts.includes(prevV) ? prevV : "";

    const gf = $("guardrailFilter");
    const prevG = gf.value;
    gf.innerHTML = `<option value="">All guardrails</option>` + guardrails.map((g) => `<option value="${esc(g)}">${esc(g)}</option>`).join("");
    gf.value = guardrails.includes(prevG) ? prevG : "";

    const tf = $("triageModeFilter");
    const prevT = tf.value;
    tf.innerHTML = `<option value="">All triage</option>` + triageModes.map((t) => `<option value="${esc(t)}">${esc(t)}</option>`).join("");
    tf.value = triageModes.includes(prevT) ? prevT : "";
  }

  function getFilteredRuns() {
    const q = $("runSearch").value.trim().toLowerCase();
    const vf = $("verdictFilter").value;
    const gf = $("guardrailFilter").value;
    const tf = $("triageModeFilter").value;

    return state.runs.filter((r) => {
      if (vf && (r.verdict || "unknown") !== vf) return false;
      if (gf && (r.guardrails_mode || "unknown") !== gf) return false;
      if (tf && (r.key_config?.triage_mode || "manifest") !== tf) return false;
      if (q) {
        const hay = [
          r.run_id,
          r.benchmark_id,
          r.commit_hash,
          r.pr_title,
          r.pr_number ? `pr#${r.pr_number}` : "",
          r.key_config?.model_default,
          r.triage_status,
          r.discard_reason,
        ].filter(Boolean).join(" ").toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  function renderRunCardHtml(r, isChild = false) {
    const active = r.run_id === state.selectedRunId ? "active" : "";
    const verdict = r.verdict || r.status;
    const dots = STAGE_ORDER.map((stName) => {
      const matches = (r.steps || []).filter((s) => s.step_name === stName);
      const latest = matches[matches.length - 1];
      if (!latest) return `<span class="stage-mini-dot" title="${esc(stName)}: not run"></span>`;
      const cls = stepState(latest);
      const cached = latest.cache_hit ? " cached" : "";
      const stLabel = { ok: "ok", fail: "failed", running: "running" }[cls];
      return `<span class="stage-mini-dot ${cls}${cached}" title="${esc(stName)}: ${stLabel}${latest.cache_hit ? " (cached)" : ""}"></span>`;
    }).join("");

    const triageMode = r.key_config?.triage_mode || "manifest";
    const tokens = r.llm_stats?.total_tokens ? `${fmtNum(r.llm_stats.total_tokens)} tok` : "0 tok";
    return `
      <div class="run-card ${isChild ? "batch-child" : ""} ${active}" data-run-id="${esc(r.run_id)}">
        <div class="run-card-top">
          <span class="run-card-bench" title="${esc(r.benchmark_id)}">${esc(r.benchmark_id)}</span>
          <span class="badge ${verdictBadgeClass(r.verdict)}" title="${esc(verdictTooltip(r.verdict))}">${esc(verdict)}</span>
        </div>
        <div class="run-card-id" title="Started: ${esc(fmtTs(r.started_at))}">${esc(r.run_id.slice(0, 8))} &middot; ${esc(fmtTs(r.started_at))}</div>
        <div class="run-card-meta">
          <span class="badge badge-muted" title="Triage mode: ${esc(triageMode)}">${esc(triageMode)}</span>
          <span class="badge badge-muted" title="Guardrails mode: ${esc(r.guardrails_mode)}">${esc(r.guardrails_mode)}</span>
          <span class="badge badge-muted" title="Verification scope: ${esc(r.verification_scope)}">${esc(r.verification_scope)}</span>
          <span class="muted" style="font-size:11px;" title="Total LLM tokens across all stages">${esc(tokens)}</span>
        </div>
        <div class="run-card-dots">${dots}</div>
      </div>
    `;
  }

  function renderRunsList() {
    const filtered = getFilteredRuns();
    $("runsCount").textContent = `${filtered.length} of ${state.runs.length} runs`;
    if (state.viewMode === "matrix") renderMatrixView();
    const listEl = $("runsList");

    if (filtered.length === 0) {
      listEl.innerHTML = `<div class="muted" style="padding:12px;">No matching runs.</div>`;
      return;
    }

    const searchActive = Boolean($("runSearch").value.trim());
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
      const passCount = runs.filter((r) => r.verdict === "PASS").length;
      const failCount = runs.filter((r) => r.verdict && r.verdict !== "PASS").length;
      const discardedCount = runs.filter((r) => r.triage_status === "discarded" || (r.discard_reason && r.discard_reason !== "")).length;
      const runningCount = runs.filter((r) => r.status === "running").length;
      const hasSelected = runs.some((r) => r.run_id === state.selectedRunId);
      const isExpanded = searchActive || hasSelected || state.expandedBatches.has(item.batchId);
      const isActiveBatch = state.viewMode === "matrix" && state.selectedBatchId === item.batchId;

      const summaryPills = [
        passCount ? `<span class="badge badge-pass">${passCount} pass</span>` : "",
        failCount ? `<span class="badge badge-fail">${failCount} fail</span>` : "",
        discardedCount ? `<span class="badge badge-muted">${discardedCount} discarded</span>` : "",
        runningCount ? `<span class="badge badge-running">${runningCount} running</span>` : "",
      ].filter(Boolean).join("");

      const childrenHtml = isExpanded
        ? `<div class="batch-children">${runs.map((r) => renderRunCardHtml(r, true)).join("")}</div>`
        : "";

      return `
        <div class="batch-group">
          <div class="batch-card ${isActiveBatch ? "active" : ""}" data-batch-id="${esc(item.batchId)}">
            <div class="run-card-top">
              <button class="batch-toggle-btn" data-toggle-batch="${esc(item.batchId)}" title="${isExpanded ? "Collapse" : "Expand"} commits">${isExpanded ? "&#x25BE;" : "&#x25B8;"}</button>
              <span class="run-card-bench" title="Crawl batch ${esc(item.batchId)}">${esc(repoLabel)}</span>
              <span class="badge badge-muted">${runs.length} commits</span>
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

    listEl.querySelectorAll(".batch-toggle-btn").forEach((btn) => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        const bid = btn.dataset.toggleBatch;
        if (state.expandedBatches.has(bid)) {
          state.expandedBatches.delete(bid);
        } else {
          state.expandedBatches.add(bid);
        }
        renderRunsList();
      });
    });

    listEl.querySelectorAll(".batch-card").forEach((el) => {
      el.addEventListener("click", () => {
        state.selectedBatchId = el.dataset.batchId;
        setViewMode("matrix");
        renderRunsList();
      });
    });

    listEl.querySelectorAll(".run-card").forEach((el) => {
      el.addEventListener("click", () => {
        if (state.viewMode !== "inspector") setViewMode("inspector");
        selectRun(el.dataset.runId, false);
      });
    });
  }

  // ---- Run Selection & Header ----
  async function selectRun(runId, preserveStageAndTab = false) {
    state.selectedRunId = runId;
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

    $("emptyState").classList.add("hidden");
    $("runView").classList.remove("hidden");

    renderRunHeader();
    populateCompareDropdown();
    switchRunTab(state.activeRunTab);
  }

  function renderRunHeader() {
    const r = state.runDetail;
    const kc = r.key_config || {};

    $("hdrVerdictBadge").className = `badge ${verdictBadgeClass(r.verdict)}`;
    $("hdrVerdictBadge").textContent = r.verdict || "unknown";
    $("hdrVerdictBadge").title = verdictTooltip(r.verdict);

    $("hdrStatusBadge").className = `badge ${statusBadgeClass(r.status)}`;
    $("hdrStatusBadge").textContent = `run: ${r.status}`;
    $("hdrStatusBadge").title = statusTooltip(r.status);

    $("hdrBenchmark").textContent = r.benchmark_id;
    $("hdrRunId").textContent = r.run_id;
    $("hdrRunId").onclick = () => copyText(r.run_id, $("hdrRunId"));

    const totalTokens = (r.steps || []).reduce((acc, s) => acc + (s.total_tokens || 0), 0);
    const totalLatency = (r.steps || []).reduce(
      (acc, s) => acc + (s.llm_invocations || []).reduce((a, i) => a + (i.latency_ms || 0), 0),
      0
    );

    $("hdrMetrics").innerHTML = `
      <span>Duration: <strong>${esc(fmtDuration(r.started_at, r.completed_at))}</strong></span>
      <span>Tokens: <strong>${esc(fmtNum(totalTokens))}</strong></span>
      <span>LLM Latency: <strong>${esc(fmtNum(totalLatency))} ms</strong></span>
      ${r.remaining_type_errors !== null && r.remaining_type_errors !== undefined ? `<span>Remaining Errors: <strong>${esc(r.remaining_type_errors)}</strong></span>` : ""}
    `;

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
      ["triage", kc.triage_mode || "manifest"],
      ["triage_model", kc.triage_mode === "crawler" ? kc.model_triage : null],
      ["depth", kc.triage_mode === "crawler" ? kc.triage_depth : null],
      ["limit", kc.triage_mode === "crawler" ? kc.triage_limit : null],
      ["threshold", kc.triage_mode === "crawler" ? kc.triage_bugfix_threshold : null],
      ["model", kc.model_default],
      ["guardrails", kc.guardrails_mode],
      ["scope", kc.verification_scope],
      ["freedom", kc.synthesis_freedom_level],
      ["local_vars", kc.repair_local_var_annotations],
      ["temp", kc.sample_temperature],
      ["steps", kc.agent_steps ? `${kc.agent_steps.synthesis ?? "-"}/${kc.agent_steps.application ?? "-"}/${kc.agent_steps.repair ?? "-"}` : null],
      ["timeout", kc.agent_timeout_ms ? `${Math.round(kc.agent_timeout_ms / 1000)}s` : null],
    ].filter(([, v]) => v !== null && v !== undefined && v !== "");

    $("hdrConfigPills").innerHTML = pills.map(([k, v]) => `
      <span class="cfg-pill" title="${esc(CONFIG_PILL_TOOLTIPS[k] || k)}">
        <span class="cfg-pill-k">${esc(k)}:</span>
        <span class="cfg-pill-v">${esc(v)}</span>
      </span>
    `).join("");

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
      const dotCls = stepState(latest);
      const dur = fmtDuration(latest.started_at, latest.completed_at);
      const turns = latest.turns_taken ? `${latest.turns_taken}t` : "";
      const attBadge = attempts.length > 1 ? ` (${attempts.length} att)` : "";
      return `
        <button class="step-card ${active}" data-step-name="${esc(name)}">
          <div class="step-card-top">
            <span class="step-name">${esc(name)}${esc(attBadge)}</span>
            <span class="stage-dot ${dotCls}"></span>
          </div>
          <div class="step-card-sub">
            <span>${latest.cache_hit ? "cached" : ({ ok: "passed", fail: "failed", running: "running" }[dotCls])}</span>
            <span>${esc(turns)} ${esc(dur)}</span>
          </div>
        </button>
      `;
    }).join("");

    $("stageStepper").querySelectorAll(".step-card:not(.not-run)").forEach((el) => {
      el.addEventListener("click", () => {
        const name = el.dataset.stepName;
        const matching = (state.runDetail.steps || []).filter((s) => s.step_name === name);
        const latest = matching[matching.length - 1];
        state.selectedStepName = name;
        state.selectedStepId = latest ? latest.step_id : null;
        renderStageStepper();
        renderStageInspector();
      });
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

    $("stageStatusIcon").className = `stage-dot ${stepState(step)}`;
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

    $("stageDuration").textContent = `${fmtDuration(step.started_at, step.completed_at)} (${fmtTs(step.started_at)})`;
    $("stageStats").textContent = [
      step.turns_taken ? `${step.turns_taken} turns` : null,
      step.total_tokens ? `${fmtNum(step.total_tokens)} tokens` : null,
    ].filter(Boolean).join(" · ");

    // Attempt selector
    const attempts = (state.runDetail.steps || []).filter((s) => s.step_name === step.step_name);
    const attWrap = $("attemptSelectorWrap");
    if (attempts.length > 1) {
      attWrap.classList.remove("hidden");
      const sel = $("attemptSelector");
      sel.innerHTML = attempts.map((a) => `<option value="${esc(a.step_id)}">#${a.attempt_number} (${{ ok: "ok", fail: "fail", running: "running" }[stepState(a)]})</option>`).join("");
      sel.value = step.step_id;
    } else {
      attWrap.classList.add("hidden");
    }

    // Tab counts
    const hasPatch = Boolean(step.diff_patch || step.cas_diff_patch || (step.annotation_placements || []).length);
    $("cntPatch").textContent = hasPatch ? (step.annotation_placements?.length || "1") : "";
    const turnCount = Math.max(
      (step.agent_turns || []).length,
      (step.cas_transcript || []).length,
      (step.agent_transcript || []).length
    );
    $("cntTranscript").textContent = turnCount ? String(turnCount) : "";
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

    const summaryCard = `
      <div class="card">
        <h4>Stage Metadata</h4>
        <table class="kv-table">
          <tr><th>Step ID</th><td><code>${esc(step.step_id)}</code></td></tr>
          <tr><th>Attempt</th><td>${esc(step.attempt_number)}</td></tr>
          <tr><th>Success</th><td>${isStepRunning(step) ? `<span class="badge badge-yellow">running</span>` : `<span class="badge ${step.success ? "badge-green" : "badge-red"}">${step.success ? "true" : "false"}</span>`}</td></tr>
          <tr><th>Cache Hit</th><td>${step.cache_hit ? "Yes" : "No"}</td></tr>
          ${step.cache_hit ? `<tr><th>Cache Origin</th><td>${step.cache_origin
            ? `<button class="link-btn cache-origin-link" data-origin-run="${esc(step.cache_origin.run_id)}">${esc(step.cache_origin.run_id.slice(0, 8))}</button> <span class="muted">(${esc(step.cache_origin.benchmark_id)} · ${esc(fmtTs(step.cache_origin.started_at))})</span>`
            : `<span class="muted">Not in current DB (loaded from .cache/stages)</span>`}</td></tr>` : ""}
          <tr><th>CAS Key</th><td><code>${esc(step.cas_key || "-")}</code></td></tr>
          <tr><th>Started</th><td>${esc(fmtTs(step.started_at))}</td></tr>
          <tr><th>Completed</th><td>${esc(fmtTs(step.completed_at))}</td></tr>
        </table>
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
      const triageStatus = r.triage_status || (isStepRunning(step) ? "running" : (step.success ? "verified" : "discarded"));
      const discardReason = r.discard_reason || tm.discard_reason || "-";
      const classification = r.classification || tm.llm_classification || "-";
      const score = r.bugfix_score ?? tm.bugfix_score;
      const threshold = kc.triage_bugfix_threshold;
      const regClass = tm.regression_test_class || r.regression_test_class;
      const regMethod = tm.regression_test_method || r.regression_test_method;
      const regTest = regClass ? `${regClass}${regMethod ? "." + regMethod : ""}` : "-";
      const details = tm.details || step.compiler_diagnostics || "";

      stageSpecific += `
        <div class="card">
          <h4>Triage Details (<span class="badge badge-muted">${esc(provenance)}</span>)</h4>
          <table class="data-table">
            <tbody>
              <tr><th>Triage Status</th><td><code>${esc(triageStatus)}</code></td><th>Discard Reason</th><td><code>${esc(discardReason)}</code></td></tr>
              <tr><th>Classification</th><td><code>${esc(classification)}</code></td><th>Bugfix Score</th><td>${score !== null && score !== undefined ? `<code>${esc(score)}</code>${threshold !== null && threshold !== undefined ? ` (threshold: <code>${esc(threshold)}</code>)` : ""}` : "-"}</td></tr>
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
          <h4>Placement Guide (<code>placement_guide.md</code>)</h4>
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
              ${step.annotation_placements.map((p) => `
                <tr>
                  <td><code>${esc(p.file_path)}</code></td>
                  <td>${esc(p.line_number)}</td>
                  <td><code>${esc(p.symbol)}</code></td>
                  <td><code>@${esc(p.qualifier)}</code></td>
                  <td>${esc(p.target_tier ?? "-")}</td>
                </tr>
              `).join("")}
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

  // ---- Stage Tab: Transcript & Turns ----
  function buildInvocations(step) {
    const casEvents = step.cas_transcript || [];
    const isPrompt = (ev) => ev && (ev.event_type === "initial_prompt" || ev.event_type === "prompt" || ev.event_type === "user_prompt");

    if (casEvents.length > 0) {
      const invocations = [];
      let current = { index: 1, timestamp: null, prompt: null, events: [] };

      for (const ev of casEvents) {
        if (isPrompt(ev)) {
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
        turns: buildTurnsFromEvents(inv.events, inv.index === 1 ? step : null),
      }));
    }

    // Fallback when no cas_transcript
    const turns = buildTurnTimeline(step);
    return [{
      index: 1,
      timestamp: step.started_at || null,
      prompt: step.initial_prompt || null,
      turns,
    }];
  }

  function buildTurnsFromEvents(casEvents, stepForCorrelated) {
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

    if (stepForCorrelated) {
      for (const llm of (stepForCorrelated.llm_invocations || [])) {
        ensureTurn(llm.turn_index ?? 0).llmCalls.push(llm);
      }
      for (const g of (stepForCorrelated.guardrail_events || [])) {
        ensureTurn(g.turn_index ?? 0).events.push({ kind: "guardrail", timestamp: g.created_at, guard: g });
      }
      for (const ce of (stepForCorrelated.compilation_events || [])) {
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
            <strong>Turn ${esc(t.turnIndex)}</strong>
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

  function renderStageTranscript(step) {
    const invocations = buildInvocations(step);
    const hasAnyTurns = invocations.some((inv) => inv.turns.length > 0 || inv.prompt);

    if (!hasAnyTurns) {
      return `<div class="card"><div class="muted">No agent turns or transcript recorded for this stage.</div></div>`;
    }

    const multi = invocations.length > 1;
    const bodyHtml = invocations.map((inv) => {
      const promptHtml = inv.prompt
        ? `<div class="card" style="margin-bottom:10px;">${renderCollapsibleCode(multi ? `Invocation #${inv.index} Prompt` : "Initial Prompt", inv.prompt, false)}</div>`
        : "";
      const turnsHtml = inv.turns.map((t) => renderTurnGroup(t)).join("");
      if (!multi) {
        return `${promptHtml}${turnsHtml}`;
      }
      return `
        <div class="card" style="margin-bottom:14px;">
          <h4>
            <span>Agent Invocation #${inv.index}</span>
            <span class="muted" style="font-weight:400;font-size:11px;">${inv.timestamp ? esc(fmtTs(inv.timestamp)) : ""} &middot; ${inv.turns.length} turn(s)</span>
          </h4>
          ${promptHtml}
          ${turnsHtml}
        </div>
      `;
    }).join("");

    return `
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

    let body = "";
    const thinkingText = ev.thinking || ((ev.kind === "thinking" || ev.kind === "reasoning") ? ev.content : null);
    if (thinkingText) {
      body += renderCollapsibleCode("Thinking", thinkingText, false);
    }
    if (ev.arguments !== undefined && ev.arguments !== null) {
      const argStr = typeof ev.arguments === "string" ? ev.arguments : JSON.stringify(ev.arguments, null, 2);
      body += renderCollapsibleCode("Call", argStr);
    }
    if (ev.result !== undefined && ev.result !== null && ev.result !== "") {
      const resStr = typeof ev.result === "string" ? ev.result : JSON.stringify(ev.result, null, 2);
      body += renderCollapsibleCode("Result", resStr);
    }
    if (!body && ev.content && ev.content !== ev.toolName) {
      body += renderCollapsibleCode("Output", ev.content);
    }

    const kindLabel = ev.paired ? "tool_call &rarr; tool_result" : esc(ev.kind);
    return `
      <div class="event-item ${esc(ev.kind)}" data-kind="${esc(ev.kind)}">
        <div class="event-top">
          <span><strong>${kindLabel}</strong>${ev.toolName ? ` &middot; <code>${esc(ev.toolName)}</code>` : ""}</span>
          <span>${esc(fmtTs(ev.timestamp))}</span>
        </div>
        ${body}
      </div>
    `;
  }

  function bindTranscriptHandlers(container) {
    container.querySelectorAll(".turn-header").forEach((hdr) => {
      hdr.addEventListener("click", () => {
        const body = hdr.nextElementSibling;
        if (body) body.classList.toggle("hidden");
      });
    });

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
          const matchKind = !kind || ev.dataset.kind === kind;
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
            <thead><tr><th>Stage</th><th>Attempt</th><th>Processor</th><th>Invoked</th><th>Success</th><th>Errors</th><th>Warnings</th></tr></thead>
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
                  <td>${esc(d.turn_index ?? "-")}</td>
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

    return html;
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
    return events.map((g) => `
      <div class="card">
        <h4>
          <span>${esc(g.guard_type)} &middot; ${esc(g.violation_category)}</span>
          <span class="badge ${g.action_blocked ? "badge-red" : "badge-yellow"}">${g.action_blocked ? "BLOCKED" : "WARNED"} (turn ${esc(g.turn_index)})</span>
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
        .map((r) => `<option value="${esc(r.run_id)}">${esc(r.benchmark_id)} (${esc(r.run_id.slice(0, 8))} · ${esc(r.verdict || r.status)})</option>`)
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

  // ---- Auto-refresh ----
  function setupAutoRefresh() {
    const toggle = $("autoRefreshToggle");
    toggle.addEventListener("change", () => {
      if (toggle.checked) {
        state.autoRefreshTimer = setInterval(async () => {
          try {
            const health = await apiGet("/api/health");
            if (health.db_mtime && health.db_mtime !== state.lastDbMtime) {
              state.lastDbMtime = health.db_mtime;
              await loadRuns(true);
            }
          } catch {
            // Ignore transient poll errors
          }
        }, 5000);
      } else if (state.autoRefreshTimer) {
        clearInterval(state.autoRefreshTimer);
        state.autoRefreshTimer = null;
      }
    });
  }

  function handleKeyDown(e) {
    if (e.key === "Escape" && !$("artifactModal").classList.contains("hidden")) {
      $("artifactModal").classList.add("hidden");
      return;
    }

    const tag = (e.target?.tagName || "").toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea" || e.metaKey || e.ctrlKey || e.altKey) {
      return;
    }

    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      const filtered = getFilteredRuns();
      if (!filtered.length) return;
      e.preventDefault();
      const idx = filtered.findIndex((r) => r.run_id === state.selectedRunId);
      const nextIdx = e.key === "ArrowDown"
        ? Math.min(filtered.length - 1, idx + 1)
        : Math.max(0, idx === -1 ? 0 : idx - 1);
      if (filtered[nextIdx] && filtered[nextIdx].run_id !== state.selectedRunId) {
        selectRun(filtered[nextIdx].run_id, false).then(() => {
          document.querySelector(".run-card.active")?.scrollIntoView({ block: "nearest" });
        });
      }
      return;
    }

    if ((e.key === "ArrowLeft" || e.key === "ArrowRight") && state.activeRunTab === "stages" && state.runDetail) {
      const executedNames = [...new Set((state.runDetail.steps || []).map((s) => s.step_name))];
      if (!executedNames.length) return;
      e.preventDefault();
      const idx = executedNames.indexOf(state.selectedStepName);
      const nextIdx = e.key === "ArrowRight"
        ? Math.min(executedNames.length - 1, idx + 1)
        : Math.max(0, idx === -1 ? 0 : idx - 1);
      const nextName = executedNames[nextIdx];
      if (nextName && nextName !== state.selectedStepName) {
        const matching = (state.runDetail.steps || []).filter((s) => s.step_name === nextName);
        const latest = matching[matching.length - 1];
        state.selectedStepName = nextName;
        state.selectedStepId = latest ? latest.step_id : null;
        renderStageStepper();
        renderStageInspector();
      }
    }
  }

  // ---- Matrix View (Compare Across Commits) ----
  function setViewMode(mode) {
    state.viewMode = mode;
    $("viewModeInspector").classList.toggle("active", mode === "inspector");
    $("viewModeMatrix").classList.toggle("active", mode === "matrix");

    if (mode === "matrix") {
      $("matrixView").classList.remove("hidden");
      $("emptyState").classList.add("hidden");
      $("runView").classList.add("hidden");
      renderMatrixView();
    } else {
      $("matrixView").classList.add("hidden");
      if (state.runDetail) {
        $("runView").classList.remove("hidden");
        $("emptyState").classList.add("hidden");
      } else {
        $("emptyState").classList.remove("hidden");
        $("runView").classList.add("hidden");
      }
    }
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
    const state = stepState(st);
    const cls = { ok: "badge-ok", fail: "badge-fail", running: "badge-yellow" }[state];
    const label = state;
    const cached = st.cache_hit ? " (C)" : "";
    const tok = st.total_tokens ? ` · ${fmtNum(st.total_tokens)}t` : "";
    return `
      <td class="matrix-cell">
        <button class="matrix-cell-btn" data-run-id="${esc(run.run_id)}" data-stage="${esc(stageName)}" title="Inspect ${esc(stageName)} for ${esc(run.benchmark_id)}">
          <span class="badge ${cls}">${label}${cached}</span>${tok ? `<span class="muted" style="font-size:11px;">${esc(tok)}</span>` : ""}
        </button>
      </td>
    `;
  }

  function renderMatrixView() {
    const el = $("matrixView");
    let runs = getFilteredRuns();
    if (state.selectedBatchId) {
      runs = runs.filter((r) => r.batch_id === state.selectedBatchId);
    }
    if (state.hideDiscardedInMatrix) {
      runs = runs.filter((r) => r.triage_status !== "discarded" && !(r.discard_reason && r.discard_reason !== ""));
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
              <th>Verdict</th>
              <th>Tokens</th>
            </tr>
          </thead>
          <tbody>
            ${runs.map((r) => {
              const mode = r.key_config?.triage_mode || "manifest";
              const verdict = r.verdict || r.status || "-";
              const tokens = r.llm_stats?.total_tokens ? fmtNum(r.llm_stats.total_tokens) : "0";
              return `
                <tr>
                  <td>
                    <button class="matrix-row-btn" data-run-id="${esc(r.run_id)}">
                      <strong>${esc(r.benchmark_id)}</strong>
                      <span class="muted" style="font-size:11px;">${esc((r.commit_hash || "").slice(0, 8))}</span>
                    </button>
                  </td>
                  <td><span class="badge badge-muted">${esc(mode)}</span></td>
                  ${STAGE_ORDER.map((s) => renderMatrixCell(r, s)).join("")}
                  <td><span class="badge ${verdictBadgeClass(r.verdict)}">${esc(verdict)}</span></td>
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
              const regTest = r.regression_test_class
                ? `${r.regression_test_class.split(".").pop()}.${r.regression_test_method || ""}`
                : "-";
              return `
                <tr class="matrix-focus-row" data-run-id="${esc(r.run_id)}" data-stage="triage">
                  <td><strong>${esc(r.benchmark_id)}</strong> <span class="muted" style="font-size:11px;">${esc((r.commit_hash || "").slice(0, 8))}</span></td>
                  <td><span class="badge badge-muted">${esc(mode)}</span></td>
                  <td><code>${esc(r.triage_status || (st ? (isStepRunning(st) ? "running" : (st.success ? "verified" : "discarded")) : "not_run"))}</code></td>
                  <td><code>${esc(r.classification || "-")}</code></td>
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
              <th>Verdict / Notes</th>
            </tr>
          </thead>
          <tbody>
            ${runs.map((r) => {
              const st = latestStep(r, stage);
              const statusBadge = !st
                ? `<span class="muted">not run</span>`
                : `<span class="badge ${{ ok: "badge-ok", fail: "badge-fail", running: "badge-yellow" }[stepState(st)]}">${stepState(st)}</span>`;
              const note = st?.compiler_diagnostics ? st.compiler_diagnostics.slice(0, 80) : (r.verdict || "");
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

    const batchBanner = state.selectedBatchId
      ? `<span class="badge badge-muted" style="margin-left:8px;">Batch: <code>${esc(state.selectedBatchId.slice(0, 8))}</code> <button id="clearBatchFilterBtn" class="btn btn-sm" style="margin-left:4px;padding:0 5px;" title="Show all batches">&times;</button></span>`
      : "";

    el.innerHTML = `
      <div class="matrix-toolbar">
        <div>
          <h2 style="margin:0 0 4px 0;">Stage Matrix <span class="muted" style="font-size:13px;font-weight:400;">(${runs.length} commit${runs.length === 1 ? "" : "s"})</span>${batchBanner}</h2>
          <div class="muted" style="font-size:12px;">Click a stage column or pill to compare one stage across all commits; click any row or cell to inspect that commit.</div>
        </div>
        <div class="matrix-stage-pills">
          <label class="checkbox-inline" style="margin-right:8px;font-size:12px;">
            <input type="checkbox" id="matrixHideDiscarded" ${state.hideDiscardedInMatrix ? "checked" : ""} />
            <span>Hide discarded</span>
          </label>
          ${stagePills}
        </div>
      </div>
      <div class="card" style="overflow-x:auto;">${tableHtml}</div>
    `;

    const clearBatchBtn = $("clearBatchFilterBtn");
    if (clearBatchBtn) {
      clearBatchBtn.addEventListener("click", () => {
        state.selectedBatchId = null;
        renderRunsList();
        renderMatrixView();
      });
    }
    const hideDiscardedEl = $("matrixHideDiscarded");
    if (hideDiscardedEl) {
      hideDiscardedEl.addEventListener("change", (e) => {
        state.hideDiscardedInMatrix = e.target.checked;
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
    document.addEventListener("keydown", handleKeyDown);
    $("refreshBtn").addEventListener("click", () => loadRuns(true));
    $("runSearch").addEventListener("input", renderRunsList);
    $("verdictFilter").addEventListener("change", renderRunsList);
    $("guardrailFilter").addEventListener("change", renderRunsList);
    $("triageModeFilter").addEventListener("change", renderRunsList);
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

    setupAutoRefresh();
    loadRuns(false).catch((err) => {
      $("runsCount").textContent = `Error: ${err.message}`;
    });
  }

  document.addEventListener("DOMContentLoaded", init);
})();
