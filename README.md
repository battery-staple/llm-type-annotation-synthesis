# LLM-Assisted Type Annotation Synthesis & Blind Verification

This project investigates combining Large Language Models (LLMs) with formal pluggable type systems (via the Java [Checker Framework](https://checkerframework.org/)) to eliminate domain-specific software vulnerabilities and defects.

---

## 1. Core Hypothesis & Problem of Interest

* **The Problem**: Robust static type systems prevent critical vulnerabilities (e.g., ID conflation, injection, unit mismatches, typestate violations), but developers rarely adopt advanced type checkers due to heavy annotation overhead and the steep learning curve of writing custom type checkers. Conversely, LLMs write code rapidly but frequently hallucinate subtle semantic bugs.
* **The Core Hypothesis**: An LLM can automatically synthesize domain-specific type annotations (and lightweight qualifier hierarchies) at commit $C-1$ (before a bug was fixed) such that a downstream **blind "fixer" agent**—which has never seen the bug report, PR description, or regression test—can fix the method bodies purely by resolving static type checker errors, thereby curing the underlying runtime bug.

---

## 2. Overarching Principles & System Philosophy

Any contributor or autonomous agent working in this repository should adhere to these core principles:

1. **Modularity & Flexibility Above All**:
   This project is an experimental research testbed. All pipeline stages, prompt templates, AST validators, and build runners must be loosely coupled and implement strict abstract interfaces. Any future development (e.g., adversarial test synthesis, test suite annotation, multi-JDK support) must be natural to plug in.
2. **Empirical Configurability Over Premature Optimization**:
   We do not attempt to guess the "single best pipeline" up front. The system is designed with explicit configuration toggles (e.g., frozen vs. editable local variable annotations, network access vs. airgapped sandbox, prompt hinting vs. blind feedback) to enable empirical A/B testing and ablation studies.
3. **Rigorous Agent Isolation & Anti-Reward Hacking**:
   If an agent can cheat to satisfy static checks, it will. The pipeline enforces defense-in-depth across both information isolation (preventing agents from learning about the intended bugfix or PR context) and structural guardrails (preventing bypasses, suppressions, or cheating).
4. **Rich Relational Observability**:
   Decouple data collection from querying. Trajectories, tool execution traces, compiler diagnostics, token usage, and rich commit metadata are recorded into a relational SQLite database with JSON columns (`data/pipeline.db`), enabling instant SQL queries and Datasette web visualization.
5. **Aggressive Descoping & Factoring Out to Future Developments**:
   When designing and implementing pipeline iterations, aggressively factor out secondary mechanisms, edge cases, and optimizations (e.g. Maven support, multi-JDK management, `.astub` overlays, adversarial games, continuous triage thresholds) into well-documented Future Developments. Keep initial drafts and code implementations lightweight and verifiable end-to-end first.

---

## 3. High-Level Pipeline Architecture

```
[Git Repo R at Commit Depth d]
          │
          ▼
   [Step 1: Ingestion]  ─── Ephemeral Git worktrees & shared pre-warmed Gradle caches
          │
          ▼
   [Step 2: Triage]     ─── Simple LLM classifies pure bugfixes;
                            Deterministic check: Regression test from C MUST fail on clean C-1
          │
          ▼
   [Step 3: Synthesis]  ─── OpenCode agent generates custom-quals.jar + placement_guide.md
                            (Level 0 pure marker qualifiers; zero executable helper code)
          │
          ▼
   [Step 4: Placement]  ─── OpenCode agent applies annotations from placement_guide.md;
                            JavaParser AST gate guarantees zero executable code changes
          │
          ▼
   [Step 5: Repair]     ─── Blind OpenCode Fixer agent resolves type errors in method bodies;
                            Configurable anti-cheat guards prevent @SuppressWarnings / signature drift
          │
          ▼
   [Step 6: Evaluation] ─── Run verified regression test from C on fixer's patched C-1 code;
                            Record full trajectory & verdict in SQLite database
```

---

## 4. Repository & Documentation Guide

- **`docs/design_plan.md`**: The conceptual pipeline design document. Covers the high-level stages, implementer freedom levels, active defense matrix and anti-cheat guards, experimentation matrix, metrics registry, and detailed sketches of future developments.
- **`docs/implementation_plan.md`**: The concrete engineering specification. Covers programming language selection (Kotlin 2.0 with JavaParser, plus decoupled Python analytics), SQLite DDL schema, OpenCode CLI watchdog harness, Gradle init script, directory layout, and CLI commands.
- **`docs/critique_report.md`**: Independent review of an earlier design draft. Historical context; it does not describe the current plans.
- **`docs/metrics_recommendations.md`**: Sixty proposed measurement and instrumentation additions.
- **`docs/additional_future_improvements.md`**: A ranked research agenda of twenty-six additional experiments.
- **`deviations.md`**: Places where the implementation intentionally differs from the engineering plan, including environment constraints and bug fixes.
- **`proposal/Project Proposal.md`**: The original CS 6158 course project proposal document.
- **`project_config.yaml`**: Global, machine-independent pipeline policy, including paths, environment defaults, guardrails, models, and stage settings.
- **`benchmarks.yaml`**: Per-benchmark manifest containing repository identity, commit selection, build configuration, and Java compatibility settings.
- **`analysis/evaluate_metrics.py`**: Offline telemetry report over `data/pipeline.db`.
- **`analysis/inspect_cache.py`**: Read-only inventory of cached stage bundles, seeds, artifacts, and run associations.
- **`eval-repos/`**: Directory housing target evaluation repositories:
  - `eval-repos/synthetic-id-swap/`: A minimal synthetic Java project with a known ID-conflation bug for rapid local debugging.
  - `eval-repos/cmsx/`: Cornell CMSX codebase (submodule). Curated benchmark
    `cmsx-assignment-extension` pins the extension-acceptance fix
    (`be6f19da`, parent `7ee122ff`) with regression test
    `cms.controller.persistence.AssignmentControllerImplTest#getSubmissionStatus_assignmentGraded_pastDue_hasExtension_accept`.
    CMSX uses a flat layout, so the entry declares `production_roots: ["src"]`
    and `test_roots: ["test", "regression/src"]` (see "CMSX benchmark" below).

---

## 5. Technology Stack & Prerequisites

- **Host Environment**: Linux container / macOS. The pipeline requires a JDK 21 toolchain; target repositories compile at their own Java level (17 for the synthetic benchmark).
- **Pipeline Language**: Kotlin 2.0.21 built with Gradle.
- **Agent Harness**: OpenCode CLI (v2.2.5 in the current container).
- **Type Checker**: Java Checker Framework 3.42.0; when `environment.checker_framework_home` is blank, its location is resolved from `CHECKERFRAMEWORK`, or defaults to `vendor/checker-framework-3.42.0`.
- **AST Parsing & Validation**: JavaParser (`com.github.javaparser:javaparser-core:3.28.2`), parsing at language level `JAVA_21` so CMSX sources (including record patterns such as `instanceof Some(var x)`) parse in the guards.
- **Storage & Inspection**: SQLite 3, Datasette (Python), Pandas / Jupyter.
- **Default LLM**: `opencode-go/deepseek-v4.1-flash`, configurable per stage. The pipeline seeds its isolated OpenCode DB with the global account/credential rows; a blank isolated DB otherwise makes `opencode-go/*` return `provider.no-route`.

## 6. Build and Run

```bash
./gradlew test                 # unit and guard tests
./gradlew installDist          # builds build/install/pipeline/bin/pipeline

./build/install/pipeline/bin/pipeline run \
  --config project_config.yaml \
  --benchmark synthetic-id-swap \
  --seed 0

python3 analysis/evaluate_metrics.py --db data/pipeline.db
python3 analysis/inspect_cache.py
```

**Configuration**: `project_config.yaml` is machine-independent, and its relative paths resolve against the config file. Per-benchmark build and Java compatibility settings live in `benchmarks.yaml`. Set `JAVA_HOME`, `GRADLE_USER_HOME`, and `CHECKERFRAMEWORK` for machine-specific overrides; `--model` overrides the configured model.

**Stopping early**: `--stop-after <stage>` ends the run as soon as that stage succeeds and skips every later stage:

```bash
./build/install/pipeline/bin/pipeline run \
  --config project_config.yaml \
  --benchmark synthetic-id-swap \
  --stop-after triage        # ingestion + triage only
```

The run is finalized with the database lifecycle status `stopped_after_<stage>` (the CLI prints `lifecycle_status: stopped` plus the `stopped_after` line) and records no evaluation verdict. Completed stages are still published to the CAS, so re-running the same command without the flag reuses their bundles instead of repeating the work. Accepted values are `ingestion|preflight|triage|synthesis|application|repair|verification` (`preflight` only with `--preflight`); `verification` is the same as omitting the flag, and an unknown value is rejected with that list. A discard or halt in the named stage still ends the run as `discarded`/`failed`, regardless of the stop point.

### Preflight (optional stage)

By default a run has the six stages above. `--preflight` adds a seventh,
between ingestion and triage, that checks the build integration before any LLM
call (checks P1-P7 in `docs/dockerization_plan.md`, section 7.3):

```bash
./build/install/pipeline/bin/pipeline run \
  --config project_config.yaml \
  --benchmark synthetic-id-swap \
  --preflight
```

Preflight checks the environment and source level, a baseline compile, a
compile with the harness init script and a fixed probe lattice, a
probe-annotated compile, two checker runs (one must report exactly one planted
error), one test class, and the full test suite under the build timeout. A
repository outside the supported envelope ends with lifecycle status
`unsupported` and a reason code, recorded in `preflight_results`, and is
excluded from evaluation metrics; the run then makes no LLM call. Results are
cached on the build configuration, so commits that change only Java sources
share one result.

- `preflight.enabled: true` in `project_config.yaml` turns the stage on for
  every run, as `--preflight` does for one run.
- `preflight.suite_check: false` skips P7, the full-suite check.
- `--stop-after preflight` stops after the stage and requires `--preflight`.
- With preflight, every later stage's step number moves up by one (triage is
  step 3 instead of 2). Without it, step numbers are unchanged.

Preflight can also run on its own, without the rest of the pipeline. This
command always runs it, whatever `preflight.enabled` says:

```bash
# --manifest takes benchmarks.yaml or repos.yaml; --id is repeatable (omit it for every entry)
./build/install/pipeline/bin/pipeline preflight \
  --config project_config.yaml \
  --manifest benchmarks.yaml \
  --id jsqlparser-index-kind
```

For a benchmark it checks the parent commit; for a `repos.yaml` entry,
`preflight_commit` or the head of the default branch. `--skip-suite` skips the
full suite and `--no-cache` ignores cached results. The summary compares each
outcome with the entry's `expected_preflight` prediction. Preflight never lets
Gradle download a toolchain, so a missing JDK is reported as `jdk_unavailable`.

### Crawler mode

Crawler mode reads repositories from `repos.yaml`. `triage.depth` (default
`20`) is the number of commits to crawl; set it explicitly for larger crawls
(`--depth 1000` for 1,000 commits). `--limit` is the maximum number of
verified samples admitted to downstream stages, not a scan limit: all
non-root crawled commits are ingested and triaged even after the downstream
limit is reached. Use these independent controls (and their config keys):

- `triage.jobs` / `--jobs` (default `1`): concurrent crawler commit runners.
- `concurrency.agents` / `--agent-jobs` (default `8`): concurrent agent/LLM
  calls.
- `concurrency.builds` / `--build-jobs` (default `2`): concurrent Gradle
  compile, test, and Checker invocations.

Retry limits are configured in `project_config.yaml` (no CLI flags):

- `stage.attempts` (default `3`): whole-stage attempts for transient failures;
  set `1` to disable retries. `stage.backoff_ms` (default `1000`) is the
  exponential delay base (2s, 4s, 8s, ...).
- `synthesis.retry_sessions` and `application.retry_sessions` (default `3`
  each): agent feedback sessions within one stage execution.
- `repair.retry_turns` (default `4`): repair feedback turns, still bounded by
  `agent.max_checker_invocations`.

Retry limits participate in stage cache keys, so changing one invalidates the
affected cached artifacts on the next run.

`jobs` still bounds whole per-commit pipelines. Agent and build semaphores are
nested within those pipelines and independently cap those resource types; they
do not increase the number of commit runners. For example, on an M2 Max:

```bash
./build/install/pipeline/bin/pipeline run --config project_config.yaml \
  --benchmark synthetic-id-swap --triage-mode crawler \
  --depth 1000 --jobs 8 --agent-jobs 8 --build-jobs 2 --limit 1000
```

Remember that `--depth` defaults to only 20 commits, so raise it explicitly for
large crawls. `--limit` is downstream-only and does not reduce scanning,
ingestion, or triage.

For a staged pilot, start with 25/50 commits, then 100/250 commits, using 8
agents and 2 builds. Increase agents to 12–16 only if the provider is stable;
keep heavy Checker builds at 1–2. Monitor wall time, agent latency and HTTP
429s, Gradle/Checker memory, Gradle daemon count, and timeout/retry rate before
scaling further.

When a verified sample is beyond the downstream gate, its run ends as
`triage_only`: it has no evaluation verdict, and its triage CAS bundle is
retained for reuse. In `v_evaluation_verdicts`, interpret `outcome_kind` to
separate these from evaluated runs; `triage_only` is not a failed evaluation
and must not be counted as one. The viewer presents the lifecycle status
without a verdict; the offline `analysis/evaluate_metrics.py` verdict section
shows the `triage_only` status and NULL verdict.

### Logs, cleanup, and telemetry

Successful and `triage_only` runs remove their run worktrees and scratch
directories. Failed or discarded runs and `--stop-after` runs are retained for
debugging. Per-run log appenders are best-effort, capped at 64 active run IDs,
and have a finite child timeout; the rolling JSON log is authoritative. If
concurrency exceeds the per-run appender cap, some per-run files may be
unavailable; use `logs/pipeline.log` and its rolled files, filtering by
`run_id`, for full history. Startup retention prunes run logs older than 14
days or beyond 2,000 files, orphaned CAS staging directories, stale OpenCode
seed DBs/sidecars, and stale per-invocation temporary DBs.

Database-operation timing/success telemetry is stored in
`db_operation_metrics` and summarized by `v_db_operation_summary`. Retry
attempts and decisions are stored in `retry_events` and summarized by
`v_retry_summary`. Subprocess timing telemetry is stored in
`subprocess_metrics` and summarized by `v_subprocess_summary`; rows record the
stage, subprocess kind, duration, exit code, timeout status, and success.
The viewer exposes subprocess summaries, and the `evaluate_metrics.py`
subprocess section provides offline aggregate timing/outcome reporting. The
viewer also provides run-level lifecycle context; use these alongside the
database-operation and retry summaries.

Runtime state (`data/`, `logs/`, `.cache/`, `.worktrees/`, `vendor/`, build output) is gitignored. A fresh checkout needs the Checker Framework vendored before running.

### Local environment

Machine-specific values are supplied through environment variables, not the committed config:

```bash
cp .env.example .env
$EDITOR .env
```

The pipeline reads `.env` automatically before loading `project_config.yaml`. It looks next to `--config` (usually the repository root), then in the working directory; set `PIPELINE_ENV_FILE` to use a different file. Existing shell environment variables take precedence, and the file is parsed rather than sourced, so `$VAR` and `$(...)` are not expanded — use absolute paths.

The template covers the JDK path, Checker Framework location, optional Gradle cache, OpenCode DB paths, and the scratch location. The most common required changes for a new checkout are:

1. Build and run the pipeline on JDK 21. If child target builds need an explicit JDK, uncomment `JAVA_HOME` in `.env`.
2. Install Checker Framework 3.42.0 at `vendor/checker-framework-3.42.0/`, or uncomment `CHECKERFRAMEWORK` and point it at the installation.
3. Install and authenticate the `opencode` CLI. If you are not on OpenCode Go, pass `--model <provider/model>` instead of editing `project_config.yaml`.
4. Initialize at least the synthetic benchmark submodule:
   `git submodule update --init eval-repos/synthetic-id-swap`
   (the `cmsx` submodule requires Cornell GitLab access).
5. Run the pipeline from the repository root; `.worktrees/` is still relative to the working directory.

Everything in `project_config.yaml` and `benchmarks.yaml` stays shared across machines. `--seed` selects the sample index, and `--model` overrides the configured model for a run.

### CMSX benchmark

The `cmsx-assignment-extension` entry runs the pipeline against the real CMSX
codebase, which differs from the synthetic project in two ways the pipeline
now handles per benchmark entry:

- **Flat source layout.** Production lives under `src/` while tests live under
  `test/` (the root project) and `regression/src/` (the `:regression`
  subproject), not `src/main` / `src/test`. The entry sets
  `production_roots: ["src"]` and `test_roots: ["test", "regression/src"]`;
  triage patch discovery/auditing, the synthesis production-diff filter, agent
  workspace isolation, prompt wording, and the application/repair filesystem
  and AST guards all follow these roots.
  Single-segment roots are anchored at the repository root, so a bare `src`
  does not sweep in `regression/src` or `cmsx-ui/src`, and a bare `test` does
  not match CMSX production helpers under
  `src/cms/www/action/actions/api/test/`. Multi-segment roots still match at
  any depth (`module/src/test`), preserving multi-module legacy layouts.
  Test roots win if both roots match, so a broad production root can never make
  test sources editable. Benchmarks that omit the roots keep the legacy
  `src/main` / `src/test` behavior unchanged.
- **Java 21.** CMSX compiles with `options.release = 21`; the JavaParser
  guards parse at language level `JAVA_21`, and the entry sets
  `source_compatibility: "21"` / `target_compatibility: "21"`.

Prerequisites:

1. Cornell GitLab access over SSH, then:
   `git submodule update --init eval-repos/cmsx`
   (the synthetic submodule needs no special access).
2. JDK 21 for the pipeline and the CMSX Gradle toolchain, plus Checker
   Framework 3.42.0 at `vendor/checker-framework-3.42.0/` (or set
   `CHECKERFRAMEWORK`).
3. Expect a heavy first build: CMSX resolves a large dependency set and its
   `compileTestJava` runs JPA weaving.

The pipeline runs project-qualified Gradle tasks, so an empty `subproject`
means exactly the root project:

- Triage runs only the regression test:
  `./gradlew :cleanTest :test --tests <test_class>.<test_method>`.
- Verification runs the same regression test, plus the pre-existing suite
  (`:test` without `--tests`) when `verification.scope` is `full_suite`.

CMSX's `:regression` subproject is intentionally outside the benchmark and is
not invoked; its test sources are still classified as tests and kept
read-only. A filter applied to the aggregate `test` task would otherwise make
`:regression:test` fail with "No tests found for given includes".

Run it (from the repository root, after `./gradlew installDist`):

```bash
./build/install/pipeline/bin/pipeline run \
  --config project_config.yaml \
  --benchmark cmsx-assignment-extension \
  --seed 0
```

## 7. Current Status

- The synthetic `synthetic-id-swap` benchmark completed end-to-end with verdict
  **`FULL_SUCCESS`**: the synthesized hierarchy produced checker errors at the
  conflated `debit`/`credit` call sites, the blind fixer resolved them in one
  turn, the final checker reported 0 errors, the regression test passed, and the
  seven pre-existing tests passed.
- CMSX has a curated benchmark entry, `cmsx-assignment-extension` (extension
  acceptance for graded assignments; `be6f19da` / parent `7ee122ff`,
  `AssignmentControllerImplTest#getSubmissionStatus_assignmentGraded_pastDue_hasExtension_accept`).
  Triage was verified manually against the pinned revisions: the derived test
  patch touches only `test/cms/.../AssignmentControllerImplTest.java`, the
  regression test **fails on C-1** (`expected ACCEPT but was REJECT`) and
  **passes on C**. A full end-to-end LLM run has not been attempted (no LLM
  run is claimed).
- Crawler mode has a parallel per-commit scheduler, a hard downstream gate,
  finite child-process timeouts, and lifecycle-aware cleanup. `--depth` must
  be raised explicitly for large crawls; `--limit` gates downstream work, not
  ingestion/triage. This remains experimental, not a claim of deterministic or
  production-ready 1,000-commit operation.
- Operational limits remain: output/transcript caps and daemon policy need
  further work, and larger runs require resource piloting. See `deviations.md`.
- Known limitations and implementation gaps are tracked in `deviations.md`.
