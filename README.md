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
- **`docs/implementation_plan.md`**: The concrete engineering specification. Covers programming language selection (Kotlin 1.9+ with JavaParser, plus decoupled Python analytics), SQLite DDL schema, OpenCode CLI watchdog harness, Gradle init script, directory layout, and CLI commands.
- **`proposal/Project Proposal.md`**: The original CS 6158 course project proposal document.
- **`eval-repos/`**: Directory housing target evaluation repositories:
  - `eval-repos/synthetic-id-swap/`: A minimal synthetic Java project with a known ID-conflation bug for rapid local debugging.
  - `eval-repos/cmsx/`: Cornell CMSX codebase for real-world evaluation.

---

## 5. Technology Stack & Prerequisites

- **Host Environment**: Linux container / macOS with Docker or native JDK 17+.
- **Pipeline Language**: Kotlin 1.9+ / 2.0 (JVM 17/21) built with Gradle.
- **Agent Harness**: OpenCode CLI (`/usr/bin/opencode` v2.0.32).
- **Type Checker**: Java Checker Framework (v3.42.0+).
- **AST Parsing & Validation**: JavaParser (`com.github.javaparser:javaparser-core:3.26.1`).
- **Storage & Inspection**: SQLite 3, Datasette (Python), Pandas / Jupyter.
- **Default LLM**: Nemotron 3.5 Super VL Free (`nvidia/nemotron-3.5-super-vl-free`), configurable per stage.
