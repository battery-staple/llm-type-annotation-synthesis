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
  - `eval-repos/cmsx/`: Cornell CMSX codebase (submodule present, but no curated benchmark entry yet).

---

## 5. Technology Stack & Prerequisites

- **Host Environment**: Linux container / macOS. The pipeline requires a JDK 21 toolchain; target repositories compile at their own Java level (17 for the synthetic benchmark).
- **Pipeline Language**: Kotlin 2.0.21 built with Gradle.
- **Agent Harness**: OpenCode CLI (v2.2.5 in the current container).
- **Type Checker**: Java Checker Framework 3.42.0; when `environment.checker_framework_home` is blank, its location is resolved from `CHECKERFRAMEWORK`, or defaults to `vendor/checker-framework-3.42.0`.
- **AST Parsing & Validation**: JavaParser (`com.github.javaparser:javaparser-core:3.26.1`).
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

Runtime state (`data/`, `logs/`, `.cache/`, `.worktrees/`, `vendor/`, build output) is gitignored. A fresh checkout needs the Checker Framework vendored before running.

## 7. Current Status

- The synthetic `synthetic-id-swap` benchmark completed end-to-end with verdict
  **`FULL_SUCCESS`**: the synthesized hierarchy produced checker errors at the
  conflated `debit`/`credit` call sites, the blind fixer resolved them in one
  turn, the final checker reported 0 errors, the regression test passed, and the
  seven pre-existing tests passed.
- CMSX is present as a submodule but has no curated benchmark entry yet.
- Known limitations and implementation gaps are tracked in `deviations.md`.
