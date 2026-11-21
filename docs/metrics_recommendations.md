# Measuring the Pipeline: Additional Metrics for LLM Type-Annotation Synthesis

**Status.** Companion document to `README.md`, `docs/design_plan.md`, `docs/implementation_plan.md`, and `docs/critique_report.md`. It proposes measurement and instrumentation, not changes to stage semantics, model defaults, or existing verdict semantics. A few metrics need more than passive logging: a binary triage label (optional future multi-class labels are analysis-only), extra baseline arms, a pre-placement Checker pass, a non-fatal conformance flag, or missing guardrail detectors. Those prerequisites are named in the relevant entry and summarized in Section 3.4.1 so they can be budgeted rather than discovered during implementation.

**Scope.** Sixty additional metrics spanning the full `C` / `C-1` blind-verification pipeline, from corpus construction through human review. Each entry states what is measured, how to capture it, why it matters for this pipeline, what one might learn, and how to report it.

**Audience.** The two project members and anyone reading the resulting paper who needs to know which quantities were measured and which were not.

## Table of Contents

1. [Purpose, scope, and relationship to the existing documentation](#1-purpose-scope-and-relationship-to-the-existing-documentation)
2. [The pipeline in brief](#2-the-pipeline-in-brief)
3. [How to read the metric entries](#3-how-to-read-the-metric-entries)
4. [Dataset and corpus construction (D1-D5)](#4-dataset-and-corpus-construction)
5. [Input representation and prompt construction (P1-P5)](#5-input-representation-and-prompt-construction)
6. [LLM inference behavior (I1-I6)](#6-llm-inference-behavior)
7. [Output parsing, patch assembly, and post-processing (O1-O5)](#7-output-parsing-patch-assembly-and-post-processing)
8. [Static analysis and type validation (T1-T5)](#8-static-analysis-and-type-validation)
9. [Evaluation and scoring (E1-E8)](#9-evaluation-and-scoring)
10. [Cost, latency, and resources (C1-C4)](#10-cost-latency-and-resources)
11. [Reproducibility, provenance, and determinism (R1-R4)](#11-reproducibility-provenance-and-determinism)
12. [Failure modes and error taxonomy (F1-F4)](#12-failure-modes-and-error-taxonomy)
13. [Robustness and sensitivity (S1-S6)](#13-robustness-and-sensitivity)
14. [Contamination and leakage (L1-L4)](#14-contamination-and-leakage)
15. [Human evaluation (H1-H4)](#15-human-evaluation)
16. [Prioritized implementation roadmap](#16-prioritized-implementation-roadmap)
17. [Reporting playbook](#17-reporting-playbook)
18. [Threats to validity](#18-threats-to-validity)
- [Appendix A. Proposed event and table schema](#appendix-a-proposed-event-and-table-schema)
- [Appendix B. Metric-to-registry crosswalk](#appendix-b-metric-to-registry-crosswalk)
- [Appendix C. Glossary](#appendix-c-glossary)

---

## 1. Purpose, scope, and relationship to the existing documentation

The pipeline's primary endpoint is deliberately narrow: does the regression test `T_reg` from bugfix commit `C` pass on the blind fixer's patched `C-1`? The existing registry in `docs/design_plan.md` §6 records that verdict and 26 supporting quantities, mostly terminal counts such as `synthesis.turns_taken`, `application.annotations_added_count`, and `fixer.remaining_type_errors`. That registry is a good scoreboard, but a scoreboard is not an explanation. It cannot say why a run reached `type_success_runtime_fail`, what information the "blind" fixer actually saw, whether the Checker errors it repaired were caused by the synthesized types or by legacy code, whether the same result would survive a prompt paraphrase, or what one cured bug cost.

This document fills that gap with sixty metrics organized around the six pipeline stages and six cross-cutting concerns (cost, reproducibility, failure, robustness, contamination, and human judgment). Each metric is *additional*: it either measures a quantity the registry does not measure at all, or it decomposes an existing aggregate into a dimension that the registry collapses. Section 3.3 defines those relation labels precisely, and Appendix B maps every extension back to its existing counterpart.

Three principles govern the selection:

1. **Every metric must change a decision.** A metric earns its place only if a plausible result would alter a prompt, a budget, a guardrail, a model choice, a corpus rule, or a claim in the paper. Metrics that are merely interesting are excluded.
2. **Measure the pipeline as a causal experiment, not just an output.** The core hypothesis is comparative: synthesized types should make the blind fixer succeed more often than the same fixer with no types or with built-in types only. Metrics are designed to support that comparison, not to decorate a single-arm success rate.
3. **Instrumentation must fit the project.** The repository has no implementation code yet: it contains documentation, an empty `eval-repos/` directory, and a plan. Every metric here is therefore specified against the planned Kotlin/SQLite/OpenCode/Checker Framework pipeline and is feasible for two researchers with one or two evaluation repositories. Expensive designs are marked optional or scope-limited in the entry itself.

**Non-goals.** This document does not propose new runtime stages, new models, new datasets, or a replacement for the five `eval.verdict` values. E3's Step 6b is an offline verification pass, and E2 decomposes the existing verdict into an ordered vector without renaming its five outcomes. It does not argue for a particular statistical philosophy beyond paired comparisons and clustered uncertainty. It does not change stage logic, guardrails, or prompts as part of the metrics themselves; where a metric needs a new detector, an output field, or an extra run, that is a prerequisite in Section 3.4.1. A reader should budget for those prerequisites rather than assume the document is purely passive.

## 2. The pipeline in brief

A reader who has not opened the other documents needs only the following model of the system.

### 2.1 Six stages and their artifacts

| Stage | Name | Input | Output | Characteristic failure |
| :--- | :--- | :--- | :--- | :--- |
| S1 | Ingestion | Repository `R`, commit depth `d`, local mirror | Ephemeral `C-1` worktree, pre-warmed Gradle cache | Build rot, wrong JDK, cache or worktree leakage |
| S2 | Triage | Diff and message of `C`, PR text | Verified sample `(C, C-1, T_reg)` or a discard reason | Misclassification, test patch does not compile on `C-1`, test passes unpatched |
| S3 | Synthesis | Buggy `C-1`, bug/PR description | `custom-quals.jar` (qualifiers plus pure validators/coercions) and `placement_guide.md` | Vague guide, non-compiling jar, vacuous lattice, impure helper |
| S4 | Application | `C-1`, `placement_guide.md` | Annotated `C-1` with at least one active Checker error | Executable edit blocked by the AST gate, trivial lattice, annotation scatter |
| S5 | Repair | Annotated `C-1`, Checker diagnostics | Patched method bodies, ideally zero remaining errors | Thrash, guard evasion, budget exhaustion, fixing unrelated diagnostics |
| S6 | Verification | Patched `C-1`, `T_reg` | `eval.verdict` and test outcomes | Wrong-invariant fix, collateral regression, flaky test |

The defining invariants are: `T_reg` must compile on `C-1` and fail at runtime there (S2); the applier may change annotations but not executable code, checked by `strip_annotations(AST_original) == strip_annotations(AST_modified)` (S4); the annotated tree must trigger at least one Checker error (S4); the fixer may edit method bodies only, may not touch `src/test/**`, and never receives the commit message, PR text, diff of `C`, or `T_reg` (S5); and a `full_success` requires zero Checker errors, all original unit tests passing, and `T_reg` passing (S6).

### 2.2 Naming conventions across the documents

The documents do not use one canonical name for every stage and key. This document uses the following, with aliases noted so that citations remain checkable:

| This document | `README.md` §3 | `design_plan.md` §2-§3 | `implementation_plan.md` §3 |
| :--- | :--- | :--- | :--- |
| S1 Ingestion | Ingestion | Workspace & Cache Ingestion | (no `step_name` value) |
| S2 Triage | Triage | Commit Triage & Test Extraction | `triage` |
| S3 Synthesis | Synthesis | Type Lattice & Guard Synthesis | `synthesis` |
| S4 Application | Placement | Semantic Type Application | `application` |
| S5 Repair | Repair | Blind Bug Fixing | `fixing` |
| S6 Verification | Evaluation | Ground-Truth Verification | `verification` |

Two config-name drifts matter for instrumentation. `design_plan.md` §5 writes `model.triage`/`model.synthesis`/`model.application`/`model.fixer`; `implementation_plan.md` §9 writes `model.*` and includes `model.application`. This document uses `model.<stage>` for the YAML, because that is the file an implementer edits. Turn caps appear as `agent.steps.synthesis` (25), `agent.steps.application` (20), and `agent.steps.fixer` (50) in the design, and as `agent.steps.synthesis`/`agent.steps.application`/`agent.steps.repair` in the plan; both are named where relevant.

### 2.3 What the current registry measures

The 27 top-level metrics in `design_plan.md` §6 are summarized below. They are not re-proposed anywhere in this document.

| Stage | Existing metrics | What they establish | What they leave invisible |
| :--- | :--- | :--- | :--- |
| S2 | `triage.total_commits_evaluated`, `triage.classification_counts`, `triage.continuous_scores`, `triage.regression_tests_found_count`, `triage.test_methods_extracted_count`, `triage.discard_reasons`, `triage.yield_rate`, `triage.token_usage`, `triage.avg_latency_ms`, `triage.commit_metadata` | How many commits were scanned, how they were classified, how many survived, why the rest were discarded | Whether the classifier is correct, which sub-stage of the gauntlet loses commits, whether the surviving corpus is representative, why tests fail to port |
| S3 | `synthesis.turns_taken`, `synthesis.compiler_feedback_rounds`, `synthesis.checker_category`, `synthesis.qualifiers_count`, `synthesis.helpers_count` | Terminal effort and the size of the synthesized surface | What the agent did per turn, whether the lattice is vacuous, whether helpers are pure or used, what the guide actually specifies |
| S4 | `application.turns_taken`, `application.ast_guard_rejections`, `application.annotations_added_count`, `application.total_checker_errors`, `application.is_trivial` | Terminal effort, raw rejection count, raw annotation and error counts | Whether errors are new or pre-existing, where they localize, whether annotations match the human fix, whether the gate rejects valid edits |
| S5 | `fixer.turns_taken`, `fixer.guard_rejections`, `fixer.remaining_type_errors`, `eval.existing_unit_tests_passed` | Terminal effort and endpoint outcomes | Error-set convergence, thrash, which active defense vectors were attempted, what diagnostics were delivered, whether adherence held when no guard fired |
| S6 | `eval.regression_test_passed`, `eval.verdict`, `eval.token_usage` | The headline verdict and total token spend | Causal lift over baselines, partial credit, fix fidelity, collateral breadth, cost per success, whether the human fix is even typeable under the lattice |

### 2.4 What the sixty additional metrics cover

The additions are deliberately distributed across the whole pipeline rather than concentrated on the final score. Section 3.5 gives the full coverage map. In summary: five corpus metrics, five prompt metrics, six inference metrics, five parsing metrics, five static-analysis metrics, eight evaluation metrics, four cost metrics, four reproducibility metrics, four failure-taxonomy metrics, six robustness metrics, four contamination metrics, and four human-evaluation metrics. Twenty-seven are tagged must-have, twenty-nine should-have, and four nice-to-have. "Must-have" means needed before a headline claim is final, not necessarily before the first batch: nineteen of the twenty-seven form the Phase 0 core that must exist before the first full evaluation batch, and the other eight are collected in the first analysis pass (Section 16).

## 3. How to read the metric entries

### 3.1 Entry template

Every metric, or tight cluster of metrics, uses the same five bullets:

- **Definition.** The operational quantity, its formula or units, and the point in the pipeline where it is captured.
- **Instrumentation.** The event, field, counter, or sampling rule that produces it.
- **Justification.** The specific blindness it removes and the decision it informs.
- **Expected learnings.** Concrete hypotheses and the analysis, table, or plot that would test them.
- **Reporting.** Aggregation level, chart type, statistical comparison, and at least one threat to validity.

The fields are intentionally redundant with the summary index. A reader who wants only the inventory can use Table 3.1; a reader who wants to implement one metric can read only its entry.

Two naming collisions are unavoidable because the source documents already use the same letters. First, `S1`-`S6` denote both pipeline stages and robustness metric IDs. Tables use them as stage labels; prose that means the robustness metric says "metric S1", and prose that means the stage says "Stage 5". Second, `T1`-`T5` denote static-analysis metric IDs, while the design's localization tiers are written "tier 1"-"tier 4". Critique findings keep their hyphenated form (`D-2`, `P-9`), which is distinct from metric IDs (`D2`; there is no metric `P9`).

### 3.2 Priority and effort rubric

| Tag | Meaning | Consequence |
| :--- | :--- | :--- |
| **Must-have** | Without it, a headline claim in the paper is uninterpretable or the main experiment cannot be evaluated. | Collect before the headline result is reported; the Phase 0 subset must exist before the first full evaluation batch. |
| **Should-have** | It materially sharpens an interpretation or catches a plausible confound, but the paper can be written without it if time runs out. | Add during the first analysis pass. |
| **Nice-to-have** | It answers a secondary question or stress-tests a result that is already supported. | Only after must-have and should-have instrumentation is stable. |
| **Low effort** | Deterministic logging or an offline join over data that already exists; no new model calls or human labor. | Hours to a day. |
| **Medium effort** | Requires a parser, a small amount of new collection, or a modest number of additional runs. | Several days. |
| **High effort** | Requires manual labeling, a human study, a combinatorial run matrix, or a new subsystem. | Plan explicitly; scope down if necessary. |

### 3.3 Relation to the existing registry

Each entry carries one of three relation labels:

- **new**: the quantity is not represented in the 27-metric registry in any form.
- **extends `<metric>`**: the entry adds a dimension that the existing aggregate collapses, such as decomposing `triage.yield_rate` into per-stage retention or `application.total_checker_errors` into baseline and new errors.
- **adds dimension to `<metric>`**: the entry keeps the existing metric as a component but adds an orthogonal axis, such as adding a reviewer label to `eval.verdict`.

An extension is not a re-proposal. For example, `fixer.turns_taken` is already recorded; I2 does not propose recording it again, but proposes the per-turn error-set delta that explains *why* a given turn count was reached. Appendix B lists every extension and its new IDs.

### 3.4 Instrumentation substrate and proposed schema

The planned schema in `implementation_plan.md` §3 defines `data/pipeline.db` with four tables: `repositories`, `commits`, `evaluation_runs`, and `step_trajectories`. The planned `step_trajectories` table carries `metrics JSON`, `agent_transcript JSON`, `compiler_diagnostics TEXT`, and `diff_patch TEXT`, which would be enough to prototype most metrics. The entries below assume the following additive structures. They are places to put observations that the current schema has nowhere to store; the few that require a new computation or an extra run are listed separately in Section 3.4.1.

| Structure | Status | Purpose |
| :--- | :--- | :--- |
| `metric_events` | Proposed table | Append-only event stream: `event_id`, `run_id` (nullable for pre-run triage), `scan_id`, `commit_hash`, `stage`, `turn_index`, `event_type`, `ts`, `payload JSON`. One row per LLM call, turn, build, gate decision, or test run. |
| `evaluation_runs.baseline_arm` | Proposed column | `fixer_no_types`, `fixer_builtin_only`, or `fixer_typed`; `fixer_localization_only` is an optional fourth value. Required for E1. |
| `evaluation_runs.config_hash` | Proposed column | Hash of the full resolved config; groups ablation arms without parsing JSON. |
| `evaluation_runs.run_metrics` | Proposed JSON column | Run-level rollup for metrics that do not belong to a single stage. The current DDL has no `metrics` column on this table. |
| `evaluation_runs.artifact_manifest` | Proposed JSON column | Per-run artifact paths, sizes, and SHA-256 hashes; supports R4. |
| `evaluation_runs.replay_of_run_id` | Proposed column | Links a replay to its original for R2. |
| `evaluation_runs.env_hash` | Proposed column | Resolved model/toolchain identity for R3. |
| `step_trajectories.started_at`, `completed_at` | Proposed columns | Wall-clock decomposition; `created_at` alone cannot separate LLM wait from build time. |
| `checker_findings` | Proposed table | One row per normalized diagnostic: `run_id`, `stage`, `turn_index`, `checker_key`, `file`, `line`, `enclosing_method`, `origin`, `error_key`, `message`. |
| `annotation_placements` | Proposed table | One row per placed annotation: `run_id`, `file`, `line`, `symbol`, `qualifier`, `guide_directive_id`, `tier`; backs T2's `annotation_id` and T5's site denominator. |
| `triage_gold_label` | Proposed table | `commit_hash`, `gold_label`, `preventability_label`, `bug_class`, `annotator_id`, `adjudicated`, `sample_stratum`, `inclusion_prob`; backs D2, D3, and H1. |
| `test_portability_probe` | Proposed table | `commit_hash`, `discard_reason`, `compile_error_class`, `missing_symbols JSON`, `jdk_target`, `gradle_exit_code`, `adaptation_candidate`; backs D5. |
| `test_outcomes` | Proposed table | One row per test case per run: `run_id`, `suite`, `test_id`, `status`, `duration_ms`, `baseline_status`. |
| `human_ratings` | Proposed table | `run_id` (nullable), `commit_hash`, `rater_id`, `dimension`, `label_or_score`, `blinding`, `review_minutes`, `interventions`, `notes`. |
| `model_cutoffs` | Proposed lookup table | `model_id`, `cutoff_date`, `source`, `confidence`; `unknown` is a legal value for L1. |

The shared event envelope is:

`{event_id, event_type, run_id, commit_hash, stage, turn_index, ts, model_id, prompt_sha256, payload}`

`stage` uses the Stage 1-Stage 6 labels above; `turn_index` is null for deterministic stages. Full prompt text is retained for every S5 invocation and for a 10% sample of S2-S4 invocations; L3 audits only the retained set and reports its coverage. Otherwise store `prompt_sha256` and token counts. Raw diagnostics, patches, and transcripts are retained per run because they are the evidence for O, T, F, and R metrics. A single append-only table is preferable to adding a column per metric: it keeps `step_trajectories.metrics` readable and lets a new metric be added without a migration.

Two accounting rules prevent silent bias. First, every denominator must exclude runs invalidated by a harness failure (I4) and report the excluded count; turn-budget exhaustion is not a harness failure and stays in the denominator as a censored outcome. Second, no metric is ever aggregated across `custom_qualifier_hierarchy` and `builtin_checker` without stratification, because their baseline error profiles differ sharply (T1).

#### 3.4.1 Prerequisites that go beyond logging

Most entries are pure joins over artifacts the pipeline will already write. The following prerequisites are larger; each is attached to the metric that needs it, and none should be discovered midway through data collection.

| Prerequisite | Metrics | What must exist | Fallback if not built |
| :--- | :--- | :--- | :--- |
| Binary triage output | D2, D3, F1 (`T-MISCLASS`) | Step 2 emits the binary `is_bugfix` label; optional `preventability_label`/`bug_class` are analysis-only extensions | D2 reports binary accuracy/calibration; D3 reports `observed_preventable` only |
| Baseline-arm configuration | E1, E8, C2, S2 | `baseline_arm` plus separate runner invocations; no stage logic change | Run `fixer_no_types` first, the cheapest control |
| Pre-placement Checker baseline | P4, T1, T2, T3, S6, E3 | One extra Checker invocation after S3 packages `custom-quals.jar` and before S4 places annotations | File-level localization; raw error counts |
| Offline Step 6b pass | E3 | A fresh worktree seeded from S4 output and a Checker run over the ported human patch | Record `patch_conflict` and mark E3 blocked |
| Conformance flag | P2 | Renderer writes `conformance_failed`; aborting on it is an explicit run-exclusion policy | Keep the flag non-fatal and exclude flagged runs in analysis |
| Missing guardrail detectors | F3, I6 | New `AstGuard`/build-guard checks for the unimplemented vectors | Report `vector_coverage` and profile only the implemented subset |
| Gold labels and human review | D2, H1-H4 | The `triage_gold_label` and `human_ratings` tables plus a written protocol | Directional agreement only; state the descope |
| Prompt variants and model swaps | S1, S2, S3 | Extra runs only; prompts and model keys already exist | Fixer-template paraphrase and `model.fixer` swap first |

The first row is the only one that changes an LLM output schema. If it is descoped, D2 and D3 remain useful as binary accuracy and observed-preventability profiles; any optional multi-class labels are analysis-only and must be reported as not collected rather than approximated from `triage_metadata`.

#### 3.4.2 Where the hooks live

The repository has no code yet, so hook names are specified against the planned components in `implementation_plan.md` rather than against classes that exist.

| Stage | Hook point | Primary events |
| :--- | :--- | :--- |
| S1/S2 | Ingestion and triage wrappers; `S2Triage` | `triage_funnel_event`, `test_portability_probe`, `triage_gold_label` |
| S3 | Synthesis wrapper and jar packager | `prompt_rendered`, `lattice_summary`, `helper_defined`, `placement_guide_emitted` |
| S4 | Application wrapper and `AstGuard` | `ast_gate_result`, `annotation_placements`, `checker_after`, `placement_guide_applied` |
| S5 | `OpenCodeRunner`, post-turn validator, build wrapper | `agent_action`, `error_set_snapshot`, `diagnostics_delivered`, `checker_after` |
| S6 | Verifier and test runner | `test_outcomes`, `eval.arm_completed`, `run.finished` |
| Offline | `analysis/` scripts and the Step 6b pass | `verify.ground_truth_fix`, rollups, resampling and sensitivity artifacts |

This table is an ownership map, not a schedule: a hook can be added at the wrapper boundary even when the stage implementation is incomplete, which is why the event envelope is deliberately stage-agnostic.

### 3.5 Coverage map

| Required area | Section | Metrics | Representative question |
| :--- | :--- | :--- | :--- |
| Dataset and corpus construction, sampling | 4 | D1-D5 | Which filters produce the benchmark, and what survives? |
| Input representation and prompt construction | 5 | P1-P5 | What does the model actually see, and does it respect isolation? |
| LLM inference behavior | 6 | I1-I6 | How do agents spend turns, and do they converge? |
| Output parsing and post-processing | 7 | O1-O5 | Do outputs become valid, local patches? |
| Static analysis and type validation | 8 | T1-T5 | Are the Checker errors new, localized, and meaningful? |
| Evaluation and scoring | 9 | E1-E8 | Is success causal, partial, faithful, and affordable? |
| Cost, latency, and resources | 10 | C1-C4 | What does a cured bug cost in time and money? |
| Reproducibility, provenance, determinism | 11 | R1-R4 | Can a run be identified, replayed, and audited? |
| Failure modes and error taxonomy | 12 | F1-F4 | What fails, in what order, and why? |
| Robustness and sensitivity | 13 | S1-S6 | Would the result survive a different prompt, model, or seed? |
| Contamination and leakage | 14 | L1-L4 | Did the model already know the fix, or see the bug? |
| Human evaluation | 15 | H1-H4 | Do humans agree, accept the types, and find them burdensome? |

### 3.6 Summary index of all proposed metrics

Table 3.1 is the at-a-glance inventory. The ID is the anchor used throughout the document; the question is the decision the metric is meant to inform.

**Table 3.1. Summary index of all proposed metrics.**

| ID | Metric | Stage | Priority | Effort | Question it answers |
| :--- | :--- | :--- | :--- | :--- | :--- |
| [D1](#d1) | Triage funnel and attrition decomposition | S2 | Must-have | Low | Where in the S1/S2 gauntlet are commits lost, and which loss dominates? |
| [D2](#d2) | Commit-classifier accuracy and calibration | S2 | Must-have | High | How accurate and calibrated is the LLM bugfix classifier? |
| [D3](#d3) | Bug-preventability and bug-class distribution | S2, S4 | Should-have | Medium | Which bug classes are actually preventable by synthesized types? |
| [D4](#d4) | Corpus representativeness and selection profile | S1, S2 | Should-have | Low | Is the verified sample representative of the commits that were not verified? |
| [D5](#d5) | Test-portability and baseline-build loss profile | S1, S2 | Must-have | Medium | Why do test patches and baseline builds fail, and how much yield could adaptation recover? |
| [P1](#p1) | Prompt context composition and budget | S2-S5 | Must-have | Low | What information and token budget does each prompt actually contain? |
| [P2](#p2) | Prompt-template identity and conformance | S2-S5 | Should-have | Low | Was the intended template rendered, and did it respect isolation? |
| [P3](#p3) | Declarative placement-guide rule structure | S3 | Must-have | Low | What is the rule volume and categorical distribution of the high-level `placement_guide.md`? |
| [P4](#p4) | Diagnostic delivery and truncation profile | S5 | Must-have | Low | What diagnostic signal reaches the blind fixer, and how much is truncated or pre-existing? |
| [P5](#p5) | Context sufficiency for target scope | S4, S5 | Should-have | Medium | Does the prompt contain the source and API context needed for the buggy scope? |
| [I1](#i1) | Action and tool-use trajectory taxonomy | S3, S4, S5 | Should-have | Low | What do agents spend their turns doing? |
| [I2](#i2) | Marginal progress and error-set dynamics | S5 (and S3) | Must-have | Low | Is the Checker error set converging, plateauing, or oscillating? |
| [I3](#i3) | Self-correction and thrash rate | S3, S4, S5 | Should-have | Medium | How much of the trajectory is unproductive repetition or thrash? |
| [I4](#i4) | Harness reliability and degenerate-output rate | S3/S4/S5 (S2 partial) | Must-have | Low | How often do harness failures and degenerate outputs contaminate runs? |
| [I5](#i5) | Agent verbosity and token efficiency | S3, S4, S5 | Should-have | Low | How many tokens does each fixed error or success cost? |
| [I6](#i6) | Instruction-adherence rate | S3, S4, S5 | Should-have | Medium | Do agents follow prompt rules when no guard enforces them? |
| [O1](#o1) | Artifact validity and parse-success rate | S3, S4, S5 | Must-have | Low | Do agent outputs parse, apply, and compile? |
| [O2](#o2) | Patch shape and edit locality | S4, S5 | Should-have | Low | How large and local are the patches agents produce? |
| [O3](#o3) | AST/canonicalization fidelity and normalization loss | S4 | Must-have | Medium | Does the AST gate accept valid annotation edits and reject executable ones? |
| [O4](#o4) | Helper API surface and utilization | S3, S5 | Should-have | Medium | Are synthesized helpers actually used, and what API surface do they add? |
| [O5](#o5) | Rejection-feedback repair efficacy | S4, S5 | Should-have | Medium | Does rejection feedback produce a corrected next attempt? |
| [T1](#t1) | Checker baseline delta | S4, S5 | Must-have | Low | How many Checker errors are pre-existing versus induced by the new types? |
| [T2](#t2) | Diagnostic taxonomy and attribution | S4, S5 | Must-have | Medium | What kinds of diagnostics arise, and from which annotation or checker? |
| [T3](#t3) | Error localization and signal-to-noise ratio | S4 (feeds S5) | Must-have | Medium | Do type errors localize to the buggy method or scatter into unrelated code? |
| [T4](#t4) | Lattice structure, canary and vacuity | S3, S4 | Must-have | Medium | Is the lattice structurally meaningful or vacuous, and does it pass the canary? |
| [T5](#t5) | Annotation coverage and agreement with ground truth | S4 | Should-have | High | Are annotations complete and semantically aligned with the human fix? |
| [E1](#e1) | Baseline lift and causal attribution | S5, S6 | Must-have | Medium | Do synthesized types causally improve repair over no-types and built-in-only baselines? |
| [E2](#e2) | Outcome vector and partial credit | S6 | Should-have | Low | Which partial outcomes do runs reach before failing? |
| [E3](#e3) | Ground-truth-fix acceptance (Step 6b) | S6b | Must-have | Medium | Would the synthesized lattice accept the historical human fix? |
| [E4](#e4) | Fix fidelity versus human patch | S6 | Should-have | Medium | Does the blind fixer converge on the same repair as the human? |
| [E5](#e5) | Collateral regression breadth | S6 | Should-have | Low | How much collateral damage does a repair cause? |
| [E6](#e6) | Difficulty stratification and success predictors | S2, S4, S6 | Should-have | Medium | Which bugs are hard, and what predicts success? |
| [E7](#e7) | Developer-burden proxies | S3/S4 | Must-have | Low | How much annotation and helper burden does the approach impose? |
| [E8](#e8) | Non-model configuration-ablation effect sizes | S1-S6 | Should-have | High | Which non-model configuration knobs change outcomes or cost? |
| [C1](#c1) | Stage latency decomposition | S1-S6 | Must-have | Low | Which stage dominates wall-clock latency? |
| [C2](#c2) | Token and monetary cost per outcome | S1-S6 | Must-have | Low | What does a verified fix cost in tokens and dollars? |
| [C3](#c3) | Compute and resource footprint | S1-S6 | Nice-to-have | Medium | What CPU, memory, and disk footprint does a run require? |
| [C4](#c4) | Retry and waste amplification | S3, S4, S5 | Should-have | Low | How much work and spend is wasted on retries, rejections, and abandoned runs? |
| [R1](#r1) | Provenance-manifest completeness | S1, S6 | Must-have | Low | Can a run be identified and reconstructed from its manifest? |
| [R2](#r2) | Replay determinism | S3, S5, S6 | Should-have | Medium | Do identical manifests produce identical verdicts and patches? |
| [R3](#r3) | Environment and model drift | S1-S6 | Should-have | Low | Did the model or toolchain change during the study? |
| [R4](#r4) | Artifact retention and third-party replayability | S6 | Must-have | Low | Are the artifacts needed for third-party replay retained? |
| [F1](#f1) | Unified multi-label failure taxonomy | S1-S6 | Must-have | Medium | What are the failure modes across all six stages, in one vocabulary? |
| [F2](#f2) | Failure cascade and co-occurrence | S1-S6 | Should-have | Low | Which failures cascade into which downstream failures? |
| [F3](#f3) | Guardrail-attempt and evasion profile | S5 | Must-have | Medium | Which of the 19 cheat vectors do agents attempt, and which escape? |
| [F4](#f4) | Budget and timeout exhaustion profile | S3-S5 | Must-have | Low | Are turn budgets truncating converging runs or wasting turns? |
| [S1](#s1) | Prompt-paraphrase sensitivity | S2-S5 | Should-have | Medium | How much do verdicts depend on prompt wording? |
| [S2](#s2) | Model and stage sensitivity | S2-S5 | Should-have | High | How much do verdicts depend on the model used at each stage? |
| [S3](#s3) | Seed and temperature sensitivity (pass@k) | S3-S5 | Should-have | Medium | How much do verdicts depend on seed, temperature, and luck? |
| [S4](#s4) | Resampling and leave-one-repo-out sensitivity | S1-S6 | Must-have | Low | How fragile are headline rates to resampling and repo composition? |
| [S5](#s5) | Annotation-perturbation brittleness | S4-S6 | Nice-to-have | High | How brittle is success to small annotation perturbations? |
| [S6](#s6) | Threshold and budget sensitivity | S2-S5 | Should-have | Low | How sensitive are rates to thresholds and turn caps? |
| [L1](#l1) | Temporal contamination and knowledge-cutoff exposure | S2 | Must-have | Low | How many benchmark fixes predate the model training cutoff? |
| [L2](#l2) | Benchmark memorization probe | S2 | Should-have | Medium | Can the model recall the human fix from the buggy code alone? |
| [L3](#l3) | Leakage-channel audit | S3-S5 | Must-have | Medium | Which channels leak bug information to the nominally blind fixer? |
| [L4](#l4) | Cross-run and shared-state contamination | S1 | Should-have | Low | Does state leak across runs through shared jars, caches, or sessions? |
| [H1](#h1) | Human triage and preventability agreement | S2 | Should-have | High | Do human raters agree on bugfix and preventability labels? |
| [H2](#h2) | Expert annotation-quality rubric | S3-S4 | Nice-to-have | High | Are the synthesized qualifiers and placements semantically good? |
| [H3](#h3) | Patch acceptability review | S6 | Should-have | High | Would a reviewer accept the blind fixer's patch? |
| [H4](#h4) | Burden and review-time study | S4-S6 | Nice-to-have | High | How much human review time and intervention does an annotation set require? |

---
## 4. Dataset and corpus construction

The benchmark this project builds is retrospective: every sample is a triple `(C, C-1, T_reg)` carved out of
real history by the S1/S2 gauntlet, so the corpus is not given but produced by a sequence of lossy filters
(build rot, commit classification, test extraction, the failure invariant). Corpus-level measurement therefore
precedes any reading of `eval.verdict`, because `full_success` is conditional on surviving Step 2 and a yield
collapse or a skewed surviving set changes what every downstream number means (critique D-4). The five metrics
below instrument the funnel itself, the accuracy of the gate that feeds it, the composition and
representativeness of what survives, and the preventability profile of the bugs that remain. All are specified
pre-implementation, since the repository currently holds no pipeline code and `data/pipeline.db` will be created
empty.

<a id="d1"></a>
### D1. Triage funnel and attrition decomposition
**Priority:** Must-have · **Effort:** Low · **Stage:** S2 · **Relation:** extends `triage.yield_rate`

- **Definition.** Per-commit stage counts for the S1/S2 gauntlet, reported as `funnel = (N_scanned, N_build_ok, N_bugfix, N_test_found, N_test_compiles, N_verified)` with conditional retention
  `r_i = N_i / N_prev` in units of commits scanned at `limits.commit_depth` (30). Captured at the last stage each commit survives, so `N_verified` sizes the `(C, C-1, T_reg)` benchmark population
  and each `r_i` isolates one loss mechanism rather than a blended rate.
- **Instrumentation.** Emit `triage_funnel_event` from S1 and S2 with fields `commit_hash`, `repo_id`, `stage_reached`, `discard_reason` (`no_test_found`, `test_compilation_failure`,
  `test_passes_unpatched`, `build_broken_unpatched`), `author_date`, `elapsed_ms`; the same write updates `commits.triage_status` and `triage_metadata` for that commit. A closing
  `triage_funnel_summary` per depth scan stores per-stage counts and `r_i` in `step_trajectories.metrics` for `step_name = 'triage'`, ready for the Pandas/Datasette layer over `data/pipeline.db`.
- **Justification.** `triage.yield_rate` collapses the whole gauntlet into one scalar, so when yield drops you cannot tell whether S1 build rot, an over-strict Step 2 classifier, or
  non-discriminating tests (`test_passes_unpatched`) is responsible. The decision whether to promote design §7.5 test adaptation behind `step2.test_adaptation`, widen `limits.commit_depth`, or
  fix the invariant first is uninformative without per-stage retention.
- **Expected learnings.** (h1) critique D-4 predicts `test_compilation_failure` is the dominant loss, so a per-repo table of `r_i` should show its steepest drop at the compile stage; (h2)
  `build_broken_unpatched` concentrates in the oldest `author_date` terciles, implying multi-JDK support (design §7.10) would recover them. A surprising outcome would be `test_passes_unpatched`
  dominating instead: then the failure invariant itself, not compilation, is the bottleneck and adversarial test synthesis (§7.6) matters more than test adaptation. Stacked bars of
  `discard_reason` by repo and age tercile would show both patterns.
- **Reporting.** Report per-repo funnel bars plus `r_i` with bootstrap CIs clustered by commit and repo; compare discard-reason mixes across repos with chi-square and against a second depth
  window as a stability check. Threat to validity: `triage_status` is single-label, so first-failure censoring hides later-stage losses for commits that would also have failed a downstream stage.

<a id="d2"></a>
### D2. Commit-classifier accuracy and calibration
**Priority:** Must-have · **Effort:** High · **Stage:** S2 · **Relation:** extends `triage.classification_counts`

- **Definition.** Accuracy of the Step 2 one-shot classifier against a stratified gold standard. The binary `bugfix` vs `non_bugfix` decision is the only prediction the pipeline emits: precision, recall, and F1 for that decision, plus a binary confusion table. Optional multi-class labels (for example feature/refactor/chore/mixed) are a future analysis-only extension and are not part of the pipeline contract; if such labels are collected for analysis, they must be reported separately from the binary verdict and must not be used to populate `triage.classification_counts`. Calibration of `bugfix_score` in `[0.0, 1.0]` uses expected calibration error over five bins, which is all 40-60 labels can support. Sampled as a stratified subsample of scanned commits per repo and weighted back by inclusion probability in every reported rate.
- **Instrumentation.** Predictions are planned to land in `commits.is_bugfix`, `bugfix_score`, and `triage_metadata`; add `triage_gold_label` rows keyed by `commit_hash` with `gold_label`, `preventability_label`, `bug_class`, `annotator_id`, `adjudicated`, `sample_stratum`, and `inclusion_prob`. Two annotators label a stratified sample of 40-60 commits and report Cohen's kappa before adjudication; H1 reuses this sample rather than drawing a second one. If the budget forces a smaller sample, mark D2 incomplete and report it as directional rather than silently dropping the labels. A script in
  `analysis/` joins predictions to labels, computes P/R/F1, the confusion table and ECE, and writes `triage_accuracy` metrics JSON onto the triage `step_trajectories` row.
- **Justification.** Step 2 is the entry gate for the whole benchmark: a false negative silently removes a valid `(C, C-1, T_reg)` sample, while a false positive injects a refactor whose
  `T_reg` is meaningless, and both bias every downstream verdict. Until labels exist, `triage.bugfix_threshold` (design §7.4) cannot be tuned and `triage.classification_counts` is
  uninterpretable as a quality signal.
- **Expected learnings.** (h1) the binary classifier's false negatives concentrate in commits that mix a bugfix with feature/refactor hunks, so real bugs are lost that
  continuous scoring with `triage.bugfix_threshold` would keep; (h2) a one-shot prompt is over-confident, giving a reliability curve well above the diagonal near scores 0 and 1. A
  surprising result would be high recall but poor calibration: then scores rank commits fine but thresholds must be set per repo. Outputs: binary confusion table and reliability diagram.
- **Reporting.** Report P/R/F1 with bootstrap CIs clustered by repo and the reliability diagram with ECE. If an optional multi-class gold set is later collected, report it as a separate analysis-only table; do not compare it to the binary pipeline output as if it were an alternative pipeline label. Threat: gold labels come from the project team, who know the hypothesis, so publish Cohen's kappa, adjudicate disagreements, and treat any label set under ~100 commits as
  directional only.

<a id="d3"></a>
### D3. Bug-preventability and bug-class distribution
**Priority:** Should-have · **Effort:** Medium · **Stage:** S2/S4 · **Relation:** new

- **Definition.** For each verified sample, a bug class drawn from the proposal §C.1 heuristic (id/units conflation, unsanitized input, range or refinement, other) plus preventability labels:
  `observed_preventable`, true when S4 yields a non-trivial lattice (`application.is_trivial == false`) with at least one Checker Framework error located
  in tier 1 or tier 2 of design §7.3 against `C`'s diff hunks. A `predicted_preventable` label is reported only if the Step 2 output is extended to emit one (Section 3.4.1); until then the predicted-versus-observed
  agreement analysis is marked blocked rather than approximated. Reported as class shares; `observed_preventable` is an analysis label, never a corpus-inclusion filter, because it is assigned after synthesis.
- **Instrumentation.** Extend `commits.triage_metadata` with `bug_class` and, when available, `predicted_preventable` at S2 (this requires the Step 2 schema extension in Section 3.4.1). Emit `preventability_verdict` once after S4 with `commit_hash`, `observed_preventable`,
  `min_error_tier`, `qualifier_count`, `synthesis.checker_category`, joined to `evaluation_runs` on `commit_hash` for that run. Tier assignment reuses the diff-hunk-to-AST-scope mapping of design §7.3;
  manual bug-class labels on a stratified subsample stay optional/scope-limited and are stored in `triage_gold_label` beside the D2 rows.
- **Justification.** Critique X-2 records that the proposal's preventability classification was silently dropped, so today a wasted synthesis on an infeasible bug is indistinguishable from a Step 3 model
  failure and `application.is_trivial` cannot separate the two. The class distribution also decides whether the README's domain-specific prevention claim can be reported per class or only as an aggregate
  rate.
- **Expected learnings.** (h1) type-conflation bugs show the highest `observed_preventable` rate, matching the proposal's heuristic, which names conflation first; (h2, only if `predicted_preventable` is collected) the prediction
  over-predicts, so the agreement table is asymmetric with false prevents dominating. A surprising pattern would be `observed_preventable == 0` for an entire class despite successful synthesis: that
  implicates `synthesis.freedom_level` rather than the bug, and would justify descoping that class or moving to Level 3 wrapper records (design §7.2). Example: 4-by-2 table of class by observed label, split by
  `synthesis.checker_category`.
- **Reporting.** Report per-class preventability with Wilson intervals and a bootstrap clustered by commit; test predicted-versus-observed agreement with McNemar and report Cohen's kappa only when both labels exist (otherwise mark that analysis blocked). Threat: `observed_preventable` is mediated by synthesis and placement quality, so a low rate confounds inherent infeasibility with an underpowered Step 3 that hit its `agent.steps.synthesis` cap.

<a id="d4"></a>
### D4. Corpus representativeness and selection profile
**Priority:** Should-have · **Effort:** Low · **Stage:** S1/S2 · **Relation:** extends `triage.commit_metadata`

- **Definition.** Covariate profile of the verified benchmark population versus the commits that were scanned but not verified: `author_date` age, repo, diff size in lines and files, tests
  added, module count, and JDK/build target, summarized as standardized mean differences (SMD) between `triage_status = 'verified'` and `triage_status != 'verified'`. The non-verified group is the complement, not "all scanned," because verified commits are a subset of scanned and overlapping groups dilute the SMD. Also records how much
  history `limits.commit_depth` (30) can see at all, since the last-30 window is itself a selection device.
- **Instrumentation.** One `corpus_profile_event` emitted per scanned commit during S1/S2 carrying `commit_hash`, `repo_id`, `author_date`, `diff_lines`, `files_touched`,
  `tests_added`, `jdk_target`, `stage_reached`, `triage_status`; diff stats come from `git show --numstat` at ingestion, the rest parsed from `commits.triage_metadata`. Expose the
  aggregate as a SQL view over `data/pipeline.db` so Datasette can render it per repo and per status; the whole pass adds no LLM calls.
- **Justification.** Only `synthetic-id-swap` and `cmsx` are planned targets and P-5 questions the target-selection criteria and X-8 warns about scope, so neither supports generalization from them; without a profile you cannot tell whether
  `eval.verdict` distributions reflect the approach or a corpus of small, recent, test-heavy commits that happened to survive Step 2. This metric decides whether headline numbers
  may be pooled across repos or must be repo-conditioned.
- **Expected learnings.** (h1) verified commits skew toward smaller diffs and more added tests than discarded ones, i.e. selection on testability rather than on bug difficulty; (h2)
  age skew is strong because `build_broken_unpatched` and `test_compilation_failure` hit old commits. A surprising outcome would be negligible size skew but a dominant repo effect,
  which would make pooling `synthetic-id-swap` with `cmsx` in one headline table invalid. Outputs: SMD table verified versus scanned, and status stacked bars by age tercile.
- **Reporting.** Report per-repo covariate tables with bootstrap CIs clustered by commit, Mann-Whitney tests for count covariates and chi-square for categorical ones, plus SMD
  against the conventional 0.1 balance threshold. Threat: covariates are missing for commits triage never reached (missing not at random), and the synthetic repo can dominate
  counts, so always publish per-repo profiles beside pooled ones.

<a id="d5"></a>
### D5. Test-portability and baseline-build loss profile
**Priority:** Must-have · **Effort:** Medium · **Stage:** S1/S2 · **Relation:** extends `triage.discard_reasons`

- **Definition.** A cause-coded decomposition of the two build-side discard reasons, which the funnel cannot separate on its own: each `test_compilation_failure` classified as `missing_symbol` (the test
  calls APIs introduced in `C`), `signature_change`, `build_config`, or `other`, and each `build_broken_unpatched` coded by JDK mismatch, Gradle plugin, or dependency failure. Reports `portable_share =
  N_missing_symbol / N_compile_fail` and a projected `yield_with_adaptation = (N_verified + N_missing_symbol) / N_scanned` per repo, the yield available if test adaptation were enabled.
- **Instrumentation.** Per discarded commit emit `test_portability_probe` with `commit_hash`, `discard_reason`, `compile_error_class`, `missing_symbols` (top 5), `jdk_target`, `gradle_exit_code`,
  `adaptation_candidate BOOLEAN`; inputs are the `javac` logs the planned registry retains for `discard.test_compilation_failure` plus Step 1 build output, coded by deterministic pattern match with an
  optional one-shot LLM tiebreak. Rows sit beside `commits.triage_status` and roll up into `triage_portability_summary`.
- **Justification.** Critique D-4 notes the design concedes test patches often fail to compile on `C-1` yet discards instead of adapting (design §7.5), so yield may be below usability with no visibility
  into why. This metric makes yield a managed quantity and prices the decision to promote §7.5 behind a `step2.test_adaptation` toggle, or to prefer multi-JDK support (§7.10) if build rot turns out to
  dominate.
- **Expected learnings.** (h1) most compile failures are `missing_symbol`, i.e. tests referencing helpers added in `C`, so the §7.5 adaptation sketch would recover a large share of currently discarded
  commits; (h2) `build_broken_unpatched` clusters at JDK transitions in old commits. A surprising split, with build drift rather than API drift dominating, would invert the priority: LLM test adaptation
  buys little and Step 1 JDK management is the real lever. Output: stacked bar of `compile_error_class` by repo with projected yield overlaid.
- **Reporting.** Report per-repo class shares and projected `yield_with_adaptation` with bootstrap CIs clustered by commit, alongside observed `triage.yield_rate` so the gap is explicit. Threat:
  `compile_error_class` is heuristic coding of compiler logs, so audit a sample with two coders (Cohen's kappa); projected yield is an upper bound because an adapted test may silently weaken its
  assertions.

### Section 4 instrumentation notes

Every Section 4 event carries `run_id` (or the depth-scan identifier), `commit_hash`, `repo_id`, `author_date`,
`stage_reached`, `triage_status`, `commit_depth`, and the `config_snapshot` hash from `evaluation_runs`, so D1-D5 rows join to
later stages on `commit_hash` without name matching. All are emitted once per commit, or once per commit per stage, by S1/S2
code, and are stored either as columns on `commits` or as metrics JSON on the `step_trajectories` row where the existing
triage metrics live. Scan start time belongs on the summary event too, because a funnel measured over two different windows
is not comparable.

## 5. Input representation and prompt construction

Steps 2 to 5 each make LLM calls whose entire effect on the outcome passes through the rendered prompt:
`src/main/resources/templates/prompts/{triage,synthesis,application,fixer}.md` plus stage artifacts (diff of `C`,
`placement_guide.md`, the Checker Framework diagnostic log, guardrail instructions). Prompt construction is also where the
blindness claim lives or dies, since critique D-2 shows that Step 3's inputs and outputs leak the bug's location into what the
blind fixer eventually sees. Measuring input representation first turns these design promises into per-invocation quantities,
so later sections on inference behavior and outcomes can be conditioned on what the model was actually given rather than on
what the templates are supposed to contain. The five metrics below are deterministic, emitted by the prompt assembler, and
require no additional model calls.

<a id="p1"></a>
### P1. Prompt context composition and budget
**Priority:** Must-have · **Effort:** Low · **Stage:** S2-S5 · **Relation:** new

- **Definition.** For every LLM invocation in S2-S5, the segment composition of the rendered prompt: each injected block actually present at that invocation, with `chars` and `tokens`, plus `total_input_tokens` and the configured model
  context window. The eligible blocks differ by stage: S2 sees the commit diff, commit message, and PR body; S3 sees the bug and PR description; S4 sees the source and `placement_guide.md`; S5 sees annotated source, Checker diagnostics, and instructions, and must show zero diff, PR, commit-message, and `T_reg` tokens. Reported as `budget_share(seg) = tokens(seg) / total_input_tokens` and `context_fill = total_input_tokens / context_window`, per invocation and per turn.
- **Instrumentation.** Prompt assemblers in S2-S5 emit `prompt_rendered` with `run_id`, `step_name`, `turn_index`, `template_id`, `template_sha256`, `segments` as a JSON
  array of `{name, chars, tokens, present}`, `forbidden_segments_present`, `total_input_tokens`, `context_window`, `truncated BOOLEAN`. Segment counts come from the tokenizer of `model.<stage>`; raw prompt
  text is kept as a hash plus a bounded excerpt for S2-S4 and in full for S5, while counts fold into `step_trajectories.metrics` next to `triage.token_usage` and `eval.token_usage`.
- **Justification.** Existing cost metrics report totals but not what is being paid for. Segment budgets also test the isolation contract rather than assuming it: `forbidden_segments_present` should be empty for S5, and a
  non-zero value is a P2/L3 finding, not a budget fact. In S3 and S4 the diff or PR text can dominate and shape the guide and annotation placement, which is critique D-2's indirect leakage channel into what the blind fixer later sees. The metric also settles whether the `agent.steps.fixer` budget
  (15) is being spent re-shipping unchanged context on every turn.
- **Expected learnings.** (h1) in S5 the diagnostics segment leads early turns and shrinks monotonically as errors resolve; (h2) in S2 the diff dominates and the PR body
  is marginal. A single segment above roughly 70% of the prompt across runs would mark a cheap first ablation: drop that segment and re-run, reusing E1's arm
  machinery. Output: stacked area chart of segment share against turn index, faceted by `step_name`.
- **Reporting.** Report per-stage median and IQR segment shares across commits, and a Spearman correlation between `total_input_tokens` and `fixer.turns_taken`. Threat:
  token counts depend on the tokenizer of `model.<stage>`, so cross-model comparisons must re-tokenize rather than reuse char proxies, and content arriving from tool
  output rather than the assembler is easy to mis-segment.

<a id="p2"></a>
### P2. Prompt-template identity and conformance
**Priority:** Should-have · **Effort:** Low · **Stage:** S2-S5 · **Relation:** new

- **Definition.** Identity of the exact template rendered per invocation: file (`triage.md`, `synthesis.md`, `application.md`, `fixer.md`), content hash of the template and of
  the fully rendered prompt, plus a conformance verdict from machine checks over that string: required clauses present (the fixer's no-`@SuppressWarnings` and method-body-only
  rules) and forbidden content absent (PR text, issue keys, `T_reg` source, diff of `C`). Unit: one verdict per LLM call.
- **Instrumentation.** Emit `prompt_conformance` from the renderer with `template_id`, `template_sha256`, `rendered_sha256`, `required_clauses` as a list of `{clause, pass}`,
  `forbidden_hits`, `conformant`, `pipeline_git_rev`; failures write `step_trajectories.metrics.conformance_failed` instead of aborting the run. Treating that flag as a pre-flight abort is an explicit
  run-exclusion policy (Section 3.4.1), not a silent control-flow change, so the headline denominator can be defined either way. Every template is snapshotted into the run's `config_snapshot`, so `evaluation_runs` pins prompt text alongside `guardrails.mode` and `model.*`.
- **Justification.** Critiques D-2 and X-3 show the blindness promise and the artifact names living in prose that already drifts between documents, so a template edit between two
  runs silently confounds any outcome difference with model sensitivity. Conformance converts the Step 5 contract `Fixer DOES NOT receive: PR description` from a documentation
  claim into a per-invocation assertion that fails loudly.
- **Expected learnings.** (h1) at least one `fixer.md` conformance failure appears before the template stabilizes, typically a placeholder that pulls `pr_body` into the render;
  (h2) template hash explains verdict variance within a pilot more than `model.*` does. A surprising zero-failure history would mean replay can trust hashes alone and needs no
  prompt archive. Output: table of `template_sha256` by `config_snapshot` cell with `eval.verdict` distributions, flagging mixed-hash comparisons as confounded.
- **Reporting.** Report template-hash by config cross-tabs and restrict verdict comparisons to single-hash strata; where pooling across hashes is unavoidable, state it as a
  confounder. Threat: textual identity says nothing about the artifacts injected around the template, and the required-clause list is hand-maintained, so a new leakage channel
  persists as long as it is not listed.

<a id="p3"></a>
### P3. Declarative placement-guide rule structure
**Priority:** Must-have · **Effort:** Low · **Stage:** S3 · **Relation:** replacement

- **Definition.** Properties of `placement_guide.md` measuring its declarative rule structure: `policy_rule_count` (total count of discrete declarative rules parsed across standardized schema sections), categorical distribution `rules_by_category` ($N_{\text{def}}, N_{\text{src}}, N_{\text{snk}}, N_{\text{san}}$), and token volume `guide_tokens`. Guides are required to define high-level typing invariants across four standardized categories (Defaulting Invariants, Source Rules / Introducers, Sink Rules / Consumers, and Sanitizers & Coercions) without naming concrete repository symbols (classes, methods, variables). Advanced automated token-AST anti-leak scoring and global syntactic AST-conformance matching are deferred to the future research agenda (`docs/additional_future_improvements.md` §T3/§T5).
- **Instrumentation.** Emit `placement_guide_emitted` after S3 with `run_id`, `guide_sha256`, `policy_rule_count`, `rules_by_category` (counts for defaults, sources, sinks, sanitizers), `qualifiers_listed`, and `guide_tokens`; a deterministic Markdown section parser extracts bullet counts per category header. Recorded in `step_trajectories.metrics` for step 3.
- **Justification.** Critique D-2 notes that symbol-specific punch-lists leak the bug location to downstream stages and degrade type synthesis to ad-hoc patching. Structuring placement guides into declarative policies ensures the synthesized lattice expresses a general type discipline. Tracking `policy_rule_count` and its categorical distribution ensures that synthesizers emit actionable, non-empty policies with both baseline assumptions and boundary rules.
- **Expected learnings.** (h1) synthesizers frequently emit default and source rules, but omit sink constraints unless explicitly prompted; (h2) rule count correlates with non-triviality in S4 without requiring symbol-level micro-directives. Output: rule count distributions across synthesis configurations and checker categories.
- **Reporting.** Report `policy_rule_count` and category distributions per run, grouped by `synthesis.checker_category` and `synthesis.allowed_checkers`.

<a id="p4"></a>
### P4. Diagnostic delivery and truncation profile
**Priority:** Must-have · **Effort:** Low · **Stage:** S5 · **Relation:** new

- **Definition.** Per fixer turn, the Checker Framework diagnostic payload actually delivered in the Step 5 prompt, as opposed to what the build emitted: `errors_emitted` by the
  build, `errors_delivered` after parsing and dedup, `delivery_ratio = errors_delivered / errors_emitted`, payload `tokens` in prompt units, `truncated` with reason (`bytes`,
  `parse`, `warnings_masked`), and the split between pre-existing baseline diagnostics on clean `C-1` (critique D-22) and diagnostics introduced by the new annotations.
- **Instrumentation.** Emit `diagnostics_delivered` each S5 turn with `run_id`, `turn_index`, `errors_emitted`, `errors_delivered`, `dedup_dropped`, `truncated`,
  `payload_tokens`, `files_covered`, `preexisting_share`, `parse_failures`; store the payload hash with the full text in `step_trajectories.compiler_diagnostics`. The
  clean-`C-1` snapshot is recorded once per commit immediately after S3 packages `custom-quals.jar` and before S4 placement, using the same processor and `-Aquals` set as later passes; every S5 turn reuses that baseline partition without re-running the Checker on clean code.
- **Justification.** The diagnostic log is the blind fixer's primary error signal, though it also receives the annotated source and instructions (design §3 Step 5), so truncation or baseline noise changes what `fixer_incomplete` measures: prompt
  plumbing versus the hypothesis. If many delivered errors predate the annotations, `fixer.remaining_type_errors == 0` is unreachable for reasons unrelated to the types, which
  is exactly critique D-22's objection.
- **Expected learnings.** (h1) the payload shrinks monotonically across turns as errors resolve, giving an interpretable difficulty curve; (h2) with `synthesis.allowed_checkers:
  'all'`, `preexisting_share` is substantial on legacy code, so delivered errors outside the buggy scope dominate early turns. A surprising result is truncation firing on early
  turns even on the small synthetic repo, which would force payload summarization before any ablation is credible. Output: emitted-versus-delivered line plot per turn.
- **Reporting.** Report median and IQR delivery curves per config cell, truncation occurrence against `eval.verdict` with McNemar, and `preexisting_share` against
  `fixer.remaining_type_errors` via Spearman, with CIs clustered by commit. Threat: emitted counts depend on Checker Framework version and flags (drift like R3), and the
  baseline snapshot assumes `C-1` builds cleanly under the same flags.

<a id="p5"></a>
### P5. Context sufficiency for target scope
**Priority:** Should-have · **Effort:** Medium · **Stage:** S4/S5 · **Relation:** new

- **Definition.** Whether a rendered prompt contains the material the agent needs to act on the buggy scope: `scope_recall = files_touched_by_C inlined / files_touched_by_C`
  per invocation, plus presence booleans for the enclosing method source of each diff hunk and for the `custom-quals.jar` API surface (qualifier names, validator and
  coercion signatures) that Step 5 is allowed to call. Unit: one sufficiency record per rendered prompt.
- **Instrumentation.** Emit `context_sufficiency` from the renderer with `run_id`, `step_name`, `turn_index`, `diff_files`, `inlined_files`, `missing_files`, `scope_recall`,
  `quals_api_inlined`, `guide_referenced`, `inlined_chars`; the file list comes from `git diff --name-only` between `C-1` and `C` and is intersected with paths appearing in
  the rendered string. The check is deterministic and adds no model calls; rows join to `evaluation_runs` on `commit_hash` and to `step_trajectories` on `step_name`.
- **Justification.** Failure can be missing input or missing capability, and only the former is fixable in the prompt templates; the proposal's per-function callgraph
  context (§C.3) was dropped per critique X-2 with no evidence it was unnecessary. This metric says whether to spend more turn budget (`agent.steps.fixer`) or rebuild
  context assembly before touching the model choice or `guardrails.mode`.
- **Expected learnings.** (h1) S4 prompts are context-rich (guide plus source) while S5 prompts are diagnostics-thin, so application failures correlate with
  `guide_referenced` and repair failures with `scope_recall`; (h2) runs where `quals_api_inlined` is false show guard rejections that invent helper names, since the fixer
  never saw the signatures. Surprisingly high `scope_recall` alongside `fixer_incomplete` would exonerate context and point at lattice quality (T4). Output: scatter of
  `scope_recall` versus `fixer.turns_taken`.
- **Reporting.** Report per-stage boxplots of `scope_recall` and a logistic model of `full_success` on `scope_recall` plus config covariates, with bootstrap CIs clustered by
  commit and repo. Threat: a file path appearing in the prompt does not prove the model read it, and large refactor hunks inflate `diff_files`, so also report a
  tier-1/tier-2-restricted recall variant.

### Section 5 instrumentation notes

All Section 5 events except P3's share `run_id`, `step_name`, `turn_index`, `template_id`,
`template_sha256`, `rendered_sha256`, `payload_tokens`, `truncated`, and the resolved `model.<stage>` value, so a single row
per LLM invocation joins composition, conformance and sufficiency without re-parsing transcripts. P3's `placement_guide_emitted` is emitted by the S3 packager and parser, not the renderer, and joins on `run_id` and guide hash. Store full prompt text for every S5 invocation and a sampled fraction of S2-S4 runs, keeping a hash plus a bounded excerpt otherwise, so `step_trajectories` stays queryable while
retention depth stays an explicit choice (L3 reports the audited fraction). Stage-level rollups are written into the same `metrics` JSON where `triage.token_usage`
and `eval.token_usage` already live.

---

## 6. LLM inference behavior

Section 6 characterizes how the OpenCode agents in S3 synthesis (`agent.steps.synthesis`, default 25 turns), S4 application (`agent.steps.application`, default 20 turns), and S5 blind fixing (`agent.steps.fixer`, default 50 turns) actually spend their turns, using the per-turn `agent_transcript` JSON and `compiler_diagnostics` planned for `step_trajectories`. The registry records terminal turn counts (`synthesis.turns_taken`, `application.turns_taken`, `fixer.turns_taken`) but nothing about what those turns contained or whether error progress was monotone. The metrics below decompose trajectories, error-set dynamics, repetition, harness failures, token use, and adherence to prompt-stated constraints so that turn budgets, guardrail defaults, and model choices can be argued from evidence rather than intuition. They apply to the multi-turn agent stages; S2 triage is single-shot and contributes only to I4.

<a id="i1"></a>
### I1. Action and tool-use trajectory taxonomy
**Priority:** Should-have · **Effort:** Low · **Stage:** S3/S4/S5 · **Relation:** new

- **Definition.** At each agent turn, classify the model's action into a fixed taxonomy (`read_source`, `edit`, `compile`, `run_test`, `read_diagnostics`, `write_artifact`, `other`) and record the ordered sequence per run. Define one primary action per turn (the first tool call, or the highest-level action if a turn contains several) and report `primary_share(action) = turns_whose_primary_action_is_action / total_turns`, so shares sum to 1. Report `action_event_share = action_events / total_action_events` separately when a turn contains multiple calls. Also report the first-turn action distribution and the action immediately preceding each successful compile. Captured from the parsed `agent_transcript` JSON of `step_trajectories` for the `synthesis`, `application`, and `fixing` steps.
- **Instrumentation.** The planned `OpenCodeRunner` counts `step_start` events; extend it to emit an `agent_action` event per action with fields `run_id`, `step_name`, `turn_index`, `action_type`, `tool_name`, `target_path`, `duration_ms`, `action_hash`, and `primary BOOLEAN`. The taxonomy is derived from tool-call names in the transcript, with an `other` bucket so unclassified actions stay visible. Granularity is one event per action, stored in the `metrics` JSON column.
- **Justification.** The registry records only terminal turn counts, so there is no way to tell whether 50 fixer turns were spent reading, compiling, or guessing. This bears directly on `agent.steps.synthesis` and `agent.steps.fixer` (D-11), which are currently justified only by intuition, and on the proposal's "retry with feedback" contingency, which presumes we know what the agent was doing when it failed.
- **Expected learnings.** We expect compile and read actions to dominate and edits to follow diagnostics; a high share of edits with no preceding compile would indicate blind patching. A surprising finding would be a stage that never compiles, implying it optimizes for textual plausibility instead of feedback. Compare action mix across `guardrails.mode` values and `model.*` settings to see whether guardrails change behavior or merely rejection counts.
- **Reporting.** Stacked bar of action share per stage plus a first-action distribution; aggregate over runs and cluster confidence intervals by commit and repo. Threat: transcripts with unparseable lines inflate the `other` bucket, so report its share as a data-quality flag rather than silently normalizing it away.

<a id="i2"></a>
### I2. Marginal progress and error-set dynamics
**Priority:** Must-have · **Effort:** Low · **Stage:** S5 (and S3) · **Relation:** extends `fixer.remaining_type_errors`

- **Definition.** Per turn, snapshot the Checker error set `E_t` and compute marginal progress as `delta_t = |E_{t-1} \ E_t| - |E_t \ E_{t-1}|` (errors removed minus introduced), plus Jaccard similarity `J(E_{t-1}, E_t)`. An oscillation is a turn that re-introduces an error key removed on an earlier turn: `oscillation_t = |E_t ∩ (E_0 \ E_{t-1})|`. Errors are identified by a normalized key `(checker_key, file, enclosing_method, message_family)` so that line shifts from edits do not manufacture false newness. Captured in the S5 repair loop and, for compiler feedback, in S3.
- **Instrumentation.** After each compile the harness parses `compiler_diagnostics`, normalizes each diagnostic to the key above, and emits `error_set_snapshot` with `run_id`, `step_name`, `turn_index`, `error_count`, `error_keys`, `delta`. This reuses the `-Aonelinemsg` output enabled in `checker.init.gradle` and the `metrics` JSON column; one snapshot per compile invocation.
- **Justification.** `fixer.remaining_type_errors` reports only the endpoint, hiding whether the agent converged, plateaued, or oscillated. A plateau on a non-empty error set is the signature of an unsatisfiable or over-restrictive lattice, the counterpart of the trivial-lattice failure the pipeline checks in the opposite direction. This informs turn-budget sizing and when to declare `fixer_incomplete` rather than keep spending turns.
- **Expected learnings.** We expect mostly monotone decreases with occasional reintroduction; a run that removes and re-adds the same error repeatedly suggests it is trading one constraint for another. If `delta_t` stays near zero for several consecutive turns, the budget is wasted and a smaller default may suffice. Relate first-turn error count to the eventual `eval.verdict`.
- **Reporting.** Line plot of error count per turn with a removed-versus-introduced waterfall; cluster by commit and pair with a survival curve over turns. Threat: normalization may merge genuinely distinct errors that share a method and message family, flattening real dynamics.

<a id="i3"></a>
### I3. Self-correction and thrash rate
**Priority:** Should-have · **Effort:** Medium · **Stage:** S3/S4/S5 · **Relation:** new

- **Definition.** Detect unproductive repetition: an `action_hash` seen earlier in the same run, or a patch that reverts a prior edit. Define `thrash_rate = thrash_turns / total_turns` and `turns_to_first_productive` as the index of the first turn with `delta_t > 0`. I3 measures repeated behavior, not outcome loss; I2 owns error reintroduction, and C4 owns non-improving turns and dollar waste. Captured per agent stage over the same turn stream as I1 and I2.
- **Instrumentation.** Hash each tool call (tool name plus normalized arguments) and each candidate patch; emit `repeat_action` when a hash recurs, carrying `run_id`, `step_name`, `turn_index`, `repeat_of_turn`, `action_hash`, and whether the repeat followed a guard rejection. This consumes the parsed transcript collected by the planned `OpenCodeRunner`.
- **Justification.** The design assumes the multi-turn loops converge within the configured budgets but never measures loops that spin. High thrash explains `fixer_incomplete` verdicts that are not really budget exhaustion, and repeated actions after an AST or guard rejection tell us whether the feedback channel informs the next turn or is ignored, which the design's rejection counters cannot distinguish.
- **Expected learnings.** We expect thrash to concentrate in runs with low localization (T3), where the agent has no local target. Repeated identical compile commands are benign; repeated identical edits are the interesting signal. A surprising pattern would be thrash rising under `strict` guardrails because blocked cheats send the agent into cycles rather than toward the bug.
- **Reporting.** Thrash rate per stage and `guardrails.mode`, with survival to first productive turn; Mann-Whitney across `model.*` settings. Threat: legitimate recompiles after unrelated edits can look like repetition, so require identical arguments before counting a repeat.

<a id="i4"></a>
### I4. Harness reliability and degenerate-output rate
**Priority:** Must-have · **Effort:** Low · **Stage:** S3/S4/S5 (S2 partial) · **Relation:** new

- **Definition.** Fraction of agent invocations that suffer a harness failure: crash, hang, timeout, empty completion, or a transcript from which no complete turn can be reconstructed. `degenerate_rate = degenerate_invocations / total_invocations`. Each failed invocation is labeled with a `failure_kind` from a small enum (`exit_error`, `timeout`, `empty_output`, `unparseable_transcript`, `rate_limited`, `auth_error`). `turn_budget_exhausted` is deliberately excluded: it is an expected `fixer_incomplete` outcome owned by F4, not a harness failure, and excluding it would bias success upward. A `valid_run` is a run with no harness failure. Captured by the runner for every stage, including the single-shot triage call.
- **Instrumentation.** The planned `AgentRunResult` carries `exitCode`, `stepsCounted`, and `transcript`; extend the runner to also record `wall_ms`, `timeout_hit`, `stderr_bytes`, `unparseable_lines`, `retry_after_rate_limit`, and `completion_tokens`, emitting `agent_run_result` per invocation. A transcript counts as unparseable only when the assistant turn structure cannot be recovered, not when one debug line is malformed. The advertised watchdog in `OpenCodeRunner` must actually enforce `timeoutSeconds` before these fields are trustworthy (I-11), and stderr must be drained (I-10).
- **Justification.** Incomplete or invalid runs contaminate every downstream denominator: a `fixer_incomplete` verdict caused by a hung process is not evidence about types. Distinguishing genuine model failures from harness failures is a precondition for interpreting I2, I3, and the evaluation verdicts, and it directly tests the currently unfixed stderr and timeout behavior described in the implementation critique.
- **Expected learnings.** We expect some unparseable debug lines, which the planned runner skips, but periodic full-run timeouts clustered in S5. A surprising result would be a high empty-output or rate-limit rate for one `model.*` value, signaling provider instability rather than task difficulty. Report the valid-run denominator alongside every headline number, and report budget-exhausted runs separately.
- **Reporting.** Table of `failure_kind` frequency per stage and model; aggregate to a valid-run count reused elsewhere, and report the excluded invocation count. Threat: silent hangs that never emit a step event are invisible unless the watchdog is enforced, so treat any pre-fix timeout data skeptically.

<a id="i5"></a>
### I5. Agent verbosity and token efficiency
**Priority:** Should-have · **Effort:** Low · **Stage:** S3/S4/S5 · **Relation:** extends `eval.token_usage`

- **Definition.** Tokens and output volume per turn and per successful outcome: `tokens_per_fixed_error = total_tokens / errors_resolved`, where `errors_resolved = max(0, E_first - E_last)` is net error reduction (report N/A when it is zero), plus `completion_chars_per_turn` and `turns_per_success`. Report prompt and completion tokens separately because diagnostic-heavy prompts scale with the error volumes measured in T2. Captured from per-turn usage records; the registry's `eval.token_usage` aggregates per stage and model, not per turn.
- **Instrumentation.** Emit a `token_usage` event per turn with `run_id`, `step_name`, `turn_index`, `model`, `prompt_tokens`, `completion_tokens`, and `diff_chars_written`. The planned runner receives `--format json` events, so usage should be attached where the provider exposes it, falling back to provider-reported totals when a turn does not carry its own.
- **Justification.** The design tracks total tokens but not their marginal productivity, so it cannot answer whether extra turns buy error reduction or only cost. This matters for the model and budget ablations and for P1's context budget, since more diagnostics per prompt can both help the fixer and inflate spend. It also reframes `fixer_incomplete` as a budget-efficiency question rather than a pure capability one.
- **Expected learnings.** We expect diminishing returns after roughly the first half of the fixer budget, with `tokens_per_fixed_error` rising steeply late in a loop. A surprising result would be verbosity uncorrelated with success, meaning the model is reasoning rather than padding. Compare across `model.*` to choose an ablation baseline on cost as well as accuracy.
- **Reporting.** ECDF of `tokens_per_fixed_error` per stage and model; regression of errors resolved on cumulative tokens. Threat: providers count tokens differently, so cross-model cost comparisons are indicative rather than exact.

<a id="i6"></a>
### I6. Instruction-adherence rate
**Priority:** Should-have · **Effort:** Medium · **Stage:** S3/S4/S5 · **Relation:** extends `fixer.guard_rejections`

- **Definition.** For prompt-stated rules that are not hard-enforced (edit only method bodies, do not touch `src/test/**`, do not add suppressions, place annotations per `placement_guide.md`), record `adherence(rule) = compliant_turns / checked_turns`. This measures behavior even when `guardrails.mode=disabled`, where no guard rejects the action. Captured per turn by a rule checker driven by the active prompt template version.
- **Instrumentation.** Emit `instruction_check` per turn with `rule_id`, `passed`, `evidence`, and `prompt_version`; rules are declared alongside each prompt template so drift is visible (P2). Where a guard also exists, join to its rejection event to separate "did not attempt" from "attempted and blocked"; `fixer.guard_rejections` supplies the blocked half.
- **Justification.** Only a minority of the active defense vectors are actually enforced (I-4, I-12), so rejection counts understate rule violations. Adherence tells us whether the agent naturally respects constraints or only does so when stopped, which is the core question behind the `disabled`/`lenient`/`strict` default debate and any claim that straying from the README's guarantees is safe. It also supplies the behavioral denominator that guardrail-attempt counts lack, since those count only blocked actions.
- **Expected learnings.** We expect suppression and signature attempts under `disabled` that guardrails merely relocate into rejection counts under `strict`. A surprising result would be adherence falling as transcript length grows, implying context pressure causes drift. Track per rule, not just in aggregate.
- **Reporting.** Adherence by rule and `guardrails.mode`; McNemar for paired strict-versus-lenient runs on the same commit. Threat: the rule checker is only as broad as the prompt templates it encodes.

### Section 6 instrumentation notes

All Section 6 metrics read the same per-turn event stream emitted by `OpenCodeRunner` and stored in `step_trajectories`. Shared fields on every event are `run_id`, `step_name` (`synthesis`, `application`, `fixing`), `turn_index`, `model`, `action_hash`, and a wall-clock timestamp; I1 and I6 additionally use transcript and prompt-version fields, while I2 and I4 carry diagnostic and process-status fields. Events must be append-only and emitted even for turns that fail, so that the valid-run denominator for I4 stays recoverable. Reparsing the raw `agent_transcript` should regenerate every event, which keeps the taxonomy robust to later changes.

## 7. Output parsing, patch assembly and post-processing

Section 7 covers what happens between an agent's emitted text and an artifact the pipeline can score: parsing the model's edits, assembling a unified patch, running the JavaParser canonicalization and `strip_annotations` gate (D-9, I-1), and threading synthesized helpers from `custom-quals.jar`. The relevant artifacts are `diff_patch`, the annotated `C-1` tree, and the helper API surface. Most of these transformations have no registry metric today, so parse failures and gate false positives are indistinguishable from genuine model failures. The metrics below make the step from model output to admissible patch observable at each stage boundary.

<a id="o1"></a>
### O1. Artifact validity and parse-success rate
**Priority:** Must-have · **Effort:** Low · **Stage:** S3/S4/S5 · **Relation:** new

- **Definition.** Fraction of agent-emitted Java artifacts that survive mechanical validation: JavaParser parses every touched file, the edit applies cleanly to the worktree, and the result compiles under plain `javac` without the Checker processor. `parse_success = valid_java_artifacts / emitted_java_artifacts`, broken down by `artifact_type` (`qualifier_source`, application patch, fixer patch). `placement_guide.md` is validated separately as Markdown (non-empty, required sections, bullet schema) and excluded from the Java denominator, because JavaParser and `javac` cannot parse it. Captured at the boundary of each agent turn.
- **Instrumentation.** After every turn, the harness runs `StaticJavaParser.parse` on modified files and applies the candidate patch to a scratch copy; emit `artifact_parse` with `run_id`, `step_name`, `turn_index`, `artifact_type`, `validation_kind` (`java_parse`/`javac`/`markdown_schema`), `parse_ok`, `error_kind`, `bytes`. Store the raw text in `step_trajectories.diff_patch`.
- **Justification.** The `strip_annotations` gate and signature locks only mean something if the patch parses at all. Without this metric, a turn where the model emitted a markdown-fenced or truncated patch is scored identically to a turn with a semantically wrong but valid edit, obscuring whether failures are textual or conceptual and misdirecting any fix.
- **Expected learnings.** We expect a non-trivial rate of malformed patches early in a run, dropping as the agent self-corrects. A surprising result would be parse failures concentrated in S4 rather than S5, implying the applier struggles with multi-file edits. Correlate parse success with `model.*` and turn index.
- **Reporting.** Rate by stage, model, and artifact type; report the parsed denominator wherever downstream metrics are computed. Threat: JavaParser is more permissive than `javac` in places, so pair this with the compile step and treat parse success as necessary but not sufficient.

<a id="o2"></a>
### O2. Patch shape and edit locality
**Priority:** Should-have · **Effort:** Low · **Stage:** S4/S5 · **Relation:** new

- **Definition.** Structural description of each accepted patch: files touched, hunks, added and removed lines, methods touched, and the count of changed lines outside method bodies. Define `body_only = outside_body_lines == 0` for fixer patches, and `annotations_only = (no non-annotation AST delta)` for applier patches, reusing O3's canonicalized comparison rather than raw diff text. Captured from `diff_patch` at acceptance time.
- **Instrumentation.** Parse the unified diff and map each hunk to its enclosing JavaParser declaration; emit `patch_shape` with `run_id`, `step_name`, `files_touched`, `hunks`, `added_lines`, `removed_lines`, `methods_touched`, `outside_body_lines`. This is deterministic post-processing, independent of any guard that may already have rejected the patch. Because it is computed from diff text rather than AST semantics, it complements O3: a patch can be body-only yet still change behavior.
- **Justification.** The design forbids signature and field edits and restricts the fixer to method bodies, but nothing records how invasive accepted patches actually are. Broad, multi-file patches raise the chance of collateral regressions (`regression_introduced`) and make the blindness claim harder to defend, since their scope can reveal localization guesses.
- **Expected learnings.** We expect applier patches to be shallow and repetitive while fixer patches stay local in successful runs. If successful fixes touch many methods, the bug may be structural rather than local, which would change how the localization metric T3 is read. Track whether broad patches predict `regression_introduced`.
- **Reporting.** Distribution of files-touched and changed-line counts, scatter against `eval.verdict` and `eval.regression_test_passed`; cluster by repo. Threat: rename or move detection can misattribute edits, so normalize paths before counting.

<a id="o3"></a>
### O3. AST/canonicalization fidelity and normalization loss
**Priority:** Must-have · **Effort:** Medium · **Stage:** S4 · **Relation:** extends `application.ast_guard_rejections`

- **Definition.** For every applier edit, the fraction accepted by the `strip_annotations(AST_original) == strip_annotations(AST_modified)` gate, plus a manual estimate of `normalization_loss`: changes the canonicalization hides and therefore cannot reject, such as executable edits inside a region the normalizer discards. `normalization_loss` cannot be computed automatically by the same canonicalizer that creates it; it is an adjudicated estimate on a random sample. Report `gate_accept_rate`, a reason breakdown (`import_delta_only`, `executable_change`, `signature_change`, `other`), and the false-negative audit with its sample size and interval. Captured in S4 before the Checker run; `gate_accept_rate` is blocked until the gate exists (critique I-1).
- **Instrumentation.** The applier gate canonicalizes via `ModifierVisitor`, strips annotations, applies the import allowlist from D-9, and emits `ast_gate_result` with `run_id`, `turn_index`, `accepted`, `reason`, `stripped_nodes`, `import_delta_only`. `application.ast_guard_rejections` records only the count; this adds the reason and the false-positive versus false-negative split. The false-negative audit is a separate two-coder review of a random sample of accepted edits, recorded in `human_ratings`.
- **Justification.** The equality gate is undefined in the design, so an implementer cannot distinguish a correct rejection from a normalization artifact. Import-only deltas are the concrete false-positive risk (D-9): a naive strip rejects a legitimate annotation import. Measuring normalization loss also bounds how much executable change could slip through, which is the guard's entire purpose.
- **Expected learnings.** We expect import-only rejections to be common before the allowlist is applied and near zero afterward. A surprising result would be non-trivial normalization loss on the adjudicated sample, meaning the gate is weaker than advertised and some executable edits are invisible to it. Track rejections that recur across turns as feedback that is not being absorbed.
- **Reporting.** Accept rate and rejection-reason breakdown per run; report the false-negative audit as an estimate with a Clopper-Pearson interval, and the false-positive spot checks the same way. Threat: the normalizer is itself code, so its fidelity can only be verified by the manual sample, and a small sample yields a wide interval.

<a id="o4"></a>
### O4. Helper API surface and utilization
**Priority:** Should-have · **Effort:** Medium · **Stage:** S3/S5 · **Relation:** extends `synthesis.helpers_count`

- **Definition.** Compare the helper API synthesized into `custom-quals.jar` against the helper calls the fixer actually makes: `helper_utilization = helpers_called / helpers_defined`, plus a per-helper call count and signature kind (`coercion`, `validator`, `other`). Captured by intersecting the S3 helper inventory with helper call sites in accepted S5 patches.
- **Instrumentation.** S3 parses the helper source before packaging and emits `helper_defined` with `fq_name`, `param_types`, `return_type`, `kind`, `pure`; S5 emits `helper_call` with `fq_name`, `call_site`, `turn_index`. `synthesis.helpers_count` supplies the headline number, while the two events make utilization and per-helper popularity computable.
- **Justification.** Helpers are live code, not inert annotations (D-18), so an unused or unsound helper is not free: it enlarges the trusted base and can alter runtime behavior even in `full_success` runs. Utilization also tests the Level 1 plus Level 2 policy, since a fixer that never calls coercions means the synthesized surface was largely decorative. A helper that is defined but never invoked still inflates the API surface a human reviewer must assess.
- **Expected learnings.** We expect coercions to be used more than flow-refining validators, and a long tail of never-called helpers. A surprising result would be utilization near zero across runs, implying the lattice is checked but not actually used to repair. Relate per-helper purity flags to subsequent `regression_introduced`.
- **Reporting.** Utilization bar per run with a signature-kind table, reporting the helper inventory size beside it. Threat: reflective or indirect calls would be missed by AST call detection, so measured utilization is a lower bound.

<a id="o5"></a>
### O5. Rejection-feedback repair efficacy
**Priority:** Should-have · **Effort:** Medium · **Stage:** S4/S5 · **Relation:** extends `fixer.guard_rejections`

- **Definition.** Given a rejection (AST gate or guardrail), whether the agent's next accepted attempt resolves it: `repair_after_rejection = resolved / rejections`, with `turns_to_resolve` per rejection. `resolved` means the next accepted patch no longer triggers the same `rejection_id` or reason; whether the run ultimately reaches its goal is reported separately as `run_goal_reached`. Distinguish immediate recovery (next turn) from delayed recovery and from never-resolved cases where the run ends still rejecting. A rejection with no subsequent turn is censored, not resolved. Captured by linking rejection events to subsequent accepted patches within the same run.
- **Instrumentation.** Emit `rejection_followup` with `run_id`, `rejection_id`, `rejection_type`, `next_turn_index`, `resolved`, `turns_to_resolve`. `application.ast_guard_rejections` and `fixer.guard_rejections` provide the rejection counts; this event adds the outcome of the feedback loop the design assumes exists but never models (D-12). Storing the link lets rejection outcomes be aggregated by `guardrails.mode` and stage without re-reading transcripts.
- **Justification.** The AST gate and guardrails are only useful if their messages change the next turn. Without this metric, a guard that rejects everything and teaches nothing looks like a strong guard, and the proposal's "retry with feedback" contingency cannot be evaluated. It also separates real anti-cheat pressure from the repeated identical attempts captured in I3.
- **Expected learnings.** We expect most rejections to resolve on the following turn with a visible message reference. A surprising pattern would be repeated identical rejections, meaning the feedback is ignored, or first-attempt resolutions that fail later, meaning the model satisfied the letter of the guard. Unresolved rejections should correlate with `fixer_incomplete`.
- **Reporting.** Resolution rate by rejection type, with a survival curve to resolution, aggregated per stage. Threat: when several issues exist in one turn, attributing a later acceptance to a specific rejection is ambiguous.

### Section 7 instrumentation notes

Section 7 metrics share one post-turn validation pipeline that runs after every agent turn: parse touched files with `StaticJavaParser`, apply the candidate patch to a scratch copy, canonicalize and compare, and characterize the diff. Common event fields are `run_id`, `step_name`, `turn_index`, `artifact_type`, `patch_hash`, and `accepted`. O1 and O2 read the raw patch, O3 reads the canonicalized AST comparison, and O4 and O5 join helper and rejection events by `fq_name` and `rejection_id`. Because all of these are deterministic, they can be recomputed from `diff_patch` and `compiler_diagnostics` without another model call.

## 8. Static analysis and type validation

Section 8 concerns the Checker Framework signal itself: what diagnostics the synthesized lattice or built-in checker emits on `C-1`, whether those errors are new relative to a clean baseline, where they land, and whether the lattice is structurally meaningful. The central constraint is critique D-22: built-in checkers such as nullness emit many pre-existing errors across legacy code, so the fixer's target of zero errors is unattainable unless the analysis reports zero new errors instead. Every metric below is stratified by `synthesis.checker_category`, distinguishing `custom_qualifier_hierarchy` runs from `builtin_checker` runs, because their baseline, localization, and vacuity profiles differ sharply. Diagnostics are captured after S3 packaging but before placement (baseline), after placement in S4, and at fixer termination in S5.

<a id="t1"></a>
### T1. Checker baseline delta
**Priority:** Must-have · **Effort:** Low · **Stage:** S4/S5 · **Relation:** extends `application.total_checker_errors`

- **Definition.** Snapshot Checker diagnostics on clean `C-1` before any annotation but after S3 has packaged the selected processor and qualifier jar (the baseline `B`), then compute `new_errors = E_after \ B` after placement and again at fixer termination. Primary quantities are `baseline_error_count`, `new_error_count`, and `residual_new_errors`. Define `checker_delta_clean = (residual_new_errors == 0)` as a parallel checker-cleanliness quantity, and `delta_success` as the full Step 6 outcome with `checker_delta_clean` substituted for raw zero; the registry's `full_success` remains raw zero errors and is not renamed. Report both. Stratified by `synthesis.checker_category`.
- **Instrumentation.** Run the selected processor and `-Aquals` set on clean `C-1` immediately after S3 packaging and before S4 placement, normalize each diagnostic to `(checker_key, file, enclosing_method, message_family)`, and emit `checker_baseline` with `count`, `error_keys`, `checker_category`. S4 and S5 emit `checker_after` in the same key space. Store raw output in `compiler_diagnostics`; `application.total_checker_errors` remains the raw count, extended here with the delta.
- **Justification.** D-22 identifies the failure directly: enabling built-in checkers over a whole legacy project surfaces pre-existing errors in unrelated code, the blind fixer is asked to repair them, and `fixer.remaining_type_errors == 0` becomes unreachable for reasons unrelated to the hypothesis. Without a baseline, `full_success` is depressed by legacy noise, and `custom_qualifier_hierarchy` runs, whose qualifiers default new code rather than flagging it, are not comparable to `builtin_checker` runs. Because the custom processor does not exist before S3, the baseline must be taken after packaging; a pre-S3 built-in-only snapshot cannot be the baseline T1 subtracts.
- **Expected learnings.** We expect a large baseline for built-in nullness and a near-zero baseline for freshly synthesized hierarchies, so raw error counts are not comparable across categories. We also expect a high baseline to predict `fixer_incomplete`. A surprising result would be a synthesized hierarchy with a large baseline, implying it accidentally reuses a built-in checker's defaulting behavior.
- **Reporting.** Paired new-error deltas per commit, stratified by `checker_category`; report both raw-`full_success` and `delta_success`, with McNemar for paired typed-versus-baseline conditions and bootstrap CIs clustered by commit and repo. Threat: the baseline is not stable across JDK or checker-version changes, so toolchain pins must be recorded in the config snapshot.

<a id="t2"></a>
### T2. Diagnostic taxonomy and attribution
**Priority:** Must-have · **Effort:** Medium · **Stage:** S4/S5 · **Relation:** new

- **Definition.** Classify every diagnostic along three axes: `checker_key` (for example subtyping, index, regex), `origin` (`pre_existing` if in the baseline, `annotation_induced` if triggered by a placed annotation, `fixer_induced` if created by a fixer edit), and `annotation_id` naming the placement responsible. Report counts per cell and the share of diagnostics that cannot be attributed to any placement. Captured at S4 and S5.
- **Instrumentation.** Parse Checker one-line diagnostics (the harness enables `-Aonelinemsg` and `-AshowPrefixInWarningMessages`), join each to the applied annotation inventory from `annotation_placements`, and emit `checker_diagnostic` with `checker_key`, `kind`, `file`, `line`, `origin`, `annotation_id`. Baseline membership is decided by key match against `checker_baseline`.
- **Justification.** `application.total_checker_errors` is a single integer, so it cannot separate noise the lattice did not cause from errors it did. Attribution is what makes T1's delta interpretable and feeds the signal-to-noise computation in T3; without it, the pipeline cannot tell whether an error is a verdict about the synthesized type or about legacy code that predates the run entirely.
- **Expected learnings.** We expect nullness-dominated diagnostics for `builtin_checker` and subtyping-map or regex diagnostics for `custom_qualifier_hierarchy`. A surprising result would be a large unattributable share, meaning errors arise where no applied annotation explains them, which would undercut the placement guide. Watch for `fixer_induced` errors appearing late in a repair loop.
- **Reporting.** Stacked bar of diagnostic counts by `checker_category`, `checker_key`, and `origin`, reporting the unattributable share explicitly; when more than one annotation covers a line, record `annotation_id` as `unattributed` rather than guessing. Threat: Checker message wording varies across releases, so the `message_family` normalizer must be version-aware.

<a id="t3"></a>
### T3. Error localization and signal-to-noise ratio
**Priority:** Must-have · **Effort:** Medium · **Stage:** S4 (feeds S5) · **Relation:** new

- **Definition.** Assign each new diagnostic to a localization tier relative to commit `C`'s diff hunks: tier 1 diff hunk +/- 3 lines, tier 2 enclosing method, tier 3 modified class, tier 4 unrelated file (design section 7.3). Define `SNR = new_errors_in_buggy_method / total_new_errors`, reporting N/A when `total_new_errors == 0`, and report the tier histogram. Captured at the end of S4 and again at S5 termination.
- **Instrumentation.** Join diagnostic `file:line` to the JavaParser enclosing method and scope, then join that to `C`'s diff hunks recovered in S1 and S2; emit `error_localization` with `run_id`, `tier`, `enclosing_method`, `distance_lines`, `checker_category`. This is the mandatory leakage and localization metric that D-2 requests, reusing the design's own tier definition.
- **Justification.** D-2 shows that placement choices and diagnostics cluster around the buggy method and leak location to the otherwise blind fixer; the pipeline should measure this rather than assume it away. Operationally, low SNR means the fixer spends its limited turns on unrelated files, compounding D-11's tight budgets and producing `fixer_incomplete` for reasons the hypothesis does not predict.
- **Expected learnings.** We expect `custom_qualifier_hierarchy` runs to be more localized because the applier targets method semantics, while `builtin_checker` runs spread across the codebase. SNR should predict fixer success and correlate with the non-triviality check. A high tier-4 share is the surprising and important case: it means the synthesized types point away from the bug.
- **Reporting.** Tier histogram and SNR per run, stratified by `checker_category`, correlated with `eval.verdict`. Threat: the historical `C` diff may be unavailable or itself noisy, so localization is a positional proxy, not proof of semantic relevance.

<a id="t4"></a>
### T4. Lattice structure, canary and vacuity
**Priority:** Must-have · **Effort:** Medium · **Stage:** S3/S4 · **Relation:** extends `synthesis.qualifiers_count`

- **Definition.** Structural summary of the synthesized lattice: qualifier count, whether an explicit top and bottom exist, the `@SubtypeOf` edge count and depth, whether the hierarchy is vacuous (a single qualifier, or top equal to bottom, which admits everything), and the canary outcome. `canary_pass` requires the checker to reject a fixture it must reject and accept one it must accept. Captured from `custom-quals.jar` and the canary fixture run.
- **Instrumentation.** S3 parses qualifier declarations and `@SubtypeOf` edges before packaging and emits `lattice_summary` with `qualifiers_count`, `hierarchy_edges`, `max_depth`, `is_vacuous`, `checker_category`; the canary fixture emits `canary_result` with `rejected_fixture`, `accepted_fixture`, `pass`. `synthesis.qualifiers_count` supplies the headline count.
- **Justification.** D-7 exposes a contradiction: the design allows only declarative `@SubtypeOf` hierarchies and built-ins, yet the threat model names a custom `isSubtype` returning `true` as an attack. Either way, a vacuous lattice can pass every downstream check, so vacuity must be measured directly rather than inferred from the absence of complaints. The canary turns "trivial lattice" from a narrative worry into a pass or fail gate for `custom_qualifier_hierarchy` in particular.
- **Expected learnings.** We expect some single-qualifier or top-equals-bottom lattices that still trigger errors, and canary failures when a custom `isSubtype` is over-permissive. Hierarchy depth should relate to whether the bug is preventable. A surprising result would be vacuous lattices that nonetheless yield `full_success`, which would suggest the fix came from somewhere other than the types.
- **Reporting.** Table of lattice features per run with a scatter of qualifier count against verdict, stratified by `checker_category`. Threat: compiled checker internals are not introspectable, so structure is inferred from source and manifest and can miss generated subtypes.

<a id="t5"></a>
### T5. Annotation coverage and agreement with ground truth
**Priority:** Should-have · **Effort:** High · **Stage:** S4 · **Relation:** extends `application.annotations_added_count`

- **Definition.** Of the type-relevant sites in the target scope (parameters, fields, return types), the fraction annotated: `coverage = annotated_sites / target_sites`, split by site kind and by whether `placement_guide.md` specified the site. `target_sites` is defined as annotatable declarations in the S4 target scope, excluding tests and generated code. Where a two-rater gold annotation set exists (10-15 sites per run), compute agreement with it as Cohen's kappa; no such set exists today, so coverage and guide conformance are the primary outputs and agreement is explicitly blocked. Captured from the annotated `C-1` tree at the end of S4.
- **Instrumentation.** JavaParser scans the annotated tree and the guide, emitting `annotation_coverage` with `run_id`, `checker_category`, `site_kind`, `qualified`, `guide_specified`, and `ground_truth_match` where available; placements come from `annotation_placements`. `application.annotations_added_count` gives the total; this event supplies the denominator and the guide-conformance split.
- **Justification.** Coverage connects the placement guide (P3) to the checker signal: an under-annotated tree may produce few or non-local errors that look like a trivial lattice (T4) rather than a placement failure. Agreement with ground truth is the most direct evidence that the synthesized domain types match the intent of the historical fix, which is the proposal's central claim and the target of human review (H2).
- **Expected learnings.** We expect parameter coverage to exceed field coverage, since fields carry more risk to annotate. Disagreement, where sampled, should concentrate on ambiguous string parameters whose domain roles are unclear. A surprising result would be high coverage with low error SNR, meaning annotations are present but semantically misplaced.
- **Reporting.** Coverage by site kind and run, stratified by `checker_category`, with kappa on a manual stratified sample; flag the ground-truth comparison as blocked and scope-limited until a two-rater annotation set exists. Threat: `target_sites` is itself a judgment call, so the denominator is model-dependent and must be reported.

### Section 8 instrumentation notes

Section 8 metrics read a single diagnostic stream plus three structural event families (`lattice_summary`, `canary_result`, `annotation_coverage`). Every diagnostic event carries `run_id`, `checker_key`, `checker_category`, `file`, `line`, `origin`, and a normalized `error_key`; baseline and post-placement runs share this key space so deltas are computable. The pre-placement baseline is taken after S3 packaging so the custom processor exists; it is not a pre-S3 built-in snapshot. Structural events carry `checker_category` and the synthesis run id, so T4 and T5 can be read per lattice rather than per commit. Because `custom_qualifier_hierarchy` and `builtin_checker` differ in baseline size and localization, no Section 8 metric is ever aggregated across the two without stratification.

---

## 9. Evaluation and scoring

The evaluation layer consumes artifacts produced by the earlier stages: the verified `(C, C-1, T_reg)` sample from S2, the lattice S3 packages into `custom-quals.jar` plus `placement_guide.md`, the annotated `C-1` from S4, the blind fixer's patched method bodies from S5, and the check-order verdict rows S6 writes into `evaluation_runs` and `step_trajectories`. Today the only scoring surface is `eval.regression_test_passed` plus the five-valued `eval.verdict`, so a `full_success` records that the regression test passed but not why. The eight metrics below turn that single verdict into a comparative, partially-credited, causally-attributed score: they add the baseline arms D-1 demands, an outcome vector, the missing Step 6b ground-truth check from X-2, fidelity and collateral measures, difficulty stratification, burden proxies for P-3/X-1, and config-ablation effect sizes. Where an existing registry item already records a boolean or enum (`eval.verdict`, `eval.regression_test_passed`, `eval.existing_unit_tests_passed`), the proposal below is marked `extends` with a new dimension; everything else is marked `new`. All are computed offline in Python/Pandas against `data/pipeline.db`; none is claimed to exist yet.

<a id="e1"></a>
### E1. Baseline lift and causal attribution
**Priority:** Must-have · **Effort:** Medium · **Stage:** S5/S6 · **Relation:** extends `eval.verdict` (baseline-arm dimension)

- **Definition.** For each verified sample `(C, C-1, T_reg)` run Step 5 three times from the same underlying `C-1` revision, with the artifact set each condition defines and the same turn budget: `fixer_no_types`
  (annotations stripped, Checker processor off), `fixer_builtin_only` (one built-in checker, no custom lattice), and `fixer_typed` (full
  `custom-quals.jar` lattice from S3/S4). The headline quantity is `lift = success(fixer_typed) - success(fixer_no_types)`, with
  `lift_builtin = success(fixer_typed) - success(fixer_builtin_only)`. `success` is the registry's raw-zero `full_success`; a parallel `delta_success` substitutes T1's `checker_delta_clean` for raw zero, and because the no-types arm has no Checker signal, `lift_delta` is reported for typed versus builtin only. `experiment.baseline` selects the reported arm (default `fixer_typed`). If budget permits, a fourth `fixer_localization_only` arm (same Checker diagnostics and guide hints, no qualifiers) separates type semantics from localization; otherwise describe the headline lift as type-plus-localization, not type-only.
- **Instrumentation.** Add `baseline_arm` to `evaluation_runs` (not only to `config_snapshot`); each arm is its own row keyed to
  the same `commit_hash`. S5 emits `fixer.turns_taken`, `fixer.remaining_type_errors`, `fixer.guard_rejections`; S6 emits `regression_test_passed`
  and `verdict`. A single `eval.arm_completed` event carries `{run_id, commit_hash, baseline_arm, guardrails_mode, passed}`, and arms are paired
  on `commit_hash`; `config_hash` is reported to show shared non-arm settings, and it excludes `baseline_arm` so that it does not break pairing.
- **Justification.** This is the control D-1 calls absent: without `fixer_no_types`, a `full_success` cannot separate "the synthesized types
  guided the repair" from "the blind fixer read the code and guessed". It also operationalizes D-2's leakage worry, because the no-types arm starts from the same source revision with no qualifier signal; arm divergence is the only direct evidence for the README core hypothesis.
- **Expected learnings.** We expect `fixer_typed >= fixer_no_types`, so a flat or negative lift would imply the lattice carries no causal load and
  the pipeline is an expensive code-repair prompt. A second hypothesis is that `fixer_builtin_only` absorbs most lift on nullness-flavored bugs,
  leaving custom qualifiers to earn their keep on ID-conflation and unit bugs; a per-bug-class McNemar table would show this. Surprising: negative
  lift with `regression_introduced`, meaning the lattice forced collateral edits.
- **Reporting.** Paired arm x outcome table, McNemar on paired binaries, bootstrap CIs clustered by repo, per-sample lift histogram, and both intention-to-treat and valid-run denominators so harness exclusions are visible. Report `lift` (raw zero) and `lift_delta` (zero new errors) side by side. Threat:
  `fixer_builtin_only` is not like-for-like unless it shares the same `--init-script` injection, `guardrails.mode`, turn budget, and a task-equivalent repair prompt; `fixer_no_types` must not stop immediately merely because the Checker is off.

<a id="e2"></a>
### E2. Outcome vector and partial credit
**Priority:** Should-have · **Effort:** Low · **Stage:** S6 · **Relation:** extends `eval.verdict` and `eval.regression_test_passed`

- **Definition.** Decompose the single enum into an ordered vector over S6's check sequence (Checker new errors -> existing unit tests -> `T_reg`) while retaining the four verdict values for continuity:
  `v = (non_trivial, checker_clean, existing_tests_clean, regression_passed)`, plus a weighted
  `partial_credit = 0.2*non_trivial + 0.2*checker_clean + 0.25*existing_tests_clean + 0.35*regression_passed`. `checker_clean` means raw zero Checker errors, matching `full_success`; `checker_delta_clean` uses T1's zero-new-error definition and is reported in parallel. The vector maps to the four strings as follows: `full_success` = all four true; `fixer_incomplete` = `checker_clean` false (regardless of later checks); `regression_introduced` = `checker_clean` true and `existing_tests_clean` false; `type_success_runtime_fail` = `checker_clean` and `existing_tests_clean` true but `regression_passed` false. `non_trivial` is an S4 property carried into S6, not a fourth S6 check.
- **Instrumentation.** S6 writes `eval.outcome_vector` JSON into `step_trajectories.metrics` for `step_name='verification'`: `checker_new_errors`,
  `existing_tests_failed_count`, `regression_test_result`, `trivial`, `partial_credit`, `check_order`. Because S6 is deterministic the vector is
  recomputable from the stored `diff_patch`, `compiler_diagnostics`, and `test_outcomes` rows.
- **Justification.** `eval.verdict` collapses near-misses into one label; partial credit stops a run that resolves every type error but trips one
  pre-existing flaky test from scoring identically to a run that never built. It also tells E6 whether failures are static (`application.is_trivial`,
  `fixer.remaining_type_errors > 0`) or runtime.
- **Expected learnings.** We expect mass at `full_success` and `fixer_incomplete`; a large `type_success_runtime_fail` share would show the lattice
  models an invariant weaker than the bug (D-22). A frequency plot of vector states, plus a scatter of `partial_credit` against E1 lift, would expose
  whether partial progress predicts causal value. Surprising: high `partial_credit` but `regression_passed` uniformly false, implying the fixer
  saturates the type system without curing the bug.
- **Reporting.** Stacked bar of vector states per arm, correlation of `partial_credit` with E1. Threat: the weights are a researcher choice, so report
  the unweighted vector alongside any scalar.

<a id="e3"></a>
### E3. Ground-truth-fix acceptance (Step 6b)
**Priority:** Must-have · **Effort:** Medium · **Stage:** S6b · **Relation:** new

- **Definition.** Add the missing half of proposal `§D.4` as an offline verification check: after S6, port `C`'s non-test source hunks onto the annotated `C-1` (a fresh worktree
  seeded from the S4 output) and run the Checker pass unchanged. Record `eval.annotations_accept_ground_truth_fix` (boolean) and
  `eval.ground_truth_fix_checker_errors` (count). Acceptance means the human's true fix type-checks under the synthesized lattice; rejection means
  the lattice is over-restrictive and would render the historical correct repair untypeable.
- **Instrumentation.** An additive Step 6b pass emits a `verify.ground_truth_fix` event with `{run_id, applied_hunks, patch_conflict, checker_new_errors, accepted}`.
  `patch_conflict=true` (the human fix does not apply to the annotated tree) is recorded as a distinct outcome from a type rejection, and results land
  in `step_trajectories` under the additive `step_name='verification_gt'`; this is a measurement pass, not a change to the six runtime stages.
- **Justification.** X-2 flags that a `full_success` can coexist with a lattice that rejects the human patch, exactly the soundness leak design `§7.1`
  worries about but only half-detects. This check makes the proposal's "passes on `C`" clause real without depending on `T_reg` or on the fixer, so it
  isolates lattice restrictiveness from fixer behavior.
- **Expected learnings.** We expect most bugs to accept the human fix; a non-trivial rejection rate would mean the S3 agent over-fits the buggy state
  rather than the domain invariant. Cross-tabulating acceptance against bug class and `synthesis.checker_category` would show whether custom qualifiers
  are more over-restrictive than `builtin_checker` choices. Surprising: `fixer_typed` passes `T_reg` while Step 6b rejects the human fix, a direct
  contradiction worth manual review.
- **Reporting.** Contingency table (accepted x verdict) with exact binomial CIs; list of rejection cases for qualitative inspection. Threat: `C`'s
  source hunks can touch scaffolding or generated files, so the port excludes `src/test/**` and records what was skipped.

<a id="e4"></a>
### E4. Fix fidelity versus human patch
**Priority:** Should-have · **Effort:** Medium · **Stage:** S6 · **Relation:** new

- **Definition.** Measure how closely the blind fixer's patched method bodies match the human fix in `C`, independent of whether `T_reg` passed.
  Quantities: `hunk_recall = |hunks(fixer_patch) intersect hunks(C_main)| / |hunks(C_main)|`, `method_overlap` (Jaccard over modified method
  signatures), and a normalized JavaParser AST edit distance between fixer and human bodies for shared methods, computed only over `src/main/java`
  hunks of `C`. Hunks match when they touch the same file, the same enclosing method, and overlapping changed line intervals; line-range identity alone is too strict. A `same_repair` boolean marks exact hunk-plus-method agreement.
- **Instrumentation.** Reuse `step_trajectories.diff_patch` for `step_name='fixing'` and the ground-truth `T_reg`/source patch from S2/S6; canonicalize
  both with JavaParser and emit `eval.fidelity` with `{hunk_recall, method_overlap, ast_edit_distance, extra_methods, same_repair}`. The AST distance is a named tree-edit metric over statement nodes, normalized by the larger tree size; report it as secondary. No new agent turns
  are needed; this is an offline join against the retained `C` source patch and the S5 diff column.
- **Justification.** A `full_success` can be reached by an alternative repair (a different guard or coercion) that cures the test without matching the
  human fix. Fidelity distinguishes a faithful cure from a lucky pass and shows whether the types steered the fixer toward the same region (D-2), which
  `eval.regression_test_passed` alone cannot express.
- **Expected learnings.** We expect high `hunk_recall` on ID-conflation bugs and lower recall on range/sanitization bugs where multiple repairs are
  valid. Low recall with a passing test would suggest `T_reg` under-specifies the invariant; plotting recall against `application.total_checker_errors`
  tests whether sharper error clusters narrow the search. Surprising: zero overlap yet `full_success`, indicating a shortcut.
- **Reporting.** Scatter of `hunk_recall` versus verdict per bug class and AST edit-distance percentiles; report `same_repair` as a rate with a Wilson
  interval. Threat: distance penalizes semantically equivalent rewrites, so pair it with the E3 acceptance outcome.

<a id="e5"></a>
### E5. Collateral regression breadth
**Priority:** Should-have · **Effort:** Low · **Stage:** S6 · **Relation:** extends `eval.existing_unit_tests_passed`

- **Definition.** Beyond the existing boolean, count breadth and depth of collateral damage: `existing_tests_failed_count`, `files_modified_outside_scope`, and `methods_modified` under the fixer patch. Map each edited location to design `§7.3` tiers (tier 1 diff hunk plus/minus
  3 lines, tier 2 enclosing method, tier 3 modified class, tier 4 unrelated file) and report two components separately: `failed_test_share = failed_tests / total_tests` and
  `t4_edit_share = |tier-4 edits| / max(1, total_edits)`. Both are proportions in [0,1]; a composite `collateral_index` is reported only if its weights are preregistered, and never as a raw sum of test counts and edit counts.
- **Instrumentation.** The planned S6 runs the full `gradle test` suite; parse the JUnit XML for failing test names and counts. Join the `diff_patch`
  against `C`'s hunks via JavaParser to assign tiers, and emit `eval.collateral` with `{failed_tests, total_tests, t4_edits, total_edits, files_outside_scope, methods_modified}`
  into `step_trajectories.metrics` for the verification step. The failed-test count is derived from E2's `test_outcomes`, not a second observation.
- **Justification.** `eval.existing_unit_tests_passed` is a boolean that hides partial breakage: a run that breaks two unrelated tests reads the same
  as one that breaks twenty. Wide tier-4 editing also flags lattices whose errors leak far outside the buggy method (D-2, design `§7.3`), inflating fixer
  cost without curing the bug.
- **Expected learnings.** We expect most edits in tiers 1-2 with rare tier-4 leakage; a fat tier-4 tail would implicate `application.total_checker_errors`
  clustering rather than the fixer. Surprising: high collateral damage concentrated in `fixer_builtin_only`, which would suggest built-in nullness
  errors scatter across legacy code (D-22) and depress `full_success` for reasons unrelated to the hypothesis.
- **Reporting.** Histogram of edits by tier and `failed_test_share` per arm, with a bootstrap CI clustered by repo; report the two components side by side. Threat: the suite itself may be
  flaky, so record a clean `C-1` baseline suite result as a covariate.

<a id="e6"></a>
### E6. Difficulty stratification and success predictors
**Priority:** Should-have · **Effort:** Medium · **Stage:** S2/S4/S6 · **Relation:** new

- **Definition.** Attach per-sample difficulty features and model `full_success` (and E1 lift) against them: bug class (ID-conflation, unit, range,
  sanitization), number of `C` diff hunks, buggy-method LOC, `application.total_checker_errors`, `synthesis.qualifiers_count`,
  `synthesis.checker_category`, sample-localization SNR (or maximum tier), commit age, and repo. Fit a fixed-effects logistic regression with repo as a
  covariate rather than a random intercept; with two repositories a random intercept is not reliably estimable. Pre-register at most five features and use the
  coarse bug classes so cells are not empty.
- **Instrumentation.** Join `commits.triage_metadata`, `evaluation_runs.config_snapshot`, and `step_trajectories.metrics`; the planned S2 extracts bug
  context, and a new `sample.difficulty_features` record is written at S4 carrying the sample-localization SNR, maximum tier, and checker-error count. No new collection
  beyond one feature-builder pass in Python/Pandas; localization reuses the `§7.3` tier mapping.
- **Justification.** Aggregate success rates can hide that the pipeline only works on one bug class, and the proposal's preventability heuristic
  (proposal `§C.1`, X-2) is otherwise unfalsifiable. This decides which bugs to target and whether `triage.bugfix_threshold` should filter differently,
  and it separates "types helped" from "these bugs were easy".
- **Expected learnings.** We expect ID-conflation bugs to succeed most and sanitization/range bugs to fail most, because the latter need value-level
  validators that Level 1/2 helpers model unevenly. A success-by-bug-class table with per-cell counts and a coefficient plot would show this.
  Surprising: success driven purely by `application.total_checker_errors` (more errors, better outcomes), suggesting raw error volume rather than type
  semantics is doing the work.
- **Reporting.** Success-by-stratum table with Clopper-Pearson intervals and a forest plot of odds ratios. Threat: small N per stratum forces pooling,
  so pre-register the coarse bug classes rather than fitting many dummies.

<a id="e7"></a>
### E7. Developer-burden proxies
**Priority:** Must-have · **Effort:** Low · **Stage:** S3/S4 · **Relation:** new

- **Definition.** Operationalize the proposal's second goal (P-3/X-1) with machine proxies per sample:
  `annotations_per_kloc = application.annotations_added_count / (KLOC at C-1)`, `helper_loc` (lines in synthesized validators plus `custom-quals.jar`
  sources), `files_annotated`, `placement_guide.md` token length, and `manual_interventions_count` (normally 0 by design, so any human adjudication is
  logged). KLOC counts main Java sources only, excluding tests, generated code, and build files; annotation counts are placement instances, not distinct symbols. E7 is deliberately machine-only; H4 is the optional human calibration and is not a second machine metric.
- **Instrumentation.** The planned S3/S4 emit `synthesis.qualifiers_count`, `synthesis.helpers_count`, and `application.annotations_added_count`; add
  `burden.annotations_per_kloc`, `burden.helper_loc`, `burden.files_annotated`, and `burden.guide_tokens`. No human study is required for E7; H4 owns
  any `human_study` table or CSV, because manual timing is outside the automated pipeline.
- **Justification.** The proposal framed burden as co-equal with prevention, yet no registry metric measures it (X-1). Without a machine proxy the
  paper can say whether the automation works but not whether it is adoptable; H4, if run, calibrates whether the machine proxies track perceived effort.
- **Expected learnings.** We expect `annotations_per_kloc` and `helper_loc` to vary widely by bug class, with custom hierarchies costing far more than
  `builtin_checker` selections. A plot of `annotations_per_kloc` against `synthesis.helpers_count` would show the burden mix. Surprising: very low
  burden but low lift, meaning cheap types that do not help. H4 can test whether these proxies track reviewer time and acceptability.
- **Reporting.** Distribution of proxies per bug class and config. Threat: proxies count lines, not cognitive load, so skipping H4 must be reported as a
  limitation, not assumed benign.

<a id="e8"></a>
### E8. Non-model configuration-ablation effect sizes
**Priority:** Should-have · **Effort:** High · **Stage:** all · **Relation:** new

- **Definition.** Estimate the effect of the two knobs with the clearest decision value: `guardrails.mode` (`disabled`/`lenient`/`strict`) and
  `synthesis.allowed_checkers` (`all`/`custom_only`/`builtin_only`). Three further knobs are optional follow-ups if the run budget allows:
  `synthesis.freedom_level` (`level0`/`level1`/`level2`), `prompt.tell_non_triviality_error`, and `repair.local_variable_annotations` (`frozen`/`allowed`).
  Per-stage model keys are deliberately excluded because S2 estimates them with a dedicated paired design. Effect size per knob is the change in `eval.verdict` distribution, in E1 lift, and in `fixer.remaining_type_errors` relative to the default config, with cost from C2.
- **Instrumentation.** The planned `evaluation_runs.config_snapshot` serializes the active config; add a `config_hash` and a `run.config_applied` event
  listing every knob value, so `GROUP BY config_hash` reconstructs arms. A one-factor-at-a-time schedule is the default; full factorial is out of scope
  for a two-person project. Each arm reuses the same verified sample set and turn budgets, and the entry reports the sample size (start with 8-12 verified commits for each knob, not the full corpus).
- **Justification.** The `§5` matrix claims to enable A/B testing (README principle 2) but nothing measures whether strict guardrails improve fix
  quality or merely block cheats (X-4, I-4). It also tests whether hinting at non-triviality biases the synthesis agent toward vacuous lattices, and
  whether `synthesis.freedom_level` changes helper count.
- **Expected learnings.** We expect `strict` to lower cheat-reaching verdicts but cost more turns, and `builtin_only` to raise success on nullness bugs
  while lowering it elsewhere (D-22). A coefficient table per knob with the default as reference would show this. Surprising: `disabled` matching
  `strict` on success, which would argue the guardrails are not load-bearing.
- **Reporting.** Effect-size table with bootstrap CIs over commits, paired only within the same sample set and turn budget. Threat: many knobs multiply
  cost, so scope the grid to the two or three knobs with the clearest research question and label the rest as fixed.

### Section 9 instrumentation notes

Shared fields for this section live on one `evaluation_runs` row per `(commit_hash, baseline_arm, config_hash)`: `run_id`, `commit_hash`,
`baseline_arm`, `guardrails_mode`, `config_hash`, `verdict`, `checker_new_errors`, `regression_test_passed`, `existing_tests_failed_count`, and
`partial_credit`. S5 emits `fixer.turns_taken`/`fixer.remaining_type_errors` and the arm identity; the single `eval.arm_completed` event closes each
arm, while `eval.fidelity`, `eval.collateral`, and `verify.ground_truth_fix` are appended by the offline scoring pass or Step 6b. E2-E7 and the offline checks are joins across `evaluation_runs`, `step_trajectories.metrics`, and `commits.triage_metadata`; E1 and E8 add runs, and E3 adds one offline Checker pass, but no scoring step itself adds an agent turn.

## 10. Cost, latency and resources

Wall-clock and dollar cost concentrate in the LLM-driven stages: S2 triage is one call per commit, S3 synthesis iterates `javac` up to `agent.steps.synthesis` (default 25) times, S4 applies annotations behind an AST gate (up to `agent.steps.application`, default 20), and S5 repair may spend `agent.steps.fixer` (default 50) turns, each potentially triggering a full `gradle compileJava` with the Checker processor up to `agent.max_checker_invocations` (default 20). S1 ingestion amortizes worktree creation and a pre-warmed Gradle cache, S6 is a single deterministic `gradle test`, and JavaParser work is negligible (implementation_plan §2 estimates parsing at roughly 100-150 ms per commit). The four metrics below decompose that spend by stage, price it per successful outcome, size the machine footprint of untrusted builds, and quantify retry waste. They are deliberately scoped for a two-person course project: cheap counters wherever possible, with any full resource profiler explicitly marked optional.

<a id="c1"></a>
### C1. Stage latency decomposition
**Priority:** Must-have · **Effort:** Low · **Stage:** all · **Relation:** extends `triage.avg_latency_ms`

- **Definition.** Per sample record wall-clock per stage (`t_ingest`, `t_triage`, `t_synthesis`, `t_application`, `t_fixer`, `t_verify`) and,
  inside the LLM-driven stages, split `llm_ms` (API wait) from `build_ms` (Gradle) and `checker_ms` (Checker pass). Report
  `total_latency = sum(stage_ms)` with p50/p90 per stage. S1 and S6 are pure subprocess time, so the interesting split is S5's turn count
  multiplied by Gradle+Checker cost.
- **Instrumentation.** Extend `step_trajectories` with `started_at`/`completed_at` and a `metrics.timing` block
  `{wall_ms, llm_ms, build_ms, checker_ms, gradle_invocations, checker_invocations}`; each step wrapper emits a `step.timing` event and
  `evaluation_runs` aggregates `total_wall_ms`. Record whether a Gradle daemon was warm, because reuse hides build cost.
- **Justification.** The plan must fit a two-person course window (X-8), and the repair loop with `agent.steps.fixer` = 50 turns over a minutes-long
  Gradle+Checker cycle is the likeliest schedule risk (D-11). Attribution tells us whether to raise turn budgets or cut Checker invocations.
- **Expected learnings.** We expect S5 to dominate because each turn can trigger a full processor-enabled `gradle compileJava`; a per-stage duration
  bar chart would confirm or refute this. Surprising: S3 dominating instead, which would point at cold cache and jar packaging rather than the fixer
  loop.
- **Reporting.** Stacked per-stage latency bars with p90 highlighted, plus a per-turn latency survival curve for S5. Aggregate p50/p90 per stage across
  samples and report the share of total wall time spent in S5 versus all other stages combined. Threat: daemon state and machine load make absolute
  times non-portable, so report relative shares within one machine.

<a id="c2"></a>
### C2. Token and monetary cost per outcome
**Priority:** Must-have · **Effort:** Low · **Stage:** all · **Relation:** extends `eval.token_usage`

- **Definition.** Sum prompt+completion tokens per stage from `triage.token_usage` and `eval.token_usage`, add per-arm tokens (E1), and convert to
  dollars using model-specific price tables looked up from `config_snapshot.model.*`. Report `cost_per_sample` and
  `cost_per_full_success = total_cost / count(full_success)`; when there are zero successes, report N/A and an exact one-sided interval rather than dividing or imputing infinity. Report the three E1 arms separately so the control's price is visible and the true
  price of one cure is not understated.
- **Instrumentation.** The planned OpenCode runner parses JSON events; write `prompt_tokens`, `completion_tokens`, `total_tokens`, and `model_id` into
  `step_trajectories.metrics` per step. A Python price table keyed by `model_id` with a documented snapshot date joins cost offline; no new runtime
  code and no pipeline dependency on pricing.
- **Justification.** The baseline arms and the E8 ablations multiply LLM spend, so the project needs a dollar denominator to decide how many samples it
  can afford. It also shows whether `fixer_no_types` is a cheap control or nearly as expensive as the treatment.
- **Expected learnings.** We expect S5 to dominate token spend because diagnostics and diffs are resent each turn; a cost-by-stage bar and a scatter of
  tokens versus `fixer.turns_taken` would show this. Surprising: S3 costing more than fixing despite fewer turns, which would flag large payloads in
  `placement_guide.md` or lattice sources.
- **Reporting.** Cost table (stage x arm) with mean/median per sample and per success, and a cumulative spend curve over the run order. Threat: free-tier
  models can be rate-limited or withdrawn, so dollar cost understates the wall-clock opportunity cost.

<a id="c3"></a>
### C3. Compute and resource footprint
**Priority:** Nice-to-have · **Effort:** Medium · **Stage:** all · **Relation:** new

- **Definition.** Size the machine footprint per sample: peak resident memory of the Gradle/Checker JVM, CPU-seconds, worktree disk bytes, shared Gradle
  cache size, and counts of `javac`/Checker/Gradle invocations. Because the pipeline builds untrusted repositories (D-23), peak memory and CPU also
  inform the sandbox resource caps, and worktree bytes bound how many arms E1 can run concurrently.
- **Instrumentation.** Lightweight sampling around each subprocess (`/usr/bin/time -v` or a wrapper reading `getrusage`), plus `du` on the worktree and
  cache at teardown; emit `resource.sample` with `{peak_rss_kb, cpu_ms, worktree_bytes, checker_invocations}`. Full cgroup or per-process profiling is
  optional and should be enabled only for the synthetic repo.
- **Justification.** A run that leaks memory or balloons a worktree silently reduces sample throughput, and the sandbox contract needs a memory/CPU
  ceiling to be enforceable. It also indicates whether parallelising samples is memory-bound or cache-bound; because E1 runs three arms per sample, the
  worktree-bytes and peak-memory figures set how many samples can run concurrently before the host swaps or the build OOMs.
- **Expected learnings.** We expect Checker passes to dominate CPU and the fixed qual-jar/cache to dominate disk; a resource-by-stage table would confirm
  this. Surprising: per-run disk growth from a non-ephemeral worktree, pointing at I-17 teardown gaps that would also break E1's parallel arms.
- **Reporting.** Per-sample resource distribution (median/IQR) plus a peak-memory maximum; flag the profiling pass as optional and scope-limited to one
  repo. Threat: host load and container differences make CPU-seconds noisy, so compare within one machine only.

<a id="c4"></a>
### C4. Retry and waste amplification
**Priority:** Should-have · **Effort:** Low · **Stage:** S3/S4/S5 · **Relation:** extends `fixer.guard_rejections`

- **Definition.** Quantify work that does not advance the run: `application.ast_guard_rejections`, `fixer.guard_rejections`, discarded E1 arms, and timeout or `fixer_incomplete` runs. Define `waste_ratio = wasted_turns / total_turns` separately for S3, S4, and S5, where a turn is wasted when it fails to change the stage's target state (no error-count reduction in S5, no accepted annotation in S4, no compilable jar progress in S3) or trips a guard, and report `abandoned_run_cost` as the C2 cost
  accumulated before a kill. `synthesis.compiler_feedback_rounds` is a cost, not automatically waste: a round that fixes a compile error is productive and is counted in C2, not in the numerator. C4 is spend and outcome accounting; I3 owns repeated actions and I2 owns error-set dynamics.
- **Instrumentation.** Compute per-turn error deltas from the S5 trajectory and the already-planned guard-rejection counters; use
  `evaluation_runs.status='timeout'` for kills. Emit `waste.turn` with `{turn_index, errors_before, errors_after, guard_blocked}` so the ratio is
  reconstructable without replaying the transcript.
- **Justification.** C1 shows turns are the expensive unit, so a high waste ratio means budgets are consumed thrashing rather than converging, and E8's
  ablation cost is largely waste. It also measures whether the active defense guards redirect the fixer productively or just reject repeatedly (I-4).
- **Expected learnings.** We expect guard rejections to cluster early then fall as the model adapts; a `waste_ratio > 0.4` on real repos would argue for
  raising the cap or improving diagnostics. Surprising: high waste with a low final error count, meaning many turns are spent shaving the last errors.
- **Reporting.** Turn-versus-errors curves per arm and waste-ratio boxplots by `guardrails.mode`, plus a table of killed runs and the fraction of killed
  runs whose best error count was already zero, which separates pure budget waste from genuine `fixer_incomplete`. Threat: "wasted" is a stage-specific judgment; a compiler round that enables a later fix can look unproductive, and a rejected annotation can still inform the next turn, so report per-stage definitions and a manual spot check.

### Section 10 instrumentation notes

Shared fields for this section are emitted by each step wrapper into `step_trajectories.metrics` and rolled up onto `evaluation_runs`: `run_id`,
`step_name`, `wall_ms`, `llm_ms`, `build_ms`, `checker_ms`, `prompt_tokens`, `completion_tokens`, `model_id`, `gradle_invocations`, and
`checker_invocations`, with `resource.sample` carrying `peak_rss_kb`/`cpu_ms` and `waste.turn` carrying the per-turn error delta. The orchestrator
writes `evaluation_runs.total_wall_ms` and a stage-cost rollup at run completion. Timing fields must never be used as correctness criteria: they are
environment-dependent and therefore outside the determinism guarantees of R2.

## 11. Reproducibility, provenance and determinism

Reproducing this pipeline means pinning three moving parts: the target repository state (the ephemeral `C-1` worktree off a local mirror), the toolchain (JDK, Gradle wrapper, Checker Framework, and the generated `custom-quals.jar`), and the stochastic LLM stages (S3 synthesis, S4 application, S5 fixer) whose prompts and model versions change across a four-week course timeline. The planned `evaluation_runs` DDL reserves a `config_snapshot JSON` column, but nothing defines its contract or records the artifacts needed to re-run a sample. The four metrics below define a provenance manifest, a replay-determinism rate, environment/model drift detection, and artifact retention with third-party replayability. They are prerequisites for any E1-E8 or C1-C4 number to be checkable by someone outside the two-person team.

<a id="r1"></a>
### R1. Provenance-manifest completeness
**Priority:** Must-have · **Effort:** Low · **Stage:** S1/S6 · **Relation:** new

- **Definition.** Generate one manifest per run with the fields needed to identify it: `commit_C`, `commit_C-1`, mirror remote and default branch,
  worktree path, `config_snapshot` JSON, SHA-256 hashes of the four prompt templates (triage, synthesis, application, fixer), `model_id` and reported
  model version, Checker Framework version, JDK vendor+version, Gradle wrapper version, SHA-256 of `custom-quals.jar`, seed and temperature, OpenCode
  version, and `guardrails.mode`. Score `manifest_completeness = present_fields / required_fields`.
- **Instrumentation.** Extend `evaluation_runs.config_snapshot` with a `provenance` block and also write a standalone `manifest.json` in the per-run
  artifact directory. Hashing happens at S1 (repo and worktree) and at S6 (qual-jar and verdict); a `run.manifest` event records the resolved fields so
  a missing value is visible rather than silently null.
- **Justification.** The pipeline mixes a pinned repository state, a pinned toolchain, and stochastic LLM stages; without every field a re-run is not
  identifiable and the Section 9 and 10 numbers cannot be checked. It is the concrete contract D-8 implies metrics need: only quantities the pipeline
  actually emits (checker version, prompt hash) are recorded.
- **Expected learnings.** We expect completeness to reach 100% for git and toolchain fields and to slip on model version strings, which provider APIs
  rarely expose; a field-presence heatmap would show this. Surprising: a field nobody populates, such as seed, if OpenCode offers no seed control,
  which itself constrains R2.
- **Reporting.** Completeness table per run and per field, plus a list of nullable fields with reasons. Threat: hashing prompt files does not capture
  runtime prompt assembly, so the manifest must also record the renderer version.

<a id="r2"></a>
### R2. Replay determinism
**Priority:** Should-have · **Effort:** Medium · **Stage:** S3/S5/S6 · **Relation:** new

- **Definition.** For a sample subset, re-run under the identical manifest and provider settings and measure agreement: verdict equality,
  `regression_test_passed` equality, canonicalized `diff_patch` identity, and equality of the Checker error set. Report
  `determinism_rate = identical_verdicts / replayed_runs`, separately for deterministic stages (S1, the S4 AST gate, S6) and stochastic stages (S3,
  S5). A `diff_identical` sub-count separates verdict agreement from patch agreement. Because the runner passes no seed flag today, "identical" means identical manifest plus provider defaults until seed and temperature control exist; record `provider_nondeterminism` when those fields are unavailable and mark R2 blocked rather than claiming bitwise replay.
- **Instrumentation.** Add `replay_of_run_id` to `evaluation_runs`; retain both `diff_patch` and `compiler_diagnostics`; canonicalize ASTs with
  JavaParser before comparison. Record `seed` and `temperature` in `config_snapshot` when the provider exposes them. The same S3/S5 replays feed the seed-sensitivity analysis (metric S3), but only when the settings match; a replay at fixed settings and a seed sweep are different designs and share runs only where both apply.
- **Justification.** An LLM-plus-build pipeline is not reproducible by default; knowing which stages are stable tells us whether a single-run verdict is
  trustworthy or needs k samples, and it validates the R1 manifest as sufficient. Without this, an E1 lift could be noise across arms.
- **Expected learnings.** We expect S1/S4/S6 to be exactly deterministic and S3/S5 to vary, with lower verdict agreement on borderline samples such as
  `type_success_runtime_fail`. A determinism matrix by stage would show this. Surprising: nondeterministic S6, which would implicate Gradle parallelism
  or test-order flakiness rather than the LLM.
- **Reporting.** Determinism rate with Wilson CIs and a list of flip cases for manual review; report the `diff_identical` rate alongside verdict
  agreement, since a stable verdict can still hide a materially different patch. Threat: temperature 0 is not guaranteed reproducible across providers
  or model versions, so the same manifest can still diverge; without seed control, R2 measures a mixture of provider nondeterminism and task variance.

<a id="r3"></a>
### R3. Environment and model drift
**Priority:** Should-have · **Effort:** Low · **Stage:** all · **Relation:** new

- **Definition.** Track the toolchain and model identity across the study and flag any change: `model_id` and version, Checker Framework version, JDK,
  Gradle wrapper, and dependency-cache generation. Define `env_hash` over these resolved fields and detect when runs collected before and after a change
  are pooled in one analysis, so drift becomes a coefficient rather than a silent confound.
- **Instrumentation.** Write `env_hash` into the R1 manifest; a lightweight probe at run start records resolved binaries and versions into
  `evaluation_runs.config_snapshot.provenance`. Analysis joins runs and groups by `env_hash`, so a drift boundary is a first-class covariate rather than
  an accident.
- **Justification.** LLM endpoints can be silently updated and Checker/Gradle releases change diagnostic wording, so a shift in success rate might be an
  environment change rather than a finding. This protects E1 lift and E6 predictors from confounds across a four-week run.
- **Expected learnings.** We expect at least one model or cache change mid-study given the timeline; a run-order plot of success colored by `env_hash`
  would reveal it. Surprising: no drift at all, which simply makes the manifest cheap insurance.
- **Reporting.** A drift timeline annotated with version transitions, plus a fixed probe sample re-run after each change. When drift is detected,
  re-run that probe under both `env_hash` values and report the paired difference, so the version change is quantified rather than merely noted. Threat:
  providers may not expose a version, so two genuinely different models can share one `env_hash`.

<a id="r4"></a>
### R4. Artifact retention and third-party replayability
**Priority:** Must-have · **Effort:** Low · **Stage:** S6 · **Relation:** new

- **Definition.** Define which artifacts are retained per run and whether a third party can replay: the annotated `C-1` diff, the fixer `diff_patch`, the
  `T_reg` patch, `C`'s non-test source patch, `custom-quals.jar` plus its source and lattice manifest, `placement_guide.md`, `compiler_diagnostics`, the `agent_transcript`, and the R1 manifest. Report
  `replayable_rate = runs with a complete bundle / runs` and record bundle size and the count of bundles missing each required artifact.
- **Instrumentation.** Write a per-run artifact directory referenced from `evaluation_runs`; store a SHA-256 per artifact and list paths and sizes in
  `evaluation_runs.artifact_manifest JSON`. Note a retention policy because full `agent_transcript` JSON is by far the largest item and may be sampled rather
  than kept for every run.
- **Justification.** E1-E8 and R2 all depend on retained artifacts; without retention, results become
  unreproducible once SQLite rows are pruned or the worktree is torn down. It also makes the E3 ground-truth port auditable by a third party. The proposal promises a working system and a paper with empirical results, not an explicit benchmark release; retention is what makes even that narrower claim checkable.
- **Expected learnings.** We expect small bundles for `builtin_checker` samples and large ones for custom lattices with long transcripts; a size
  histogram would show storage pressure. Surprising: many runs missing `placement_guide.md`, exposing an I-2-style gap where the S3-to-S4 handoff
  artifact was never persisted.
- **Reporting.** Replayable rate by repo and by E1 arm, plus an artifact-size distribution and the median bundle size per arm, since custom lattices with
  long transcripts dominate storage. Threat: transcripts can embed repository source, so retention must respect the target repos' licenses and must strip
  any credentials captured in build logs.

### Section 11 instrumentation notes

Shared fields for this section are written by the orchestrator at run start and completion: `run_id`, `manifest_hash`, `env_hash`, `prompt_hashes`,
`seed`, `temperature`, `model_id`, `checker_version`, `jdk`, `gradle`, `jar_sha256`, and `replay_of_run_id`. They land both in
`evaluation_runs.config_snapshot.provenance` and in a per-run `manifest.json` under the artifact directory, with `run.manifest` emitted once S1 has
resolved the worktree and S6 has resolved the qual-jar. These fields are the join keys for R2-R4 and the provenance backbone for every number reported
in Sections 9 and 10.

R1, R3, and R4 are deliberately separated but share one source of truth. R1 scores whether the provenance fields are present; R4 scores whether the
artifacts those fields point to are present and replayable; R3 derives its drift flag from changes in `env_hash` between runs. All three read
`manifest.json` and `artifact_manifest`, so a missing field is counted once in R1 and not again as an independent defect in R3 or R4.

---

## 12. Failure modes and error taxonomy

Every one of the six stages has a documented way to fail, but the 27 registry metrics record each failure in its own dialect: Step 2 writes `triage_status` and discard reasons into `commits`, Step 4 counts AST-gate rejections in `application.ast_guard_rejections`, Step 5 folds guard hits into `fixer.guard_rejections`, and Step 6 collapses everything into one of five `eval.verdict` strings. There is no shared vocabulary for failures that cross stages, such as a mis-clustered annotation set at S4 that stalls the blind fixer at S5, or for harness failures like the absent watchdog flagged in I-11. The four metrics here add a multi-label failure taxonomy (F1), an ordered cascade view over it (F2), an attempt-level profile keyed to the active defense matrix of design §4 (F3), and survival analysis over the turn budgets that D-11 calls implausible (F4). All four are built from quantities the stages can actually emit, respecting critique D-8's rule.

<a id="f1"></a>
### F1. Unified multi-label failure taxonomy
**Priority:** Must-have · **Effort:** Medium · **Stage:** S1-S6 · **Relation:** new

- **Definition.** Every non-success terminal is labeled from a shared enum stored per run, extending the five `eval.verdict` values with stage-level codes:
  triage/test-portability `T-NOTEST`, `T-TESTCOMPILE`, `T-TESTPASS`, `T-BUILDBASE`, `T-MISCLASS`; synthesis `Y-NONPURE`, `Y-VACUOUS`, `Y-COMPILE`; application
  `A-ASTREJECT`, `A-TRIVIAL`, `A-NOCLUSTER`; repair `R-EVAD`, `R-STUCK`, `R-OVERFIX`, `R-MISGUIDED`; verification `V-FLAKY`; infrastructure `H-HANG`, `H-BUILD`,
  `H-CACHE`. Runs carry a multi-label `failure_codes` array; warning-level codes may co-occur with `full_success`. Split the vocabulary into codes computable from existing verdicts and counters (`T-NOTEST`, `T-TESTCOMPILE`, `T-TESTPASS`, `T-BUILDBASE`, `A-ASTREJECT`, `A-TRIVIAL`, `R-EVAD`, `R-STUCK`, `H-HANG`, `H-BUILD`, `H-CACHE`) and codes that need a new detector or label (`T-MISCLASS`, `Y-NONPURE`, `Y-VACUOUS`, `A-NOCLUSTER`, `R-OVERFIX`, `R-MISGUIDED`, `V-FLAKY`). Report `taxonomy_coverage = runs with at least one assigned code / non-success runs`, and use `unassigned` rather than guessing a semantic code.
- **Instrumentation.** `S2Triage` maps each `discard_reason` to a `T-*` code (resolving the enum drift I-18 documents); `Y-VACUOUS` comes from T4's canary/vacuity result; the JavaParser gate's
  `Invalid(reason)` becomes `A-ASTREJECT`; `R-STUCK` derives from `fixer.remaining_type_errors > 0` at the turn limit; `V-FLAKY` requires a repeated `T_reg` run and is left `unassigned` until that exists; `R-OVERFIX` and `R-MISGUIDED` require a review label and are left `unassigned` until H3 runs; the future watchdog emits `H-HANG` (I-11). Codes are written into `evaluation_runs.run_metrics JSON` and queried in Datasette.
- **Justification.** `eval.verdict` distinguishes only the four S6 outcomes; commits discarded in Step 2, halted at the non-triviality check (I-8), or killed by
  harness crashes leave no comparable record, so one SQL question, "which stage dominates attrition?", is currently unanswerable. The taxonomy decides where
  engineering effort goes: test adaptation (D-4) versus the 11 missing guardrail detectors (I-4) versus synthesis prompt work.
- **Expected learnings.** Expect `T-TESTCOMPILE` and `R-STUCK` to dominate attrition; if `A-ASTREJECT` dominates instead, the Step 4 applier, not the fixer, is
  the bottleneck. Cross-tabbing codes against `guardrails.mode` should concentrate `R-EVAD` in `disabled` runs. A surprising prevalence of `Y-VACUOUS` or
  `Y-NONPURE` would implicate the selected Level 1 + 2 hybrid freedom policy in `synthesis.freedom_level`.
- **Reporting.** Stacked bar of code frequency by stage plus a co-occurrence (UpSet-style) view; bootstrap CIs clustered by commit; report `taxonomy_coverage` and list the codes still `unassigned`. Threat: codes are
  multi-label, so counts are of labeled runs, not exclusive bins, and until implementation exists the taxonomy is exercised only on `synthetic-id-swap`.

<a id="f2"></a>
### F2. Failure cascade and co-occurrence
**Priority:** Should-have · **Effort:** Low · **Stage:** S1-S6 · **Relation:** new

- **Definition.** Per run, record the ordered failure history `cascade = [(stage, code, turn_or_attempt), ...]`, including within-stage retries (AST-gate
  feedback loops, synthesis `javac` rounds). Derived quantities: cascade depth (stages failed before termination), recovery rate (runs where a stage failed then
  succeeded later in the same run), and transition probabilities `P(code_j at stage k+1 | code_i at stage k)`. Captured at run end by folding per-step
  `step_trajectories` rows.
- **Instrumentation.** The planned `step_trajectories` stores `step_number`, `success`, `turns_taken`, and `metrics JSON` per step; a post-run fold in
  `analysis/evaluate_metrics.py` joins rows by `run_id` ordered by `step_number` and appends `cascade JSON` to `evaluation_runs`. Within-stage loops need one
  addition: timestamp each `application.ast_guard_rejections` event and each synthesis compiler-feedback round so retries are orderable.
- **Justification.** The design §2 mermaid diagram has no failure back-edges and incomplete verdict mapping (D-12); today an S5 failure cannot be attributed to
  an upstream S4 artifact versus an intrinsic repair difficulty. Cascade data turns "the run failed" into "S4 misplacement preceded the S5 stall", which is the
  causal attribution any retry-policy decision (proposal §C.3 fallbacks) requires.
- **Expected learnings.** Hypothesis: most `R-STUCK` runs carry a preceding `A-NOCLUSTER` or weak error-localization precursor (cross-ref T3); if cascades rarely
  span stages, each stage can be improved independently. A diagonal-dominated transition matrix would support stage-local retry-with-feedback loops. Surprising:
  frequent S2-to-S5 cascades would mean triage admits commits for which no qualifier lattice can be useful.
- **Reporting.** Transition heat map plus a cascade-depth histogram per `eval.verdict`; Fisher's exact test for code-pair association. Threat: with
  `limits.commit_depth: 30` per repo and heavy S2 attrition (D-4), transition probabilities rest on few runs; report raw counts beside rates.

<a id="f3"></a>
### F3. Guardrail-attempt and evasion profile
**Priority:** Must-have · **Effort:** Medium · **Stage:** S5 · **Relation:** extends `fixer.guard_rejections`

- **Definition.** For each active defense vector in design §4, count per Step 5 run: `attempts` (agent produced the forbidden construct), `blocks` (guard rejected it
  under the active `guardrails.mode`), and `escapes` (construct present in the final `diff_patch` but undetected in that mode). Profile = attempts per 100 fixer
  turns, stratified by mode, plus `vector_coverage = instrumented_vectors / 19`. Escapes are measured post hoc by re-running the strict-mode detectors over final patches of `disabled`/`lenient` runs; vectors with
  no detector yet (11 of 19 per I-4) are reported as `unmeasurable`, not as zero attempts. Until those detectors exist, report the profile only over the implemented subset and state its coverage.
- **Instrumentation.** The planned `AstGuard.validateStep5` and `BackdoorDetector` return `Invalid(reason)`; extend that reason with `vector_id` (1-19) and log a `guard_event
  {vector_id, action: block|warn|allow, turn_index, snippet_hash}` into `step_trajectories.metrics JSON`. Vectors enforced by flags in `checker.init.gradle` (raw
  types, unsafe casts, array covariance, purity) emit events from parsing Checker output instead. An offline escape audit over `diff_patch` writes `escape_audit
  JSON` per run.
- **Justification.** Mode A exists precisely to measure "how frequently LLMs naturally attempt to cheat" (design §4), and README §2, principle 3, promises defense-in-depth,
  yet only a subset of vectors is fully implemented (I-4). Without per-vector attempt counts, the disabled-mode experiment is unrunnable and strict-versus-lenient
  conclusions are anecdotal; the profile also prioritizes which detectors to build next.
- **Expected learnings.** Expect `@SuppressWarnings` and utility-cast attempts to dominate, with signature-drift and dead-code attempts rarer; a spike in
  build-argument tampering (`-AskipDefs`, `-Awarns`, `.astub`) would elevate the read-only-build guard over AST checks in priority. If lenient-mode escapes never
  survive Step 6 verification, full extended hardening drops in urgency.
- **Reporting.** Per-vector grouped bars (attempts, blocks, escapes) faceted by `guardrails.mode`; report `vector_coverage` beside every profile; McNemar on paired commits run under two modes. Threat: the
  escape audit reuses the same detectors, so an unimplemented vector (e.g., reflection/`Unsafe`) is invisible until coded; spot-review a random patch sample to
  bound undercounting.

<a id="f4"></a>
### F4. Budget and timeout exhaustion profile
**Priority:** Must-have · **Effort:** Low · **Stage:** S3-S5 · **Relation:** extends `fixer.turns_taken`

- **Definition.** Turn-budget survival analysis for the three agent stages (synthesis cap 25, application cap 20, repair cap 50, exposed in the design as `agent.steps.synthesis`/`agent.steps.application`/`agent.steps.fixer` and in the plan YAML as `agent.steps.synthesis`/`agent.steps.application`/`agent.steps.repair`): record per step `turns_used`, `exhausted` (cap hit), `resolved` (stage goal met: compilable `custom-quals.jar`, `>=1` Checker error,
  or 0 errors), and `killed` (wall-clock/watchdog kill, I-11). Treat turns-to-resolution as event time with cap-exhaustion and harness kills as right-censored,
  and estimate Kaplan-Meier-style curves per stage; also record wall-clock per turn since Checker runs take minutes (D-11).
- **Instrumentation.** The planned `step_trajectories` includes `turns_taken` and `success`; add `budget JSON {cap, exhausted, killed, wall_ms, error_count_at_cap}` per
  step. `OpenCodeRunner` is specified to count `step_start` events; the watchdog recommended in I-11 should emit `H-HANG` with a kill timestamp, which F4 consumes as a
  censored observation rather than a hard failure.
- **Justification.** D-11 argues the original 8/15-turn defaults were implausible for real Gradle plus Checker loops (prompting their upward revision to 25/20/50), but offers no empirical baseline; meanwhile
  `evaluation_runs.status` distinguishes `running`/`success`/`failed`/`timeout`. Survival curves separate "the agent converged and the budget was adequate" from "the
  cap truncated a converging run", which is the evidence needed to trade budget against token cost (C2).
- **Expected learnings.** Hypothesis: S5 curves show whether resolutions cluster within early turns or whether the expanded 50-turn budget captures a long tail of multi-file fixes;
  if instead resolutions cluster in the first few turns and stalls never resolve, raising `agent.steps.repair` is waste. A bimodal S3 curve would separate
  easy lattices from structurally hard commits (cross-ref E6 difficulty strata).
- **Reporting.** Kaplan-Meier plot per stage faceted by `eval.verdict`, reporting medians and restricted mean turns; log-rank comparison between model arms
  (cross-ref S2). Threat: a "turn" is not a homogeneous unit (one turn may run `javac`, another edit 30 files), so wall-clock curves must accompany turn curves;
  small N makes curves coarse.

### Section 12 instrumentation notes

F-metrics share four additions: a `failure_codes` array and a `cascade` array on `evaluation_runs.run_metrics JSON`, `guard_event` records inside `step_trajectories.metrics JSON` carrying {vector_id, action, turn_index, snippet_hash}, and a per-step `budget` JSON with {cap, exhausted, killed, wall_ms, error_count_at_cap}. All are written by small logging hooks at the stages' existing decision points (the discard-reason write in Step 2, the AST gate's `Invalid(reason)`, the future watchdog kill per I-11), with one post-run fold deriving the F2 cascade. No stage behavior changes; the fields exist so one Datasette query can group runs by failure vocabulary instead of by table.

## 13. Robustness and sensitivity

The experimentation matrix in design §5 exposes twelve toggles, but the project's actual budget is a four-week data window (proposal §E), two planned evaluation repositories (`eval-repos/synthetic-id-swap` and `eval-repos/cmsx`, both currently empty), and multi-minute Gradle plus Checker Framework loops per attempt, so a full model-by-stage-by-guardrail cross-product is out of reach. The metrics below are therefore designed as reduced experiments: a handful of prompt paraphrases rather than a sweep (S1), one-factor-at-a-time model swaps rather than a matrix (S2), pass@k with small k (S3), bootstrap and leave-one-out reanalysis of runs that will exist anyway (S4), a small targeted perturbation probe instead of the design §7.7 mutation campaign (S5), and one-dimensional threshold scans over logged data (S6). Full model matrices, large paraphrase sweeps, and mutation campaigns are marked optional or scope-limited in each block. Together they answer whether reported verdicts are properties of the pipeline or of incidental choices.

<a id="s1"></a>
### S1. Prompt-paraphrase sensitivity
**Priority:** Should-have · **Effort:** Medium · **Stage:** S2-S5 · **Relation:** new

- **Definition.** For each prompt template (`templates/prompts/triage.md`, `synthesis.md`, `application.md`, `fixer.md`), author K=3 semantic-preserving
  paraphrases (constraint reordering, rewording, verbosity changes) and run the same verified commits under each variant with model and config held fixed.
  The full design is 12 variants per commit, which is too expensive; start with the `fixer.md` paraphrase on 5-8 verified commits and add templates only if that
  comparison is stable. Metrics: per-stage verdict-flip rate, defined as the fraction of (commit, variant) pairs whose `eval.verdict` (or triage label at S2) differs from the
  canonical template's, plus the dispersion of `turns_taken` and token usage across variants.
- **Instrumentation.** Add `prompt.template_id` and `prompt.variant_id` to `config_snapshot` in `evaluation_runs` (a new dimension on template identity, not yet
  tracked anywhere); store the three variants per template in the repo alongside the canonical prompts; run variants as ordinary evaluation runs so every
  downstream join keeps working. Paraphrase authoring is a one-off manual task, not a runtime dependency.
- **Justification.** All 27 registry metrics silently treat the prompt as a constant, but README principle 2 demands empirical configurability; without this
  metric, a verdict difference between prompt variants can be misread as a design failure or a model-capacity limit of the kind D-13 identifies. If verdicts
  swing across meaning-preserving rewrites, stage-level metrics measure the template as much as the stage, and this bounds the noise floor of F1 and E-series comparisons.
- **Expected learnings.** Expect the single-shot triage prompt (S2) to be most sensitive, since label flips change benchmark membership itself and feed the
  selection-bias worry in D-4 and P-9; deterministic S6 should be zero-sensitivity by construction. A surprising result: synthesis paraphrases frequently flipping
  `checker_category` between `custom_qualifier_hierarchy` and `builtin_checker` would show lattice design is nearly arbitrary under prompting. Flip rates below a
  few percent would justify freezing templates.
- **Reporting.** Per-stage flip-rate table with exact binomial CIs; paired per-commit comparison (McNemar, canonical versus variant). Scope-limited: 3 variants, 5-8 commits, no paraphrase-by-model interaction (deferred to S2). Threat: paraphrases authored by the same team that wrote the template under-represent natural variation,
  echoing P-9's single-team-bias concern.

<a id="s2"></a>
### S2. Model and stage sensitivity
**Priority:** Should-have · **Effort:** High · **Stage:** S2-S5 · **Relation:** new

- **Definition.** One-factor-at-a-time model swaps: hold all stages at `model.default`, then re-run the verified-commit set with exactly one stage key changed
  (`model.triage`, `model.synthesis`, `model.application`, `model.fixer`), using at most 2 alternative models per stage. Report per-stage deltas in
  `eval.verdict` distribution, `turns_taken`, token usage (cross-ref C2), and F1 failure-code mix, pairing runs by `commit_hash` so each commit is its own
  control. A full 4-stage by N-model matrix is optional and out of scope; start with `model.fixer` on 8-12 verified commits, then add one other stage only if the fixer contrast is stable.
- **Instrumentation.** The `model.*` keys are specified in `implementation_plan.md` §9; add the resolved model string per step into `step_trajectories.metrics JSON`
  (today the resolved per-stage model is only inferable from `config_snapshot`), enabling within-run attribution. Record model version/date for the R3 drift log.
- **Justification.** D-13 flags that the default `nvidia/nemotron-3.5-super-vl-free` may be weak for code reasoning and that unmeasured model capacity
  masquerades as design failure; critique D-1 further demands baseline arms before any causal claim. Stage-level swaps localize where capacity actually matters
  (blind repair versus lattice synthesis versus commit classification), directly informing whether the default stays for headline results or is relegated to
  ablation arms.
- **Expected learnings.** Hypothesis: `model.fixer` dominates outcome variance while `model.triage` dominates benchmark-composition variance; if swapping the
  synthesis model barely moves verdicts, either lattices are structurally simple or the fixer rescues weak ones. A surprising parity between a strong external
  model and the free default at S5 would suggest the task, not the model, is limiting, or that the benchmark is too easy (cross-ref E6).
- **Reporting.** Paired per-commit slopegraph of verdicts with bootstrap CIs clustered by commit; McNemar on `full_success` per swapped stage. Threat: free-tier
  model IDs change behavior under the same name over time (R3 drift), so snapshot versions and avoid comparing unpaired historical runs.

<a id="s3"></a>
### S3. Seed and temperature sensitivity (pass@k)
**Priority:** Should-have · **Effort:** Medium · **Stage:** S3-S5 · **Relation:** new

- **Definition.** Re-run the stochastic stages (S3 synthesis, S4 application, S5 repair) with k=3 independent replicates per verified commit, holding config
  fixed and varying only the seed; fix and record temperature where the API exposes it. Metrics: per-commit pass@k (at least one replicate reaches
  `full_success`), verdict unanimity across replicates, and the coefficient of variation of `turns_taken`. For k=3 report raw pass rates and the standard
  unbiased pass@k combination when sampling multiple candidates per replicate.
- **Instrumentation.** Key `evaluation_runs` by (commit_hash, replicate_index, seed); add `seed` and `temperature` to `config_snapshot`. The `OpenCodeRunner`
  invocation in plan §6 passes no seed flag today, so seed control is best-effort: record whatever the CLI defaults to and note uncontrolled nondeterminism.
- **Justification.** The core hypothesis is currently evaluated on single stochastic draws, so `eval.verdict` conflates "the types cannot guide repair" with "the
  fixer got unlucky on this seed", biasing E1 lift estimates and the F1 failure mix alike. pass@k separates capability from reliability, and reliability is the
  quantity a developer adopting the tool would experience.
- **Expected learnings.** Expect high within-commit variance at S5 given a 15-turn agentic loop; if replicates are unanimous, single-draw evaluation is adequate
  and budget can shift to more commits (D-4 yield). Surprising: S3 lattices that differ qualifiably across seeds yet yield identical S6 verdicts would indicate
  many equivalent type designs for the same bug, motivating the §7.7 perturbation view (S5).
- **Reporting.** pass@1 versus pass@3 with bootstrap CIs per stage; a commit-by-replicate unanimity heat map. Scope-limited to the synthetic repo plus a few cmsx
  commits. Threat: k=3 yields wide pass@k intervals, and server-side nondeterminism beyond temperature is uncontrolled; report per-replicate verdicts, not just
  aggregates.

<a id="s4"></a>
### S4. Resampling and leave-one-repo-out sensitivity
**Priority:** Must-have · **Effort:** Low · **Stage:** S1-S6 (offline reanalysis) · **Relation:** new

- **Definition.** Offline reanalysis of the recorded run table requiring no new pipeline executions: (a) bootstrap CIs clustered by commit, and by repo once two
  repos exist, for headline rates (`full_success` rate, `triage.yield_rate`); (b) leave-one-repo-out (LOO) recomputation of every reported rate with each repo
  removed; (c) leave-one-commit-out jackknife to identify commits that single-handedly flip a conclusion. Applies to every rate this project publishes, not to
  one privileged number.
- **Instrumentation.** Implemented in `analysis/evaluate_metrics.py` over joins of `evaluation_runs`, `commits`, and `step_trajectories`; repo identity from
  `repositories.repo_id` via `commits.repo_id`. Emit a `resampling JSON` artifact per results table, stamped with the git hash of the analysis script and the
  run-id set, so any paper number is traceable to its resampling inputs.
- **Justification.** With `limits.commit_depth: 30` and heavy S2 attrition (D-4's test-porting loss), the verified corpus may number in single digits per repo,
  so a headline like "X% full_success" can be a statistic of three commits; E-series and F-series claims inherit that fragility. This metric forces clustered CIs
  and a repo-sensitivity flag onto every published rate, directly answering P-9's selection-bias challenge.
- **Expected learnings.** Hypothesis: clustered CIs will be wide enough that most stage-level comparisons are inconclusive, and that honest finding should shape
  the paper's claims; if removing one commit flips a sign (jackknife dominance), the result belongs in case-study form, not rate form. Surprising: tight CIs
  despite small N would mean a large, consistent effect worth reporting confidently.
- **Reporting.** BCa bootstrap (about 10k resamples, clustered) beside each rate, plus a LOO min-max column in results tables. Threat: bootstrap with fewer than
  roughly 10 clusters is itself unreliable, so pair it with exact binomial intervals when a rate is per-commit Bernoulli and always print the cluster count.

<a id="s5"></a>
### S5. Annotation-perturbation brittleness
**Priority:** Nice-to-have · **Effort:** High · **Stage:** S4-S6 · **Relation:** new

- **Definition.** A small targeted probe inspired by design §7.7 Option C (mutation testing), explicitly scope-limited to a few runs in place of a full mutation
  campaign: take an accepted S4 annotation placement and apply M=2-3 deterministic perturbations, such as dropping one qualifier from a parameter of the buggy
  method, widening one qualifier to its supertype, or swapping one annotation to a neighboring parameter, then re-run only S5 and S6. Primary metric:
  verdict-flip rate under perturbation; high flip rates mark the repair signal as idiosyncratic rather than semantically targeted.
- **Instrumentation.** Perturbations applied by a small JavaParser mutator reusing the AstGuard parsing path; each mutation logged as `mutation {type, file,
  member, qual_from, qual_to}` in `step_trajectories.metrics JSON` with a `parent_step_id` linking to the unperturbed S4 row. Mutated worktrees follow the
  per-run isolation rules so the qual jar cannot cross-contaminate (cross-ref L4, I-15).
- **Justification.** Design §7.7 motivates the adversarial applier because a cooperative S4 may pick annotations that make the fixer's job artificially easy;
  verdict metrics alone cannot distinguish a semantically load-bearing lattice from a coincidental one. A verdict that survives perturbation is stronger evidence
  that the type design, not the placement lottery, guided the blind fixer.
- **Expected learnings.** Expect perturbations inside the buggy method to flip verdicts most often and periphery perturbations rarely; if even buggy-method
  mutations leave `full_success` intact, the lattice is over-permissive and the design §7.1 adversarial game becomes the necessary next test. Surprising: a
  perturbation that improves the verdict would indicate annotation noise at S4 worth auditing with T5-style agreement checks.
- **Reporting.** Flip rate per mutation type with exact CIs; paired comparison against each parent run's verdict. Scope-limited to verified successful runs, so N
  is small. Threat: some mutations will simply fail to compile; count those as censored (akin to `A-TRIVIAL`) rather than flips, and state M and N explicitly.

<a id="s6"></a>
### S6. Threshold and budget sensitivity
**Priority:** Should-have · **Effort:** Low · **Stage:** S2-S5 (offline reanalysis) · **Relation:** new

- **Definition.** Sensitivity of reported rates to numeric knobs re-scoreable from logged data without new runs: the `triage.bugfix_threshold` cutoff over stored
  `bugfix_score` values (scan thresholds, recompute corpus membership and `triage.yield_rate`), the Step 4 non-triviality floor (1, 3, or 5 Checker errors), and the
  repair success target (0 errors versus 0 new errors relative to the clean `C-1` baseline, per D-22). Turn-budget sensitivity is reported as observed
  `resolved_by_turn_k` from F4's per-turn logs, with runs still erroring at the cap treated as censored; do not report a counterfactual `fixer_incomplete` rate at caps above the observed maximum without actually running those turns. Each headline rate is reported as a curve over the knob, not a point.
- **Instrumentation.** Requires two dependencies: continuous scores must actually be emitted (populate `bugfix_score`/`triage.continuous_scores` when §7.4 lands;
  mark the metric blocked until then, per D-8) and F4's `error_count_at_cap`/per-turn error series. The scans live in `analysis/evaluate_metrics.py` and write a
  `sensitivity JSON` artifact per knob, keyed by run-id set.
- **Justification.** D-4's yield worry, D-22's baseline-error redefinition, and D-11's budget critique all hinge on cutoffs chosen a priori with no sensitivity
  analysis; a success rate that changes when the turn cap moves from 15 to 20 is a property of the cap, not of type-guided repair, but that claim requires either
  observed runs at both caps or explicit censoring. This is the structural defense against P-9's cherry-picking critique.
- **Expected learnings.** Expect triage yield to be highly threshold-sensitive near the bugfix cutoff, with many borderline commits, but the `full_success` rate
  among verified samples to be flat across `triage.bugfix_threshold` values; a surprising sensitivity of success to the 0-versus-0-new error target would
  quantify how much pre-existing Checker noise (D-22) distorts `eval.verdict`.
- **Reporting.** One small-multiples panel per knob (rate versus threshold) with clustered CI bands; preregister the default cutoff before scanning. Threat:
  knobs interact (bugfix cutoff by turn cap), so two-dimensional scans are scope-limited to the two knobs that F4 and D-4 show to be most influential.

### Section 13 instrumentation notes

S-metrics share three declarations made at run start: `prompt.template_id` and `prompt.variant_id` plus the resolved per-step model string written into `config_snapshot` and `step_trajectories.metrics JSON`, and `replicate_index` with `seed`/`temperature` on `evaluation_runs`. The reanalysis half (S4, S6) needs no new pipeline executions at all: it reads `bugfix_score`, the per-turn error series, and F4's `budget` records, and emits `resampling JSON` and `sensitivity JSON` artifacts stamped with the analysis-script git hash so every offline number stays traceable to its inputs.

## 14. Contamination and leakage

The benchmark is assembled from historical commits of public repositories by scanning `limits.commit_depth` commits per repo, and every stage is driven by LLMs trained on public code, so the fixes being "rediscovered" may already sit in model weights. The design compounds this with an information-flow asymmetry: Step 2 and Step 3 legitimately consume the commit message, PR title, and PR body of `C`, while the Step 5 blind fixer must work from annotated `C-1` and Checker diagnostics alone. Critique D-2 documents that this blindness is leaky by construction through `placement_guide.md` and diagnostic clustering, and I-15/I-17 add engineering channels: the fixed `/tmp/custom-quals` jar path and shared Gradle caches can carry state between runs. The four metrics below separate these risks into temporal exposure (L1), behavioral memorization (L2), a per-run audit of leakage channels including prompts, guides, diagnostics, git metadata, test names, and the filesystem (L3), and cross-run shared-state contamination (L4).

<a id="l1"></a>
### L1. Temporal contamination and knowledge-cutoff exposure
**Priority:** Must-have · **Effort:** Low · **Stage:** S2 · **Relation:** new

- **Definition.** For each verified sample, compute `age_at_cutoff = model_cutoff - C_date` from the `commits.author_date` column and the documented training
  cutoff of the model configured for each stage, then assign an exposure bin: `pre_cutoff` (the fix likely predates training), `post_cutoff` (the fix postdates
  the model), or `unknown`. Report `eval.verdict` and F1 failure codes stratified by bin; the headline quantity is `exposure_rate = N_pre_cutoff / N_verified`
  for the verified corpus.
- **Instrumentation.** Compute the exposure bin in an analysis view keyed by `(run_id, stage, model_id)` rather than freezing a run-specific value on `commits`. A maintained `model_cutoffs` lookup keyed
  by model ID supplies the cutoff date; models without a published or explicitly sourced cutoff get `unknown`, never a guessed date. Stratified queries join `evaluation_runs.verdict`; no agent-side changes, so
  this can ship before any run executes.
- **Justification.** The benchmark's public-repository historical fixes are exactly the material LLMs are trained on, so a `full_success` on an old CMSX fix may
  be recall of the human patch rather than type-guided repair, and no guardrail from design §4 can detect that. Stratification is the only defense available
  without a private corpus, and bins must be fixed before results are seen, or the audit degenerates into post-hoc excuse-making.
- **Expected learnings.** With `limits.commit_depth: 30`, the scan window is recent, so the bin split depends on how old the window reaches relative to model
  release dates; expect a mix of `post_cutoff` and `unknown`. A surprising dominance of `pre_cutoff` samples would reframe all S5 successes as
  recall-contaminated and argue for pushing depth into pre-LLM-era commits; parity of success rates across bins would substantially allay contamination worries.
- **Reporting.** Exposure-bin table (N and exact binomial CI per bin) printed beside every headline rate; exploratory logistic regression of success on exposure
  bin. Threat: author dates differ from PR merge dates and cutoffs are often unpublished, so `unknown` must be reported as its own bin and never silently folded
  into another.

<a id="l2"></a>
### L2. Benchmark memorization probe
**Priority:** Should-have · **Effort:** Medium · **Stage:** S2 · **Relation:** new

- **Definition.** A behavioral contamination probe run once per verified commit before interpreting results: prompt each configured stage model with only the
  buggy method from `C-1` plus its signature context, and ask (a) to describe the bug and its fix in free text, and (b) to select the fix from multiple choices
  including the actual human diff. Distractors are human diffs from other commits in the same repository, matched by changed-line count and touched-file count, with option order randomized; the free-text
  rubric is a three-point scale (same repair, related repair, unrelated) applied by one reviewer on a sample. Score `memorized` on semantic agreement with the human diff of `C`; report `memorization_rate` across verified samples,
  cross-tabbed with L1 exposure bins.
- **Instrumentation.** One-shot probes (no agent loop) logged as synthetic `step_trajectories` rows with the additive `step_name = 'memorization_probe'`; responses stored in
  `agent_transcript JSON`; target fixes reused from the `C` source patch already extracted during Step 2, and distractors drawn from the same scan. This costs two calls per commit (describe, select), not one. Automatic scoring uses normalized token overlap against the
  human diff as a screening signal; the three-point rubric is the primary free-text judgment, and human judging can fold into the H2 workflow.
- **Justification.** L1 infers exposure from dates; this measures it behaviorally. If the fixer model can recite the human patch from buggy code alone, Step 5
  success is uninterpretable as evidence for the README's type-guidance hypothesis, and the D-1 baseline arms (`fixer_no_types` versus `fixer_typed`) become the
  only valid evidence; the probe is cheap, roughly two triage-equivalent calls per commit (C2).
- **Expected learnings.** Expect low free-text memorization but higher multiple-choice hit rates, since choice lists cue recall; a surprising high free-text rate
  on niche CMSX commits would indicate strong repo-specific training exposure and justify labeling or excluding those samples. Divergence between the triage
  model's and the fixer model's probe scores would inform the S2 model-swap design.
- **Reporting.** Memorization rate with exact binomial CI, cross-tabbed against L1 bins and E1 lift estimates. Threat: probe phrasing modulates recall (an
  S1-style sensitivity), and free-text scoring is subjective; report automatic and, if run, human-judged rates side by side rather than merged.

<a id="l3"></a>
### L3. Leakage-channel audit
**Priority:** Must-have · **Effort:** Medium · **Stage:** S3-S5 · **Relation:** new

- **Definition.** A deterministic per-run audit of six channels carrying information about `C` to the blind fixer beyond Checker diagnostics: (1) PR or commit
  text from the synthesis prompt (Step 3 legitimately sees it per D-2) copied verbatim into artifacts; (2) `placement_guide.md` wording that names the bug,
  beyond the location-level hints the redefined blindness permits; (3) diagnostic content (location leakage, quantified via T3/SNR); (4) git metadata in the
  worktree, such as a reachable future commit or PR text in `git log`; (5) `T_reg`'s fully qualified test names extracted in S2 appearing in S5 prompts or
  readable test files; (6) leftover filesystem artifacts (guide drafts, probe outputs, stale qual jars per I-15) in the S5 working directory. Metric: per-channel
  binary finding plus evidence snippet, aggregated as `leakage_findings JSON`.
- **Instrumentation.** A post-run audit script: token-grep the retained S5 prompts and `agent_transcript` for PR title/body tokens and `regression_test_name` strings, reporting `leakage_audit_coverage = audited_runs / total_runs`;
  check reachability from the worktree's `HEAD` and the refs actually visible to the agent, not `git log --all` (worktrees share the mirror's object store, so `--all` would flag the future commit by construction); assert that a known future commit is unreachable; inventory the S5 cwd
  before agent start; n-gram match `placement_guide.md` against PR text. Findings written to `evaluation_runs.run_metrics JSON`.
- **Justification.** D-2's remedy is explicit: measure leakage rather than assume blindness away, yet every result table's "blind" prefix is currently
  unverified. Each channel maps to a concrete fix (sanitize guide wording, strip history from worktrees, relocate artifacts), so the audit directly generates the
  remediation backlog, and channel (4) firing would invalidate all prior runs pending replay (R2).
- **Expected learnings.** Expect channels (2) and (3) to fire on most runs, given the design's location-level blindness, while textual channels (1) and (5)
  should be rare and count as genuine defects. Surprising: any (4) or (6) finding would mean harness contamination, not design leakage, and would block
  trustworthy data collection until fixed.
- **Reporting.** Per-channel finding counts plus a severity table splitting textual from location-level leakage and the audited fraction; after remediation, replay affected runs and
  compare verdicts with McNemar pairing. Threat: n-gram matching over-flags coincidental identifier overlap, so flagged cases receive a one-line human
  confirmation, reusing the H-section rating workflow.

<a id="l4"></a>
### L4. Cross-run and shared-state contamination
**Priority:** Should-have · **Effort:** Low · **Stage:** S1 · **Relation:** new

- **Definition.** Audit of state persisting across runs that could carry artifacts or configuration between commits: (a) the fixed qual-jar path
  `/tmp/custom-quals/custom-quals.jar`, where a stale jar from run X could be consumed by run Y if `CUSTOM_QUAL_JAR` is not per-run (I-15); (b) the shared Gradle
  user home from design §3 Step 1, benign for dependencies but risky for task-output caching; (c) OpenCode CLI session reuse across commits; (d) stale
  `data/pipeline.db` rows leaking into analysis joins unless every query keys on `config_snapshot`. Metric: `state_leak_incidents` count per channel, plus a CI
  canary running one commit twice back-to-back with different qual jars and asserting jar-hash provenance.
- **Instrumentation.** Record the qual-jar hash in `config_snapshot` and assert it at S5 start, emitting the F1 `H-CACHE` code on mismatch; verify worktree
  freshness with `git rev-parse --verify` on the per-run branch; log OpenCode session IDs per `runAgent` call; run the double-execution canary in CI on
  `synthetic-id-swap`.
- **Justification.** I-15 documents the collision risk and I-17 the unspecified worktree lifecycle; if qual jars cross-contaminate, verdicts and failure codes
  attach to the wrong runs, corrupting every metric in this document. The canary also verifies that the per-run isolation fix actually shipped, which the
  implementation plan currently tests nowhere.
- **Expected learnings.** Expect channel (a) to fail the canary until the per-run path fix lands, and (c)/(d) to be clean under the single-process CLI design; a
  surprising (b) hit, such as compiled test outputs cached across commits, would require cache-key work in S1 before any latency metric (C1/C3) is trustworthy.
  Canary pass status doubles as an ongoing harness-health signal.
- **Reporting.** Incidents per channel over time as a control chart, plus a canary pass/fail badge in CI; incident-free runs carry an `isolation_verified` flag
  usable as an analysis filter. Threat: the canary exercises only the synthetic repo, so multi-module Gradle cache behavior in cmsx may exhibit channels the
  canary cannot see.

### Section 14 instrumentation notes

L-metrics add a `model_cutoffs` lookup table, an exposure view over `(run_id, stage, model_id)`, `memorization_probe` rows in `step_trajectories`, and `leakage_findings JSON` plus `state_leak_incidents` on `evaluation_runs.run_metrics JSON`, alongside per-run artifact hashes (qual jar, `placement_guide.md`) recorded in `config_snapshot`. All four audits run offline after a batch rather than inside the agents being audited, so a leaking agent cannot influence its own audit trail; L3 and L4 additionally emit findings before any verdict analysis is trusted.

## 15. Human evaluation

The proposal states two co-equal goals in §B: types that meaningfully prevent domain-specific bugs and an approach that does not impose significant developer burden, yet critique P-3 and X-1 show the burden conjunct is measured nowhere, and P-9 shows the manually curated benchmark has no written triage protocol or inter-rater agreement. The repository contains no human study and no human-labeled data of any kind, so every metric in this section is new and each costs manual effort the current plans do not schedule. The four metrics close the gap where the pipeline already depends on human judgment: the manual triage decisions behind `commits.triage_status` (H1), the semantic quality of `custom-quals.jar` and `placement_guide.md` that the Checker Framework cannot judge (H2), the acceptability of the blind fixer's patch relative to the human fix in `C` (H3), and the proposal's burden goal (H4). H1 and H3 are Should-have because they audit existing claims; H2 is Nice-to-have and H4 is explicitly optional and scope-limited, matching the remedy P-3 offers.

<a id="h1"></a>
### H1. Human triage and preventability agreement
**Priority:** Should-have · **Effort:** High · **Stage:** S2 · **Relation:** new

- **Definition.** Two independent raters (the two team members; proposal §E describes a two-person team) label the same stratified sample of 40-60 scanned commits used by D2. D2 owns axis (i), the bugfix/non-bugfix
  labels and their Cohen's kappa; H1 adds axis (ii), statically preventable versus infeasible, the proposal §C.1 classification the design dropped (X-2).
  Metrics: Cohen's kappa for axis (ii), rater-versus-LLM agreement on axis (i) against `commits.is_bugfix`/`bugfix_score`, rater-versus-`triage_status` agreement, and
  intra-rater kappa on a 10-commit re-label after a two-week gap. Reusing D2's sample avoids a second labeling effort and keeps the two metrics' denominators identical.
- **Instrumentation.** A labeling sheet rendered from Datasette keyed by `commit_hash`, showing the diff, commit message, and PR body with no downstream outcome
  information; labels stored in `triage_gold_label` with `annotator_id` and `dimension`-equivalent columns, or in `human_ratings` when a null `run_id` is used; the LLM label joins from existing `commits`
  columns. No pipeline-stage changes required.
- **Justification.** P-9 flags that the same team curates the benchmark subjectively, inviting a cherry-picking critique, and P-12 notes the triage heuristic is
  unvalidated while its errors propagate to everything downstream. Without kappa numbers, `triage.yield_rate` inherits unquantified curator bias; low axis-(ii)
  agreement would also explain why X-2's preventability screen disappeared from the design, forcing an explicit decision to restore or descope it.
- **Expected learnings.** Expect high agreement on axis (i) for clear bugfixes and moderate agreement on axis (ii), since preventability is a judgment about
  lattice feasibility; a very low axis-(ii) kappa (under about 0.4) would argue for treating "preventable" as a noisy post-hoc label rather than a benchmark
  inclusion rule. LLM-rater agreement below rater-rater agreement quantifies triage-model headroom (cross-ref D2).
- **Reporting.** Kappa tables with raw agreement and prevalence notes, plus per-axis confusion matrices. Threat: only two raters, both system authors, so there
  is no external validity; publish the written protocol and every disagreement resolution so third parties can re-audit labels.

<a id="h2"></a>
### H2. Expert annotation-quality rubric
**Priority:** Nice-to-have · **Effort:** High · **Stage:** S3-S4 · **Relation:** new

- **Definition.** Rubric-based human rating of pipeline artifacts on a sample of runs: the qualifier hierarchy in `custom-quals.jar` (S3 sources retained) and
  `placement_guide.md` are scored 1-5 with anchored definitions on four dimensions: (a) semantic fit, whether qualifiers name the domain invariant the bug
  violated; (b) generality, whether the design would catch sibling bugs or is bug-specific (the P-8 tautological-checker worry); (c) placement precision, the
  fraction of S4-placed annotations a reviewer marks correct, idiomatic, unnecessary, or harmful (this fraction is the primary numeric output); (d) guide
  clarity, whether a developer could apply `placement_guide.md` by hand.
- **Instrumentation.** Reviewers work from a Datasette page per run bundling the qualifier sources, the guide, and the annotated diff from
  `step_trajectories.diff_patch`, with a stated blinding condition (bug context hidden or shown); ratings land in `human_ratings` {run_id, dimension, label_or_score,
  rater_id, review_minutes, notes}. An optional second reviewer on a subsample yields kappa.
- **Justification.** The Checker Framework enforces consistency, not meaning: a vacuous or bug-specific lattice can pass the canary and non-triviality checks
  (D-7, P-8) and still be worthless as a type design, and `synthesis.qualifiers_count` says nothing about quality. This metric supplies the semantic validity
  check missing from T4 and E-series, backing the paper's usefulness claim (proposal §B goal 1) under X-9's prevention-versus-repair reframing.
- **Expected learnings.** Expect (b) generality to score lowest because Step 3 sees the specific bug description (D-2); high (c) with low (b) would characterize
  the pipeline as precise-but-narrow, a concrete input for the design §7.6 falsification loop. Surprising: high guide clarity with low precision would localise
  information loss to the S4 applier rather than the guide (cross-ref P3).
- **Reporting.** Per-dimension box plots with rater counts; placement-precision fractions with Wilson CIs. Scope-limited: 10-15 runs rated by one reviewer,
  second-rater kappa optional. Threat: reviewers are the system's authors and cannot fully blind themselves to their own design intent; acknowledge author bias
  and publish all rating sheets.

<a id="h3"></a>
### H3. Patch acceptability review
**Priority:** Should-have · **Effort:** High · **Stage:** S6 · **Relation:** adds dimension to `eval.verdict`

- **Definition.** For each run reaching Step 6, a reviewer inspects the blind fixer's final `diff_patch` against the human fix (the diff of `C`, recomputed from git with `git show C` or read from the retained `C` source patch; it has no column in `commits`) and assigns one label: `acceptable` (semantically equivalent to the human fix), `acceptable_variant` (different but sound), `unacceptable` (passes
  `T_reg` for the wrong reason, such as special-casing test inputs), or `harmful` (introduces latent defects). Primary metrics: the acceptability rate among
  machine-`full_success` runs and the machine-versus-human disagreement rate, which is the reward-hacking detector of last resort.
- **Instrumentation.** Review packets per run, rendered in Datasette from `step_trajectories.diff_patch` and the `C` diff, optionally hiding the run config and
  `T_reg` name until judgment to mirror blindness; labels stored in `human_ratings` with `dimension = 'patch'` and joined to `evaluation_runs.verdict`; the
  reviewer logs minutes per patch so H4 can reuse the timing data.
- **Justification.** `eval.regression_test_passed` reflects exactly one test, and D-18's observation that helper calls are live code, plus the dead-code and cast
  vectors (D-21, design §4), show how a patch can satisfy tests while violating intent; with 11 of 19 detectors unimplemented (I-4), some evasions necessarily
  escape automated guards. This review tells us whether the headline `full_success` rate needs a true-success discount.
- **Expected learnings.** Expect most passing patches to land in `acceptable_variant`, since agent-found fixes rarely byte-match the human patch, with a small
  unacceptable tail concentrated in runs also carrying `R-OVERFIX` or `R-MISGUIDED` codes. A surprising cluster of `harmful` labels on test-passing patches would
  trigger expansion of F3's detector set before further data collection; disagreement concentrated in one repo would implicate weak repo tests rather than the
  fixer.
- **Reporting.** Confusion matrix of `eval.verdict` by review label with per-cell counts, and the acceptability rate with an exact CI reported next to the raw
  rate. Scope-limited: one reviewer, all machine-`full_success` runs plus a random sample of failures, capped at roughly 25 patches; this is a review, not a full census. Threat: single reviewer with author bias (mitigated by blinding to config), and the human fix is not the unique correct fix, so `acceptable_variant`
  boundaries are judgment calls; publish the rubric anchors.

<a id="h4"></a>
### H4. Burden and review-time study
**Priority:** Nice-to-have · **Effort:** High · **Stage:** S4-S6 · **Relation:** new

- **Definition.** Optional, scope-limited operationalization of proposal §B goal 2, which P-3 and X-1 show is unmeasured. Reduced design on one primary repository
  (`synthetic-id-swap`) plus a small `eval-repos/cmsx` slice: (i) the E7 machine proxies, reused as the quantitative baseline rather than redefined; (ii) a
  timed task where one reviewer accepts or rejects each annotation set with a stopwatch, recording `review_minutes` and `interventions` (manual edits needed
  to make it usable); (iii) a hand-annotated reference on the synthetic bug as the comparison point. Composite metric: `burden_index =
  review_minutes + 5 * interventions`, with the weight justified by a small pilot or reported as a sensitivity surface over weights rather than asserted as a natural constant.
- **Instrumentation.** Timing and intervention counts stored as `human_ratings` rows {run_id, review_minutes, interventions, notes}; proxies computed offline
  from `step_trajectories.diff_patch` and file sizes; the timing instructions and reference hand-annotation committed to the repo so the protocol is replayable;
  all rows join to `evaluation_runs` on run_id.
- **Justification.** Without this, half the proposal's primary goal is structurally unfalsifiable (X-1, P-3), and the paper could claim efficacy but not
  adoption value; the critique itself offers burden proxies plus a small one-repo review-time study as the acceptable remedy. Proxies are computed from
  already-planned instrumentation, so even if the timed task is dropped, a descope sentence backed by proxy numbers remains defensible.
- **Expected learnings.** Expect review time to scale with annotation count and with the number of non-built-in qualifiers; if interventions concentrate in
  `custom_qualifier_hierarchy` runs rather than `builtin_checker` runs, the adoption argument favors `synthesis.allowed_checkers: builtin_only`. A surprising
  outcome: review time below the hand-annotated reference at equal acceptability would be the project's strongest burden result.
- **Reporting.** Burden-index distributions with the hand reference as a paired baseline (Wilcoxon signed-rank; a single reviewer permits no kappa), and the
  proxies table reported for all runs even if timing is dropped. Scope-limited: one reviewer, one primary repo plus the small cmsx slice, roughly 5-10 timed runs. Threat: single-author timing
  without blinding or external developers makes findings indicative rather than generalizable; state this descope explicitly in the paper.

### Section 15 instrumentation notes

H-metrics share one `human_ratings` table keyed by `run_id` when a pipeline run exists and by `commit_hash` for triage labels, with rater_id, dimension, scores or labels, `review_minutes`, free-text notes, and a blinding flag recording whether the reviewer saw the bug context or run config, so H2 and H3 results can be stratified by visibility. Review packets are Datasette pages assembled from `step_trajectories.diff_patch` and `commits` rows, and the H3 timing writes the same `review_minutes` field H4 reuses, so no observation is entered twice. Kappa and rate computations join this table to `evaluation_runs` on run_id, keeping human judgments in the same relational substrate as machine verdicts.

---
## 16. Prioritized implementation roadmap

Sixty metrics is an inventory, not a work plan. The recommended sequence is driven by one rule: collect first the quantities that make the headline result interpretable, then the quantities that explain it, then the quantities that stress-test it.

### 16.1 Phase 0: before the first real evaluation batch

Phase 0 is deliberately cheap relative to the full inventory. It requires the event envelope from Section 3.4, a pre-placement Checker snapshot, the baseline-arm configuration, and deterministic post-processing of artifacts the pipeline will write. The fifteen low-effort must-haves are all collectable in roughly 8-15 person-days across the two-person team: D1, P1, P4, I2, I4, O1, T1, E7, C1, C2, R1, R4, F4, S4, L1. Add E1 (the three-arm comparison), T3 (localization/SNR), L3 (leakage audit), and F1 (failure taxonomy), which bring the defensible core to nineteen metrics and roughly 16-31 person-days; these four are medium-effort but without them the paper cannot attribute success to the types, cannot measure the blindness claim, and cannot explain attrition.

| Phase | Metrics | Why now | Prerequisite |
| :--- | :--- | :--- | :--- |
| 0 | D1, P1, P4, I2, I4, O1, T1, E7, C1, C2, R1, R4, F4, S4, L1, E1, T3, L3, F1 | Establish the funnel, the information diet, the error baseline, the causal arms, and the audit trail before any result is reported | Event envelope; pre-placement (post-S3) Checker snapshot; `baseline_arm`; manifest |
| 1 | D2, D3, D4, D5, P2, P3, P5, I1, I3, I5, I6, O2, O3, O4, O5, T2, T4, E2, E3, E5, C4, F3, L2, L4, S3, S6, H1 | Explain the Phase 0 numbers: classifier quality, guide specificity, localization, partial outcomes, additional failure channels, and stability | Phase 0 data; manual labels; diff-to-AST mapping |
| 2 | T5, E4, E6, E8, C3, R2, R3, F2, S1, S2, S5, H2, H3, H4 | Stress-test and contextualize the claims; most are optional or scope-limited | Stable pipeline; additional runs; reviewers |

Phase 0 does not require all six stages to be complete. D1, P1, P4, I2, I4, O1, T1, E7, C1, C2, R1, R4, F4, S4, and L1 can be instrumented from the first end-to-end run; E1 requires the baseline arms to exist as configuration, T3 requires `C`'s diff to be mapped to AST scopes, L3 requires the post-run audit script, and F1 requires only the code list.

### 16.2 Dependencies and blocked metrics

| Metric | Blocked on | Can it be approximated meanwhile? |
| :--- | :--- | :--- |
| D2, H1 | A stratified human-labeled sample; two raters | Report raw agreement on 40-60 commits and label it directional |
| E1 | `baseline_arm` column and 3x S5/S6 compute per sample | Run one baseline arm (`fixer_no_types`) first; it is the cheapest control |
| E3 | `C`'s non-test source patch and a fresh worktree seeded from S4 output | Record `patch_conflict` separately; do not count conflicts as rejections |
| T1, T3 | A pre-placement Checker run and a diff-hunk-to-AST-scope mapper | Use file-level localization until method-level mapping exists |
| T5, H2, H3 | Manual annotation or review labor | Use E7 machine proxies and a single-reviewer spot check |
| S1 | Three paraphrased templates per stage | Paraphrase only the fixer template first |
| S2 | Alternative models and run budget | Swap only `model.fixer`, the stage with the largest expected effect |
| S3 | Seed/temperature control in `OpenCodeRunner` | Record provider defaults and treat replicates as a sensitivity check, not a guarantee |
| S5 | A JavaParser qualifier mutator | Use a manual mutation of one successful run as a pilot |
| L2 | One probe call per verified commit | Probe a random subsample of 20 commits |
| R2 | Replay budget | Replay one or two borderline samples, not the whole corpus |
| H4 | Reviewer time | Report only machine proxies (E7) and state the descope |

### 16.3 Effort profile

| Priority | Low | Medium | High | Total |
| :--- | ---: | ---: | ---: | ---: |
| Must-have | 15 | 11 | 1 | 27 |
| Should-have | 12 | 12 | 5 | 29 |
| Nice-to-have | 0 | 1 | 3 | 4 |
| **Total** | **27** | **24** | **9** | **60** |

Using rough planning ranges of 0.5-1 day for low, 2-4 days for medium, and 5-10 days for high, Phase 0 is roughly 16-31 person-days, Phase 1 roughly 45-90 person-days, and Phase 2 roughly 46-92 person-days. The full inventory is therefore about 107-213 person-days, or 53-107 per team member, which does not fit the four-week implementation and data-collection window in proposal §E. The honest reading is that Phase 0 must overlap implementation and is the only phase that fits the data window; Phase 1 is post-data analysis, and Phase 2 is optional paper-revision work to be trimmed first. These ranges are planning estimates, not measured costs.

### 16.4 What not to build

Do not build a general observability platform, a full model-by-stage-by-guardrail matrix, or a large human study before the Phase 0 numbers exist. Do not add a metric whose only consumer is a dashboard. Do not bind a metric to an event schema that is still changing weekly; use the generic envelope and derive the rollup offline until the stage's artifacts stabilize. The inventory is intentionally larger than the first implementation because it documents the target, not because every row must ship.

## 17. Reporting playbook

The metrics only matter if they change what the paper reports. This section specifies the standard artifacts, the statistical protocol, and the minimum disclosure that makes a table checkable.

### 17.1 Standard tables and figures

| Artifact | Metrics | What it shows |
| :--- | :--- | :--- |
| Table 1. Corpus funnel | D1, D4, D5 | Scanned -> buildable -> bugfix -> test found -> compiles -> fails -> verified, with conditional retention by repo |
| Table 2. Main results by arm | E1, E2, E5 | `fixer_no_types`, `fixer_builtin_only`, `fixer_typed`; verdict distribution, lift, collateral, N and CIs |
| Table 3. Failure taxonomy | F1, F2, F4 | Multi-label failure frequencies, cascade depth, budget exhaustion by stage |
| Table 4. Cost and latency | C1, C2, C3 | Wall-clock and dollar cost per stage and per success, with p50/p90 |
| Table 5. Annotation quality and burden | T5, E7, H2, H3, H4 | Coverage, agreement, reviewer acceptability, annotations per KLOC, review minutes |
| Figure 1. Error trajectories | I2, I3, T2 | Checker error count and composition per turn, with convergence and plateau cases |
| Figure 2. Localization | T3 | Tier histogram and SNR per run, stratified by `checker_category` |
| Figure 3. Robustness | S1, S2, S3, S4 | Forest plot of verdict/lift across prompt, model, seed, and resampling perturbations |
| Figure 4. Leakage and contamination | L1, L2, L3 | Exposure bins, memorization probe, per-channel leakage audit |
| Appendix. Per-run table | all | One row per run with config hash, manifest hash, verdict, arm, exclusions |

The main results table should be readable without the appendix: one row per arm, columns for N, `full_success`, `type_success_runtime_fail`, `regression_introduced`, `fixer_incomplete`, lift with CI, and median cost per success. A reader should be able to see whether the typed arm beat the no-types arm before reading any prose.

### 17.2 Statistical protocol

The unit of analysis is the verified sample `(C, C-1, T_reg)`, not the individual LLM call. Arms and replicates are nested within a sample, and samples are clustered within repositories. This has four consequences:

1. **Pair within a sample.** Compare `fixer_typed` to `fixer_no_types` on the same `C-1` with the same turn budget. Use McNemar's test for paired binary verdicts and a paired Wilcoxon signed-rank test for continuous quantities such as turns or cost. Do not compare two independent proportions computed from different subsets.
2. **Cluster uncertainty by repository.** With two repositories, a naive bootstrap over samples understates uncertainty because commits within a repo share build, test, and style. Report exact binomial (Clopper-Pearson) intervals per repo and a repo-stratified summary as the primary interval; use a cluster bootstrap (BCa, roughly 10,000 resamples) over repositories only as a sensitivity check. With fewer than about ten clusters, report the cluster count and treat the bootstrap interval as indicative.
3. **Pre-register the primary comparisons.** Limit the primary set to three: (a) `full_success` lift of `fixer_typed` over `fixer_no_types`; (b) `type_success_runtime_fail` share; (c) cost per success. Treat SNR/tier share, burden proxies, and all other metrics as secondary and label them exploratory. Apply Holm-Bonferroni if a fourth primary is added. A paper that tests sixty metrics and reports the smallest p-value has found noise.
4. **Report absolute rates, differences, and N.** For each rate, give the numerator, denominator, and a Wilson or Clopper-Pearson interval. For each difference, give the paired estimate and a Newcombe or bootstrap interval. Report the number of excluded runs and the reason; an intention-to-treat table (all runs, including harness failures) and a per-protocol table (valid runs only) should both appear when the exclusion rate exceeds a few percent.

Sample size is a design decision, not a post-hoc excuse. Before the first batch, state a target of at least 10 verified samples per repo for the headline rate and a minimum detectable difference of roughly 20 percentage points for the paired lift; if the funnel yields fewer than 8, report the result as a case series with exact intervals rather than a rate. These thresholds are provisional and should be written into the analysis plan before `eval.verdict` is examined.

For continuous outcomes, report median and IQR alongside the mean; LLM cost and latency are right-skewed. For human labels, report Cohen's kappa with its CI, raw agreement, and prevalence, because kappa is unstable when one label dominates. For pass@k with small k, report the per-replicate verdicts and the empirical pass rate, not only a single aggregate.

### 17.3 Minimum disclosure per table

Every table or figure that reports a rate should carry, in the caption or a nearby footnote:

- the number of commits scanned, verified, run, and excluded, with exclusion reasons;
- the `config_hash`, `model.<stage>` values (and resolved versions), prompt template hashes, `guardrails.mode`, Checker Framework version, and JDK;
- the denominator definition, especially whether "zero errors" means raw zero or zero new errors relative to the `C-1` baseline (T1);
- whether the arm is paired and which test produced the interval;
- the analysis-script git hash and the run-ID set for any offline reanalysis (S4);
- any metric that was not collected, and why.

This disclosure is not bureaucracy. In a pipeline with free-tier models, moving defaults, and small samples, an unlabeled rate is not reproducible even when the code is.

### 17.4 Reporting negative or ambiguous results

A flat lift is a result, not a failure to be hidden. Report the point estimate and CI. Distinguish three outcomes against a pre-specified meaningful threshold (for example, a 10-percentage-point lift): (a) no evidence of an effect (CI spanning zero but not lying wholly inside the equivalence margin), (b) evidence of no meaningful effect (CI inside the equivalence margin), and (c) evidence of a meaningful positive or negative effect (CI excludes the margin on one side). "No meaningful causal effect" is the conclusion for (b), not for a CI that excludes the threshold. Each outcome implies a different next experiment.

Three patterns deserve explicit reporting even if they are unflattering. First, if `type_success_runtime_fail` dominates, the lattice models the wrong invariant; report the error taxonomy and a representative case rather than pooling it into failure. Second, if `fixer_no_types` performs as well as `fixer_typed`, the pipeline is an expensive code-repair prompt; report the cost comparison and the leakage audit (L3) before concluding anything about types. Third, if `L2` memorization is high, the benchmark measures recall; report exposure bins and, if the contaminated set is large, consider a post-cutoff subset rather than claiming a general result.

## 18. Threats to validity

| Threat | Mechanism | Metric that quantifies it | Mitigation |
| :--- | :--- | :--- | :--- |
| Construct: one regression test is a narrow oracle | `T_reg` can pass for the wrong reason or fail for an unrelated reason | E2, E3, E4, H3 | Report partial outcomes and reviewer acceptability; do not equate `full_success` with a correct repair |
| Construct: "preventable" is undefined | The proposal's heuristic was dropped (X-2) | D3, H1 | Label preventability explicitly; report rater agreement |
| Internal: pre-existing Checker noise | Built-in checkers emit errors in unrelated legacy code (D-22) | T1, T2, T3 | Report `full_success` and zero-new-error `delta_success` side by side; stratify by checker category |
| Internal: prompt and artifact leakage | PR text, `placement_guide.md`, diagnostics, git metadata, test names | P2, L3, T3 | Sanitize templates, audit channels, replay affected runs |
| Internal: worktree history and cache leakage | The worktree shares the mirror's object store, and Gradle caches persist across runs | L3, L4 | Remove future refs, per-run caches and jar paths, reachability canary |
| Internal: test-oracle quality | `T_reg` may be flaky, under-specified, or fail for an unrelated reason | E5, F1 (`V-FLAKY`), H3 | Repeat `T_reg` on `C-1` and `C`, record failure hashes, review a sample of passing patches |
| Internal: harness failures | Hangs, empty output, unparseable transcripts, stale jars | I4, F1, F4, L4 | Valid-run denominator; watchdog; per-run jar hashes |
| Internal: non-determinism | Sampling, provider drift, test flakiness | R2, R3, S3 | Replicates, seeds where available, fixed toolchain, replay |
| Internal: guardrail mode changes the task | `strict` versus `lenient` changes what the fixer can do | F3, E8 | Report mode with every result; pair modes on the same commits |
| External: two repositories | `synthetic-id-swap` and `cmsx` are not a representative sample | D4, S4 | Per-repo tables and leave-one-repo-out; state the generalization limit |
| External: Java/Gradle only | Maven and other JDKs are deferred | D4, D5 | Report build-tool and JDK coverage; do not generalize |
| External: model family | Results may be a property of one model | S2 | Stage-wise model swaps; report model versions |
| Statistical: small N | Depth 30 and S2 attrition may leave single-digit samples per repo | D1, S4, F4 | Exact intervals, raw counts, case studies, no overfitting |
| Statistical: multiplicity | Sixty metrics invite false positives | Section 17.2 | Pre-register primary comparisons; Holm-Bonferroni; label exploratory |
| Contamination: public historical fixes | The model may have seen `C` and its patch | L1, L2 | Exposure bins, memorization probe, post-cutoff subset |
| Human: author bias | The team labels and reviews its own system | H1, H2, H3 | Written rubric, blinding where possible, second rater on a subsample |

**What would falsify the core hypothesis?** A `fixer_typed` lift whose confidence interval excludes any meaningful advantage over `fixer_no_types`; a high `E3` rejection rate showing the lattice cannot accept the human fix; a high `L2` memorization rate showing the fix was recalled rather than derived; or an SNR so low that the errors the fixer repairs are mostly unrelated to the bug. Each of these is measurable with the metrics above, and each is more informative than another aggregate success rate.

The largest residual threat is not statistical but architectural: the pipeline's "blind" fixer is blind to text, not to location. If L3 shows that the guide and diagnostics reliably point at the buggy method, the paper should describe the result as "type-guided localization plus repair," not "blind discovery." That is still an interesting claim, and it is the claim the data can support.

---

## Appendix A. Proposed event and table schema

### A.1 Event types

| Event type | Emitter | Key payload | Metrics served |
| :--- | :--- | :--- | :--- |
| `run_started` | Orchestrator | `config_hash`, `commit_hash`, `baseline_arm`, `manifest_hash` | E1, E8, R1 |
| `stage_started` / `stage_finished` | Step wrapper | `stage`, `wall_ms`, `success`, `turns_used` | C1, F4 |
| `llm_call` | Runner | `model.<stage>`, `prompt_tokens`, `completion_tokens`, `latency_ms` | C2, I5 |
| `agent_action` | Runner | `turn_index`, `action_type`, `tool_name`, `target_path`, `primary` | I1, I3 |
| `prompt_rendered` | Prompt assembler | `template_id`, `template_sha256`, `segments[]`, `total_input_tokens`, `truncated` | P1, P2 |
| `prompt_conformance` | Renderer | `required_clauses[]`, `forbidden_hits[]`, `conformant` | P2, L3 |
| `artifact_parse` | Post-turn validator | `artifact_type`, `validation_kind`, `parse_ok`, `error_kind`, `bytes` | O1, O3 |
| `ast_gate_result` | S4 validator | `accepted`, `reason`, `stripped_nodes`, `import_delta_only` | O3, O5 |
| `checker_baseline` | Pre-placement runner | `checker_key`, `count`, `error_keys[]` | T1 |
| `checker_after` / `checker_diagnostic` | Build wrapper | `stage`, `checker_key`, `file`, `line`, `origin`, `error_key` | T1, T2, T3 |
| `test_run` / `test_outcome` | S6 runner | `suite`, `test_id`, `status`, `duration_ms`, `baseline_status` | E2, E5 |
| `guard_event` | AST/build guard | `vector_id`, `action` (`block`/`warn`/`allow`), `turn_index`, `snippet_hash` | F3 |
| `rejection_followup` | Post-turn validator | `rejection_id`, `resolved`, `turns_to_resolve` | O5 |
| `budget_snapshot` | Step wrapper | `cap`, `exhausted`, `killed`, `wall_ms`, `error_count_at_cap` | F4 |
| `verify_ground_truth_fix` | S6b | `applied_hunks`, `patch_conflict`, `checker_new_errors`, `accepted` | E3 |
| `human_rating` | Review tool | `dimension`, `label_or_score`, `rater_id`, `blinding`, `review_minutes` | H1-H4 |
| `run_finished` | Orchestrator | `verdict`, `failure_codes[]`, `run_metrics`, `artifact_manifest` | F1, F2, R4 |

The underscore names in this table are canonical. Where an entry in the body uses a dotted variant for readability (for example `prompt.rendered`), the underscore form is what the schema stores.

### A.2 DDL sketch

```sql
CREATE TABLE IF NOT EXISTS metric_events (
    event_id      TEXT PRIMARY KEY,
    run_id        TEXT,             -- null for pre-run triage and offline scans
    scan_id       TEXT,             -- groups triage events that precede any run
    commit_hash   TEXT,
    stage         TEXT,             -- 'S1' ... 'S6', 'S6b', or 'offline'
    turn_index    INTEGER,
    event_type    TEXT NOT NULL,
    ts            TEXT NOT NULL,
    model_id      TEXT,
    prompt_sha256 TEXT,
    payload       JSON NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_run_stage
    ON metric_events(run_id, stage, event_type);

CREATE TABLE IF NOT EXISTS checker_findings (
    run_id          TEXT NOT NULL,
    stage           TEXT NOT NULL,
    turn_index      INTEGER,
    checker_key     TEXT NOT NULL,
    file            TEXT NOT NULL,
    line            INTEGER,
    enclosing_method TEXT,
    origin          TEXT,           -- 'pre_existing' | 'annotation_induced' | 'fixer_induced'
    error_key       TEXT NOT NULL,
    message         TEXT
);

CREATE TABLE IF NOT EXISTS test_outcomes (
    run_id        TEXT NOT NULL,
    suite         TEXT NOT NULL,
    test_id       TEXT NOT NULL,
    status        TEXT NOT NULL,    -- 'passed' | 'failed' | 'skipped' | 'error'
    duration_ms   INTEGER,
    baseline_status TEXT
);

CREATE TABLE IF NOT EXISTS human_ratings (
    run_id        TEXT,             -- nullable for triage/preventability labels
    commit_hash   TEXT NOT NULL,
    rater_id      TEXT NOT NULL,
    dimension     TEXT NOT NULL,    -- 'triage' | 'preventability' | 'annotation' | 'patch' | 'burden'
    label_or_score TEXT,
    blinding      TEXT,
    review_minutes REAL,
    interventions REAL,
    notes         TEXT
);

CREATE TABLE IF NOT EXISTS triage_gold_label (
    commit_hash       TEXT NOT NULL,
    gold_label        TEXT NOT NULL,   -- 'bugfix' | 'non_bugfix' (MVP); multi-class labels are a future analysis-only extension
    preventability_label TEXT,         -- 'preventable' | 'infeasible' | NULL
    bug_class         TEXT,
    annotator_id      TEXT NOT NULL,
    adjudicated       BOOLEAN NOT NULL DEFAULT 0,
    sample_stratum    TEXT,
    inclusion_prob    REAL
);

CREATE TABLE IF NOT EXISTS annotation_placements (
    run_id            TEXT NOT NULL,
    file              TEXT NOT NULL,
    line              INTEGER,
    symbol            TEXT NOT NULL,
    qualifier         TEXT NOT NULL,
    guide_directive_id TEXT,
    tier              INTEGER
);

CREATE TABLE IF NOT EXISTS test_portability_probe (
    commit_hash       TEXT NOT NULL,
    discard_reason    TEXT NOT NULL,
    compile_error_class TEXT,
    missing_symbols   JSON,
    jdk_target        TEXT,
    gradle_exit_code  INTEGER,
    adaptation_candidate BOOLEAN
);

CREATE TABLE IF NOT EXISTS model_cutoffs (
    model_id      TEXT PRIMARY KEY,
    cutoff_date   TEXT,             -- ISO date, or NULL
    source        TEXT,
    confidence    TEXT              -- 'published' | 'unknown'
);
```

The planned `evaluation_runs` table would gain, via `ALTER TABLE ... ADD COLUMN`: `baseline_arm TEXT`, `config_hash TEXT`, `run_metrics JSON`, `artifact_manifest JSON`, `replay_of_run_id TEXT`, `replicate_index INTEGER`, and `env_hash TEXT`. `step_trajectories` would gain `started_at TEXT` and `completed_at TEXT`; `seed` and `temperature` live in `config_snapshot` because they are provider settings, not run identity. These are additive; no existing column changes type or meaning.

### A.3 Example event

The following is an illustrative envelope, not a measured result:

```json
{
  "event_id": "evt_000123",
  "event_type": "checker_diagnostic",
  "run_id": "run_0042",
  "commit_hash": "abc123",
  "stage": "S5",
  "turn_index": 4,
  "ts": "2026-10-19T14:03:11Z",
  "model_id": "configured-fixer-model",
  "prompt_sha256": "…",
  "payload": {
    "checker_key": "subtyping",
    "file": "src/main/java/example/Transfer.java",
    "line": 88,
    "enclosing_method": "transfer",
    "origin": "annotation_induced",
    "error_key": "subtyping|Transfer.java|transfer|argument",
    "tier": 1
  }
}
```

### A.4 Sampling and retention

One event per LLM call, agent turn, build, gate decision, and test run is cheap relative to the model call itself. Full prompt text is the expensive item: keep every S5 prompt (it is small compared with the transcript) and a 10% random sample of S2-S4 prompts plus every run that produces a `full_success` or an unexpected `type_success_runtime_fail`, so qualitative review is possible without storing every prompt. L3 reports the audited fraction when it relies on the sample. Retain patches, diagnostics, manifests, and `custom-quals.jar` for every run; these are small compared with transcripts. If transcript storage becomes a problem, sample transcripts at 25% but keep the derived per-turn events for all runs, because the events, not the raw text, are what the metrics consume.

---

## Appendix B. Metric-to-registry crosswalk

### B.1 Existing metrics that are extended

The 27-metric registry is not replaced. The following 17 existing metrics are extended or given a new dimension by 22 of the 60 proposals; the remaining 38 are new. Existing metrics not listed here are used as covariates, strata, or components rather than re-proposed.

| Existing metric | New IDs | Added dimension |
| :--- | :--- | :--- |
| `triage.yield_rate` | D1 | Per-stage retention and discard decomposition |
| `triage.classification_counts` | D2 | Binary accuracy and calibration against a binary gold standard |
| `triage.commit_metadata` | D4 | Representativeness and selection profile (SMD, repo share, age) |
| `triage.discard_reasons` | D5 | Cause codes for test-portability and baseline-build failures |
| `synthesis.helpers_count` | O4 | Helper API surface, purity, utilization, and call sites |
| `synthesis.qualifiers_count` | T4 | Hierarchy structure, vacuity, and canary outcome |
| `application.ast_guard_rejections` | O3 | Gate accept rate, rejection reason, normalization loss |
| `application.annotations_added_count` | T5 | Coverage denominator and agreement with ground truth |
| `application.total_checker_errors` | T1 | Baseline-adjusted new-error count and residual new errors |
| `fixer.turns_taken` | F4 | Turn-budget survival and cap exhaustion |
| `fixer.guard_rejections` | I6, O5, C4, F3 | Adherence denominator, feedback repair, waste, and per-vector attempts/escapes |
| `fixer.remaining_type_errors` | I2 | Per-turn error-set delta, convergence, and plateau |
| `eval.existing_unit_tests_passed` | E5 | Collateral breadth, failed-test count, and out-of-scope edits |
| `eval.regression_test_passed` | E2 | Outcome vector and partial credit (the boolean remains a component) |
| `eval.verdict` | E1, E2, H3 | Baseline-arm lift, partial outcomes, and reviewer acceptability |
| `eval.token_usage` | I5, C2 | Token efficiency and monetary cost per outcome |
| `triage.avg_latency_ms` | C1 | Full stage-level latency decomposition |

### B.2 Existing metrics used but not extended

`triage.total_commits_evaluated`, `triage.continuous_scores`, `triage.regression_tests_found_count`, `triage.test_methods_extracted_count`, `synthesis.turns_taken`, `synthesis.compiler_feedback_rounds`, `synthesis.checker_category`, `application.turns_taken`, `application.is_trivial`, and `triage.token_usage` remain useful as denominators, covariates, or stratifiers. They are not re-proposed because each already defines a quantity the new metrics consume. "Existing" is not a claim that every field is implemented: `triage.continuous_scores` is future-only per D-8, `application.turns_taken` and `application.is_trivial` are absent from the implementation plan (I-8), and most registry entries are schema-only until the first run writes them. Metrics that depend on an unwritten field (D3's `application.is_trivial`, E7's `application.annotations_added_count`, S6's `bugfix_score`) inherit that dependency and are marked with the blocking conditions in Section 16.2.

### B.3 New-only metrics

The following 38 metrics have no existing counterpart: D3, P1-P5, I1, I3, I4, O1, O2, T2, T3, E3, E4, E6, E7, E8, C3, R1-R4, F1, F2, S1-S6, L1-L4, H1, H2, H4. P2 and P3 are new because no existing metric records the rendered template or the contents of `placement_guide.md`; O1 and O2 are new because no existing metric records parse success or patch shape; T2 and T3 are new because `application.total_checker_errors` is a single count with no taxonomy or location.

### B.4 Duplicate-boundary notes

Several metrics are deliberately adjacent, and the boundary matters:

- **D5 versus F1.** D5 profiles the causes of `test_compilation_failure` and `build_broken_unpatched`; F1 assigns a shared failure code to every terminal. D5 is a diagnostic decomposition, F1 is a cross-stage vocabulary.
- **T1 versus T2 versus T3.** T1 counts baseline membership and the new-error delta; T2 classifies each diagnostic by checker, origin, and responsible placement; T3 maps new diagnostics to localization tiers. T2 reports `unattributed` when more than one annotation covers a line and never substitutes for T1's baseline delta.
- **I3 versus C4.** I3 measures repeated or reverted actions; C4 measures stage-specific non-progress in turns and dollars. A turn can be novel and still wasted, or repetitive and still cheap.
- **I6 versus F3.** I6 measures adherence to prompt rules that may have no guard; F3 measures per-vector attempts, blocks, and escapes when a guard exists. I6 is the behavioral denominator, F3 is the enforcement profile.
- **L1 versus L2.** L1 estimates temporal exposure from commit dates and model cutoffs; L2 probes whether a model can reproduce the fix behaviorally. An `unknown` L1 cutoff is reported as its own exposure bin, not imputed from L2 or treated as missing at random.
- **P2 versus L3.** P2 is a pre-flight assertion on the rendered template; L3 is a post-run audit of every channel, including artifacts, git metadata, test names, and the filesystem.
- **E7 versus H4.** E7 is a set of machine proxies computed for every run; H4 is a small optional human time study that calibrates whether those proxies track perceived burden.
- **E8 versus S2.** E8 estimates non-model configuration knobs; S2 estimates model and stage sensitivity with a dedicated paired design. Model keys are excluded from E8 to keep the two analyses separate.
- **H1 versus D2.** D2 owns the human-labeled sample and the axis-(i) bugfix kappa used to score the LLM classifier; H1 reuses that same sample and adds the preventability axis (ii) plus rater-versus-`triage_status` agreement. They are separate metrics with a shared denominator, not two independent labeling studies.

---

## Appendix C. Glossary

| Term | Meaning in this project |
| :--- | :--- |
| `C` | The historical bugfix commit whose regression test is the ground truth. |
| `C-1` | The commit immediately before `C`; the pipeline synthesizes and applies types here. |
| `T_reg` | The regression test extracted from `C`, required to compile on `C-1` and fail at runtime there. |
| `custom-quals.jar` | The pre-compiled qualifier annotations and pure validator/coercion helpers produced by S3. |
| `placement_guide.md` | The natural-language guide from S3 telling the S4 applier which symbols to annotate and with which qualifiers. |
| Blind fixer | The S5 agent that sees annotated `C-1` and Checker diagnostics but not the commit message, PR text, diff of `C`, or `T_reg`. |
| AST gate | The S4 check that `strip_annotations(AST_original) == strip_annotations(AST_modified)`, intended to permit annotation-only edits. |
| Canary | A fixture the synthesized checker must reject, and one it must accept, used to detect a vacuous lattice. |
| Checker Framework | The Java pluggable type-checking framework used to enforce the synthesized qualifiers. |
| Qualifier hierarchy | The set of type qualifiers and `@SubtypeOf` relations synthesized by S3. |
| `custom_qualifier_hierarchy` | A run in which S3 synthesizes its own qualifiers (as opposed to selecting a built-in checker). |
| `builtin_checker` | A run in which S3 selects a built-in Checker Framework checker such as Nullness, Index, Regex, or FormatString. |
| Freedom level | The S3 policy for what may be synthesized: Level 0 annotations, Level 1 coercions, Level 2 validation guards, with Level 3 wrapper records deferred. |
| `guardrails.mode` | `disabled` (prompt steering only), `lenient` (partial enforcement), or `strict` (enforcement of the implemented vector subset; a subset of vectors fully today). |
| active defense matrix | The attack and evasion vectors enumerated in `design_plan.md` §4. |
| SNR | Signal-to-noise ratio: new errors in the buggy method divided by all new errors (design `§7.3`). |
| `full_success` | `T_reg` passes, all original unit tests pass, and the Checker reports zero errors under the existing verdict. T1 reports a parallel zero-new-errors `delta_success` without renaming the verdict. |
| `delta_success` | The full Step 6 success outcome with `residual_new_errors == 0` relative to the pre-placement Checker baseline substituted for raw zero (T1); an analysis quantity, not a replacement for `full_success`. |
| `type_success_runtime_fail` | The Checker reports zero errors and existing unit tests pass, but `T_reg` fails. |
| `regression_introduced` | The Checker passed but existing unit tests broke. |
| `fixer_incomplete` | The fixer did not eliminate all type errors within the turn limit. |
| Lift | `success(fixer_typed) - success(fixer_no_types)`, the primary causal quantity (E1). |
| New error | A Checker diagnostic not present in the clean-`C-1` baseline (T1). |
| Pass@k | The fraction of samples where at least one of `k` independent replicates reaches `full_success`. |
| Cohen's kappa | Agreement between two raters corrected for chance. |
| ECE | Expected calibration error, used for the triage classifier's continuous score. |
| SMD | Standardized mean difference, used to compare the verified corpus with the scanned population. |

*End of document.*
