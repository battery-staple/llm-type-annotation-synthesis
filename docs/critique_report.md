# Critical Review: Proposal, Refined Design, and Implementation Plan

**Historical note:** This report reviews an earlier draft (design ~335 lines, implementation ~445 lines). The current design and implementation plans have since been revised substantially; use this report for historical context, not as a description of the current files.

**Artifact type:** Independent critique / improvement report (a review artifact, not a project source artifact).
**Scope:** The three research artifacts plus the repository README, reviewed against the project's own stated goals.
**Review date:** 2026-10-19 (repository state: commit `7b48ff6`; working tree currently contains untracked `README.md` and `docs/`).

**Artifacts reviewed**

| Artifact | Path | Size |
| :--- | :--- | :--- |
| Research proposal | `proposal/Project Proposal.md` | 55 lines, §A–§F |
| Refined design | `docs/design_plan.md` | 335 lines, §1–§7 |
| Implementation plan | `docs/implementation_plan.md` | 445 lines, §1–§10 |
| Repository README | `README.md` | 85 lines, §1–§5 |

**Method.** Each artifact was read line by line and audited independently for weaknesses; four cross-cutting audits were then run (README-goal alignment, design↔implementation-plan fidelity, modularity enforcement, future-developments feasibility). Every finding cites a file path plus a section heading or line number and quotes or closely paraphrases the text it rests on. Absence claims about text presence were grep-verified; where a finding depends on file bodies the plan does not show, it is phrased as "no shown mechanism". The report was then adversarially fact-checked against the sources; residual gaps and corrections are disclosed in §11.

**Important context.** The repository contains **no implementation code** — only `README.md`, `proposal/Project Proposal.md`, `docs/design_plan.md`, `docs/implementation_plan.md`, an empty `eval-repos/` directory, and `.gitignore`. All statements about "the plan" describe planned structures, not existing code.

---

## 1. Executive Summary

The three artifacts form a coherent and unusually ambitious research vision: synthesize domain-specific Checker Framework qualifiers from historical bugfixes, apply them mechanically, and let a **blind** fixer agent repair the bug using only static type errors. The refined design is substantially more rigorous than the proposal it grew from, and the implementation plan is a detailed engineering specification rather than a sketch.

However, the three artifacts are **not yet mutually consistent**, and several of the strongest claims of the README's philosophy are not yet enforced by the design or plan. The most consequential gaps are: (1) there is **no baseline/control condition**, so a successful fix cannot be attributed to the synthesized types; (2) the implementation plan reproduces only a **skeleton** of the design — the Step 4 AST gate, the `placement_guide.md` artifact, the canary test, the non-triviality halt, 11 of the 19 attack vectors, and five design config keys have no plan counterpart; (3) **modularity is declared but not enforced** (no `PipelineStep` contract, no dependency injection, no isolated testability); (4) half of the proposal's stated success criteria — *developer burden* — is operationalized **nowhere** in the design's 27-metric registry; and (5) several validity and reward-hacking threats are unmodeled: synthesis sees the bug context, helpers can encode or merely name the fix, constant-return repairs are not a guarded vector, and each bug is run only once.

### 1.1 Verdict summary

| Requirement | Verdict | Where verified |
| :--- | :--- | :--- |
| **F1** Critique the proposal | **Satisfied** (executed; context and risk coverage added in §4.3) | §4 |
| **F2** Critique the refined design | **Satisfied** (executed) | §5 |
| **F3** Design reasonable & likely to achieve meaningful objectives incl. README goal | **Partially satisfied** | §8.1 |
| **F4** Implementation plan matches design exactly | **Not satisfied** | §8.2 |
| **F5** Plan enforces modularity very strongly | **Partially satisfied** | §8.3 |
| **F6** Every future development easily implementable via the plan | **Not satisfied** (3 accommodated / 7 at-risk / 1 impeded) | §8.4 |
| **F7** Evidence-grounded findings | **Largely satisfied** (quotations or close paraphrases with locators; residual gaps disclosed in §11) | throughout |
| **F8** Concrete, actionable recommendations | **Largely satisfied** (every finding has a recommendation; several are cross-references) | throughout |

### 1.2 Top findings (priority order)

1. **No baseline arm** → the core causal claim ("types caused the fix") is untestable. *(D-1)*
2. **Design↔plan fidelity is partial** → Step 4 AST gate, `placement_guide.md`, canary test, non-triviality halt, 15 of the 19 attack vectors (11 missing, 5 partial), and five design config keys are absent or unenforced in the plan. *(I-1…I-8)*
3. **Developer-burden success criterion is never measured.** *(P-3, X-1)*
4. **Blindness and synthesis independence are leaky** — Step 3 sees the bug/PR description; helpers can encode the fix; the fixer's worktree may retain git history. *(D-2, D-24, D-25)*
5. **Modularity is announced, not enforced** — no interface contracts, no DI, no isolated tests; hard-coded toolchain paths and env-var wiring. *(I-21, M-1…M-6)*
6. **Some design guarantees are internally contradictory** — binary vs 5-way triage taxonomy; declarative-only lattice vs `isSubtype` canary; hybrid freedom level vs a 3-value enum; Mode A vs unconditional guards. *(D-3, D-6, D-7, D-33)*
7. **Guardrail mode is never wired from config to build** in the shown plan → the default `lenient` is what the configuration would enforce, contradicting README §2, principle 3. *(I-6, X-4)*
8. **Triage yield may be low** — the design itself concedes that test patches from `C` "often" fail to compile on `C-1`, and the core loop discards such commits instead of adapting. *(D-4)*
9. **Concrete defects in the plan's code and build script** — `prompt: str` does not compile; stderr is never drained; `timeoutSeconds` is unused; the default `SubtypingChecker` invocation is invalid without `-Aquals`; `AstGuard` has false positives and bypasses. *(I-9…I-11, I-25, I-26)*
10. **Proposal promises dropped or escalated** — preventability classification, modular prompt decomposition, failure-driven fallbacks, and the "passes on fixed commit `C`" half of the check are missing, while LLM triage was promoted from a stretch goal to the critical path. *(X-2, P-20)*
11. **No Checker baseline on `C-1`** — enabling built-in checkers over whole legacy projects may surface pre-existing errors, making the "0 errors" success criterion practically unattainable. *(D-22)*
12. **No execution sandbox** for cloning/building untrusted repositories and running LLM-generated helpers. *(D-23)*
13. **Unmodeled reward hacks and overfitting** — a constant-return repair passes the regression test, and a bug-specific lattice can satisfy every gate. *(D-24, D-27)*
14. **Single stochastic run per bug** — no repetitions, temperature, seed, or variance reporting, so a causal claim rests on one sample. *(D-29)*

---

## 2. Severity and Conventions

| Label | Meaning | Rationale |
| :--- | :--- | :--- |
| **Major** | Threatens research validity, a stated goal, or a stated philosophy; or blocks a design guarantee from being implemented at all. | Fixing later costs rework across artifacts. |
| **Moderate** | Causes real ambiguity, contradiction, or rework risk, but does not by itself invalidate results. | Should be fixed before implementation freeze. |
| **Minor** | Clarity, polish, or localized inconsistency. | Cheap to fix. |

Finding IDs: `P-*` proposal, `D-*` design, `I-*` implementation plan, `M-*` modularity, `X-*` cross-cutting, `F6-*` future-development verdicts, and `*-S*` strengths. Requirement verdicts for F3–F6 are in §8. Each finding's **Impact** line is the rationale for its severity: a finding is Major when that impact threatens validity, a stated goal/philosophy, or blocks a design guarantee outright. F6 uses a feasibility axis, mapped to severity as IMPEDED = Major, AT-RISK = Moderate, ACCOMMODATED = no severity (a success); strengths carry no severity because they are not findings.

When an area was examined and found sound, it is stated explicitly in §10; the findings list is not padded to imply otherwise. Known scope-outs are listed in §11.

---

## 3. Cross-Artifact Map

```
proposal/Project Proposal.md ──(evolves into)──► docs/design_plan.md ──(instantiated by)──► docs/implementation_plan.md
        §A–§F (course proposal)                       §1–§7 conceptual design                §1–§10 engineering spec
                       ▲                                      ▲                                      ▲
                       └──────────────────────── README.md §1–§5 (goal, principles, stack) ────────────┘
```

Continuity is analyzed in §7.1; fidelity in §8.2.

---

## 4. Proposal Critique (F1)

Target: `proposal/Project Proposal.md`. Context: `README.md`.

### Strengths (verified)

- **P-S1 — The pipeline is technically concrete at the level of intent.** `§C` step 2 specifies "prompt an LLM to synthesize Checker Framework annotations and supporting classes/methods (e.g., `AnnotatedTypeFactory`s and coercion/sanitization functions)" and step 3 names a "deterministic diff sanitizer". The sanitizer itself is undefined (see P-21), so this is a strength of specificity, not of implementation.
- **P-S2 — Failure modes are paired with contingencies.** `§C` step 3's two sub-bullets give two diagnosed failure modes and responses ("retrying the type generation step with feedback"; "prompt the model one function at a time, with other functions in the callgraph in the context").
- **P-S3 — Ground truth is external to the LLM.** `§D` steps 3–4 use the pre-fix commit and the historical human fix as oracles ("the fix proposed by the LLM after seeing the new types is correct").
- **P-S4 — The cited statistic is accurate.** `§A`: "Rust's memory safety model reduced Google's Android memory vulnerabilities below 20% in 2025 \[1\]". Verified against the cited source (blog.google, *Rust in Android: move fast and fix things*, Nov 13 2025): "memory safety vulnerabilities falling below 20% of total vulnerabilities for the first time."
- **P-S5 — Problem scope is honestly bounded.** `§C` step 1 distinguishes "statically preventable" from "statically infeasible to prevent".

### Weaknesses

**P-1 — No explicit research questions; goals are never operationalized. (Major)**
- **Evidence:** `§B` (lines 7–10): "Our primary goal is to generate type annotations for which: 1. The new types meaningfully prevent domain-specific bugs in practice 2. Creating and instituting the new annotations do not impose a significant burden for developers." No definition of "meaningfully", "in practice", or "burden" appears anywhere, and `§D` states no sample size or success threshold.
- **Impact:** Any outcome can be narrated as success; results are ungradeable.
- **Recommendation:** Add an RQ block after `§B` (e.g., RQ1 = fraction of preventable bugs for which synthesized types fail on `C-1` and pass on `C`; RQ2 = fix rate with vs. without synthesized types; RQ3 = annotation lines per KLOC as a burden proxy). In `§D`, state a target N (e.g., "≥ 8 bugs across ≥ 2 repos") and a per-RQ threshold. Improved state: the paper's evaluation section can be written directly from the RQs.

**P-2 — No related work; novelty is unpositioned. (Major)**
- **Evidence:** `# References` (lines 53–56) contains exactly two entries — the Google blog and "https://checkerframework.org/". There is no Related Work section.
- **Impact:** Neither reviewers nor the authors can see what is new relative to LLM type inference (e.g., Type4Py-style prediction), Checker Framework adoption/effort studies, or LLM program repair.
- **Recommendation:** Add a 6–8 citation Related Work section with an explicit differentiation sentence (e.g., "prior work predicts types for existing annotations; we synthesize *new qualifier designs plus their enforcement logic* and test whether they causally guide repair"). Improved state: a defensible novelty claim.

**P-3 — Stated goal #2 (developer burden) is never operationalized. (Major)** — *(also cross-cutting X-1)*
- **Evidence:** `§B` line 10 (quoted in P-1). The term "burden" appears **nowhere** in `docs/design_plan.md` or `README.md` (grep-verified).
- **Impact:** Half of the proposal's primary goal is unfalsifiable in the current evaluation design.
- **Recommendation:** Either (a) add burden proxies to the design's metric registry (`annotations_per_kloc`, `manual_interventions_count`, and a small human-review time study on one repo), or (b) explicitly descope it in `docs/design_plan.md §1` and `README.md §1` with one sentence ("we approximate annotation burden via added-annotation count and zero-manual-intervention rate; a human study is out of scope"). Leaving the criterion implicit is the main risk.

**P-4 — The distinctive idea (blind fixer) is buried, and the proposal's fixer is not blind. (Moderate)**
- **Evidence:** `§C` step 4: "Prompt a final LLM to resolve the bug by modifying method bodies" — no isolation stated; `§D` step 3 says the system runs "with information about the bug (future: without information about the specific bug?)". Contrast `README.md:10`, where blindness is the core hypothesis.
- **Impact:** With the fixer given the bug, a successful fix cannot be attributed to the types — the proposal forfeits its cleanest experiment and undersells its novelty.
- **Recommendation:** In `§C` step 4, specify two conditions (informed vs. blind fixer that sees only compiler diagnostics) and report success per condition; delete the parenthetical uncertainty in `§D` step 3. Improved state: a directly interpretable causal result.

**P-5 — The only named evaluation target has no selection criteria and unverified Checker Framework compatibility. (Moderate)**
- **Evidence:** `§D` step 1: "Find open source projects (e.g. Kafka) on GitHub to test our approach." No inclusion criteria, size bounds, or confirmation that candidates compile under the Checker Framework.
- **Impact:** Kafka is a large multi-module JVM build (Scala core plus Java clients), so only part of it is even eligible for Checker Framework checking; whether such a codebase compiles under the Checker Framework within the project's four-week window is unaddressed, and no fallback is offered if a real repository turns out to be incompatible. (The README's actual targets are `eval-repos/synthetic-id-swap/` and `eval-repos/cmsx/` (`README.md:71-73`), and "Kafka" appears only in the proposal — grep-verified.)
- **Recommendation:** State selection criteria (Java-dominant, module-scoped, plain-`javac` build under ~10 min, Checker-Framework-compilable), name 2–3 verified candidates, and commit to a fallback of purpose-built mini-repos if no real repo qualifies.

**P-6 — LLM-generated tests are used as a quality oracle, which is circular. (Moderate)**
- **Evidence:** `§C` step 2: "An agentic verification loop ensures the code compiles and passes LLM-generated tests (provides a baseline output quality)."
- **Impact:** If the same model family writes the types and their tests, agreement measures self-consistency, not correctness. (The design later drops this and relies on the ground-truth regression test, but never says so — see X-2.)
- **Recommendation:** Qualify in `§C`: "LLM-generated tests are compile-level smoke checks only; correctness is judged exclusively by the historical regression test (`§D` step 4)."

**P-7 — Timeline compresses implementation, refinement, and data collection into four weeks with no slack. (Moderate)**
- **Evidence:** `§E`: "Oct 1–Oct 26: implement and refine approach, collect data" — one bullet for all four pipeline stages on real repositories, for a two-person team ("Both members of the group", `§E`).
- **Impact:** LLM pipelines fail on integration, not conception; a slipped integration consumes the entire data window.
- **Recommendation:** Split into weekly milestones (triage + synthetic repo end-to-end → synthesis + placement on repo A → repair stage → data collection) and declare a minimum viable scope (synthetic repo + ~3 real bugs) that still yields a paper.

**P-8 — The most serious identified risk has no proposed mitigation. (Major)**
- **Evidence:** `§C` closing paragraph names the risk explicitly: "the Checker Framework supports custom checkers, which effectively allows the LLM to make its own rules… Thus, it is important that we are able to constrain what type designs generated by the LLM are acceptable." The paragraph then names a second risk ("even if the LLM generates sound types, they may not be useful for the end user") but offers only the resolve to "minimize" it while refining; no guard, criterion, or review procedure is specified.
- **Impact:** An LLM can trivially satisfy `§D`'s "fail on old commit" check with a tautological or bug-specific condition, passing evaluation while being worthless as a type design.
- **Recommendation:** Name concrete guards in that paragraph (forbid `@SuppressWarnings`/assume-suppression; require checks expressible over types, not value-level string matching; whitelist built-ins; human-review each hierarchy against a written "trivial check" definition). The design's §4 matrix is effectively this fix — cross-reference it.

**P-9 — No threats-to-validity or selection-bias coverage. (Moderate)**
- **Evidence:** `§D` step 2: "Look through commit history for bugs; manually triage them to find places where better static types can help fix them." No triage protocol, no N, no inter-rater agreement, no acknowledgment that pre-selecting type-preventable bugs limits generalizability.
- **Impact:** Same team builds the system and curates the benchmark subjectively; invites "cherry-picked" critique.
- **Recommendation:** Add written triage criteria, have both members independently triage a sample and report agreement, and report excluded as well as included decisions.

**P-10 — Deliverables and contributions are vague and unquantified. (Moderate)**
- **Evidence:** `§F`: "The main deliverable will be a system that effectively generates better types for Java projects using CheckerFramework." "Effectively" and "better" are undefined; secondary contributions (the benchmark, the blind-verification design, the failure taxonomy) are unstated.
- **Recommendation:** Rewrite `§F` as a numbered contribution list: (1) pipeline; (2) labeled benchmark of N preventable bugs; (3) empirical results on synthesis success and blind-vs-informed repair; (4) failure-mode taxonomy; plus an artifact-release commitment.

**P-11 — Motivational leap from Rust memory safety to Java domain invariants is asserted, not argued. (Moderate)**
- **Evidence:** `§A` line 3: "Similarly, Java supports advanced static analysis via the Checker Framework \[2\]."
- **Impact:** "Similarly" conflates a whole-language redesign that removes an entire bug class with an opt-in per-annotation layer whose adoption economics are the very problem the proposal cites.
- **Recommendation:** Add one bridging sentence: "While Rust's gains required language-level redesign, pluggable type systems offer a retrofit path for existing Java codebases — but their annotation burden has limited adoption; this project tests whether LLMs remove that burden."

**P-12 — Triage heuristic is unvalidated and its errors propagate. (Minor)**
- **Evidence:** `§C` step 1: "Simple heuristic: Classify bugs involving type conflation… as statically preventable. We may explore more nuanced heuristics…"
- **Recommendation:** State that heuristic precision will be spot-checked against the manual labels `§D` already produces.

**P-13 — No sandboxing/security note for compiling and running LLM-generated code. (Moderate)**
- **Evidence:** `§C` steps 2–3 compile and test agent-generated code with no mention of isolation (the README later adds an "airgapped sandbox" toggle, `README.md:21`).
- **Recommendation:** One line in `§C` stating builds/tests run in an isolated container with no network egress beyond the LLM API.

**P-14 — Cosmetic and terminology artifacts. (Minor)**
- **Evidence:** `§A` line 3 "\[1\]"; `§C` step 3 (line 24) uses "Opencode"; `§F` "CheckerFramework" vs "Checker Framework" elsewhere.
- **Recommendation:** Re-render references as plain Markdown; standardize "OpenCode" and "Checker Framework".

**P-15 — No title/authorship header. (Minor)**
- **Evidence:** The file begins at line 1 with "## A. Problem of Interest".
- **Recommendation:** Add a title, team names, course number, and date.

**P-16 — `§B` heading does not match its content. (Minor)**
- **Evidence:** `§B` is titled "Anticipated Challenges" but contains goals ("Our primary goal is to generate…"); the actual challenges sit at the end of `§C`.
- **Recommendation:** Retitle `§B` "Goals" and move the `§C` closing risk paragraph into a dedicated "Risks" subsection.

**P-17 — The load-bearing adoption premise is uncited. (Major)**
- **Evidence:** `§A` line 3 (and `README.md:9`): "developers often avoid advanced type features due to heavy annotation overhead and cognitive burden". The entire justification for goal 2 (developer burden) rests on this empirical claim, yet `# References` contains only the Rust blog post and the Checker Framework homepage.
- **Impact:** The project's motivation and its novelty relative to adoption/effort research are asserted rather than grounded; a reviewer can reject the premise without touching the pipeline.
- **Recommendation:** Cite 2–3 adoption/effort studies (Checker Framework case studies, annotation-burden or static-analysis-adoption literature) in `§A`, and treat the premise as a hypothesis measured by the new burden proxies (P-3). Improved state: the motivation is defensible and the burden goal is testable.

**P-18 — The `@NotNull` claim is technically overstated. (Moderate)**
- **Evidence:** `§B` line 12: "Widely used type annotations like `@NotNull` effectively eliminate common bugs such as `NullPointerException`s".
- **Impact:** Checker Framework annotations are opt-in and erased at runtime; they cannot eliminate NPEs arising in unannotated libraries, platform code, reflection, or unchecked paths. The sentence overstates what the motivating technology can do.
- **Recommendation:** Reword to "detect many nullness errors at compile time in annotated code" and add one clause noting the opt-in/unannotated-code caveat. Improved state: the motivation is accurate and still compelling.

**P-19 — The proposal's evaluation has no control condition and leaves its checks unoperationalized. (Major)**
- **Evidence:** `§D` (lines 34–38) compares nothing: it runs the system once, then checks that annotations "always fail on the old commit and pass on the new (fixed) commit, and that the fix proposed by the LLM after seeing the new types is correct." "Always fail" has no scope; "correct" has no rubric or judge; the "statically infeasible to prevent" branch from `§C` step 1 (line 18) has no defined action.
- **Impact:** Even if every step works, the proposal cannot attribute a repair to the types, and its correctness judgment is subjective. This is the proposal-level form of D-1 and is why F1's evaluation critique would otherwise be incomplete.
- **Recommendation:** In `§D`, add a no-types/informed-fixer control arm, define correctness as "passes the historical regression test and is judged semantically equivalent to the human fix by a blinded rubric", and state that infeasible bugs are recorded and excluded with a reason. Improved state: the proposal's evaluation is falsifiable before the design is written.

**P-20 — LLM triage was escalated from a stretch goal to the critical path without an explicit note. (Moderate)**
- **Evidence:** `§D` step 2 sub-bullet (line 36): "A potential stretch goal will be to have the LLM do the triage." The refined design makes the LLM the Step 2 classifier (`design §3 Step 2:83-86`) and the plan allocates `S2Triage.kt` (`implementation_plan §4:119`).
- **Impact:** Benchmark selection now depends on an unvalidated model judgment, adds a failure mode to sample construction, and removes the human adjudication the proposal implied. X-2 tracks dropped promises but not this escalation.
- **Recommendation:** State the escalation explicitly in `design §1`, retain human adjudication of a sample (with agreement reported), and add `triage.human_audit_rate` to `§5`/`§6`. Improved state: the scope change is visible and the benchmark's labels are auditable.

**P-21 — Operational prerequisites are missing: sanitizer semantics, mutation scope, budgets, and build reproducibility. (Moderate)**
- **Evidence:** `§C` step 3 names a "deterministic diff sanitizer" but never defines what counts as an executable change; `§C` step 4 says "while keeping field annotations fixed" (line 27) without explaining why fields are singled out; the proposal has no model/token/cost budget; it has no plan for reproducing historical builds (JDK/plugin/dependency drift); and the timeline gives no analysis/rework buffer (data ends Oct 26, writing starts Oct 27, `§E:45-46`).
- **Impact:** Each gap can consume the four-week implementation window or invalidate comparisons between commits.
- **Recommendation:** (a) define the sanitizer as an AST allowlist (annotations/imports only, with a worked example); (b) define the permitted mutation surface as methods only and say why fields are frozen; (c) add a per-run token/cost cap; (d) pin JDK and dependency versions per commit (links D-30/I-31); (e) add a one-week analysis buffer before writing. Improved state: the plan is executable within the stated window.

**P-22 — The proposal never states or tests its fit with its own context. (Moderate)**
- **Evidence:** The file begins at `## A. Problem of Interest` with no title, course, team, or scope statement (P-15); `§E` commits "Both members" to all tasks in a four-week implementation window; `§F` promises a paper "across a variety of open-source codebases" while `§D` names one example target. No section assesses the project against CS 6158's scope, the two-person team, or the paper/presentation obligations.
- **Impact:** F1's "fit with its stated context" is unaddressed, and the scope is not demonstrably achievable by the team that proposed it.
- **Recommendation:** Add a short "Context and scope" subsection stating the course, team size, available compute, and the minimum viable result (synthetic repo + ~3 real bugs); map `§F` deliverables to the paper and presentation. Improved state: scope and context are explicit and defensible.

**P-23 — Two named risks are never carried into a mitigation. (Minor)**
- **Evidence:** `§C` line 30 names "unsound type features that the LLM may also use" and "even if the LLM generates sound types, they may not be useful for the end user". P-8 covers the custom-checker half of the first risk; the design's §4 matrix covers some unsound features, but no finding or design section owns "types may not be useful".
- **Impact:** A reader cannot tell which artifact is responsible for these risks or how success would be judged.
- **Recommendation:** Add a three-row risk register (custom-checker abuse, unsound features, useless types) with owner, mitigation, and evidence, cross-referenced to `design §4` and `§6`. Improved state: every named risk has a home.

---

## 5. Refined Design Critique (F2)

Target: `docs/design_plan.md`.

### Strengths (verified)

- **D-S1 — Clear falsifiable chain.** `§1 'High-Level Workflow'` steps 1–5 and the `§2` paradigm table (Step 1 Deterministic → Step 6 Deterministic) define a pipeline whose endpoint is a runtime verdict, not a static one.
- **D-S2 — Unusually thorough threat model.** `§4 'The 19-Vector Defense Matrix'` enumerates 19 attack vectors with mechanism, detection layer, and strict/lenient actions.
- **D-S3 — Experimentation matrix and metrics registry are real.** `§5` defines 12 parameters with defaults; `§6` registers 27 top-level metrics.
- **D-S4 — Aggressive, well-chosen descoping.** `§7` defers Level 3 records, Maven, multi-JDK, and `.astub` overlays.
- **D-S5 — Blind-isolation specification is explicit.** `§3 Step 5 'Information Isolation'` lists exactly what the fixer does not receive.

### Weaknesses

**D-1 — No control/baseline condition; the causal claim is unproven. (Major)**
- **Evidence:** `§3 Step 6 'Evaluation Verdicts'`: "1. **Full Success**: Regression test passes AND all original unit tests pass AND Checker Framework reports 0 errors." `§6 'Overall Evaluation Metrics'` registers only outcome-level fields — `eval.regression_test_passed`, `eval.verdict`, and `eval.token_usage` (design:257-259) — with no condition/baseline field. `§5`'s matrix contains no baseline parameter, and `README.md:21` promises "A/B testing and ablation studies" for exactly this kind of toggle.
- **Impact:** A Full Success does not distinguish "the synthesized types guided the fix" from "the LLM fixed the bug by reading the code". This is the single most important missing control for the README's core hypothesis, and it leaves README principle 2's ablation promise unmet at the level of the core claim.
- **Recommendation:** Add `experiment.baseline` to `§5` with values `{fixer_no_types, fixer_builtin_only, fixer_typed}` (default `fixer_typed`), and make `lift = success_typed − success_untyped` the primary reported metric in `§6`. Improved state: the README hypothesis becomes directly testable.

**D-2 — "Blind" fixer is leaky: Step 3's inputs and outputs, helpers, and the worktree encode the bug. (Major)**
- **Evidence:** `§1 'High-Level Workflow'` step 4: "A blind fixer agent—strictly isolated from commit $C$, PR text, and regression tests—is tasked with resolving all Checker Framework errors." `§3 Step 5 'Information Isolation'`: "Fixer DOES NOT receive: Commit message of $C$, PR description, git diff of $C$, or the regression test code." But `§2` gives Step 3 (`Type Lattice & Guard Synthesis`, design:23) the inputs "Buggy code at $C-1$, bug/PR description". Step 3 also emits a `placement_guide.md` (design:114); the guide is a Step 3→Step 4 handoff (design:24), **not** a Step 5 input (design:132-133 lists only the annotated codebase, diagnostics, and instructions), so it leaks only indirectly through annotation placement. Two stronger channels are unaddressed: `§3 Step 3` packages "pure validator classes" into `custom-quals.jar` (design:113) and `§3 Step 5` lets the fixer call them inside method bodies (design:136), so a helper such as `isValidCustomerId` both names the predicate and supplies the repair; and `§3 Step 1` runs the fixer in an ephemeral `git worktree` of the mirror clone (design:75-76), which retains `.git` history unless explicitly redacted, so a shell-capable agent can read commit `$C` with `git log`/`git show`.
- **Impact:** The annotations and diagnostics disclose the bug's location and semantics; helpers can disclose the fix itself; and the worktree may disclose the future commit. The claim "purely by resolving static type errors" is still defensible for the annotation channel, but the stronger "never seen the bug" framing is not. Errors need not cluster at the bug — `§7.3` (design:282) explicitly contemplates errors in "irrelevant classes" — so leakage is partial and should be measured, not assumed.
- **Recommendation:** Redefine blindness precisely in `§3 Step 5` (no textual bug report; location hints via types are permitted by design), scrub or rename semantic helpers, remove `.git`/future refs from the fixer worktree, and add a mandatory localization metric (reuse `§7.3`'s SNR) plus a shell-escape test to `§6`. Improved state: the claim matches what is measured and enforced.

**D-3 — Internal inconsistency: triage taxonomy is binary in one place and five-way in another. (Moderate)**
- **Evidence:** `§3 Step 2 'LLM Task'`: "1. Classify commit as `bugfix` vs `non-bugfix` (pure bugfixes only; excludes feature additions, refactors, and chore/CI changes)." vs `§6 'Granular Triage & Benchmark Metrics'`: "`triage.classification_counts`: Breakdown of classifications (`bugfix`, `feature`, `refactor`, `chore_ci`, `mixed`)." The `§3` parenthetical itself enumerates the non-bugfix categories, and `§7.4` (design:289) calls the current design "Binary".
- **Impact:** The metric cannot be populated from the specified Step 2 output without an unstated mapping. This is under-specification rather than logical impossibility: a five-way label plus a "pure bugfix" filter rule satisfies both texts.
- **Recommendation:** Make `§3 Step 2` a five-way classification followed by a "pure bugfix" filter rule (not a second taxonomy), and mirror the five labels in the plan's `triage_status`/`triage_metadata`. Improved state: one taxonomy, one metric, one DB enum.

**D-4 — Test-porting yield is admitted to be low, but the core loop discards rather than adapts. (Major)**
- **Evidence:** `§3 Step 2 'Deterministic Verification Invariant'`: "2. Apply the test patch onto clean commit $C-1$… **Compilation Failure**: Discard commit". `§7.5 'LLM-Assisted Regression Test Adaptation'` concedes: "regression tests from $C$ often call new helper methods or constructor overloads introduced in $C$, causing compilation failures on $C-1$."
- **Impact:** This admitted failure mode reduces `triage.yield_rate` (`§6`), possibly below usability, threatening any meaningful sample. The design gives no measured share, so its frequency is unknown rather than established as dominant.
- **Recommendation:** Promote `§7.5` to a guarded core option (`step2.test_adaptation: off|llm`, default `off`) and add a stopping rule / target yield in `§6`. Improved state: benchmark yield is a managed quantity, not an accident.

**D-5 — The Gradle init-script injection contract required by README principle 5 is absent from the design. (Moderate)**
- **Evidence:** `README.md:29`: "The pipeline uses Gradle init scripts (`--init-script`) to dynamically inject compiler plugins, JVM module flags (`--add-opens`), and pre-compiled qualifier JARs (`custom-quals.jar`)." The design contains no "init script" or `--init-script` (grep-verified) and `§4` only asserts "Read-only build files". Injection mechanisms are described only for *future* Maven support (`§7.9:322`, "compiler wrapper script … to intercept `javac` and inject Checker Framework processors") and `.astub` overlays (`§7.11:330-335`), neither of which is the Gradle path the pipeline uses.
- **Impact:** Design and README disagree on how non-intrusive injection works; the plan (§8) implements `checker.init.gradle`, so the design is the odd one out.
- **Recommendation:** Add an "injection contract" paragraph to `§3 Steps 3–6` describing the `--init-script` mechanism and the `CUSTOM_QUAL_JAR`/`CHECKERFRAMEWORK_HOME` inputs, matching the plan. Improved state: one normative description of injection.

**D-6 — Freedom-level policy contradicts the config enum. (Moderate)**
- **Evidence:** `§3 Step 3`: "**Selected Policy**: **Level 1 + Level 2 Hybrid**." vs `§5`: "`step3.freedom_level` | `"level0"`, `"level1"`, `"level2"` | `"level2"`".
- **Impact:** There is no `hybrid` value; whether `level2` includes Level-1 coercions is undefined.
- **Recommendation:** Change `§5` values to `level0|level1|level2|level1+2`, document that L2 is a superset of L1, and set default `level1+2`.

**D-7 — Lattice scope contradicts the canary threat. (Moderate)**
- **Evidence:** `§3 Step 3 'Lattice Types Allowed'`: "Declarative subtyping hierarchies (`@SubtypeOf`) via built-in `SubtypingChecker`. Built-in Checkers (Nullness, Index, Regex, FormatString)." vs `§4` row: "**Trivial Custom Checkers** | Step 3 writing a dummy `isSubtype` returning `true`."
- **Impact:** If only declarative `@SubtypeOf` and built-ins are allowed, no custom `isSubtype` code exists and the canary row is dead; if custom checkers *are* allowed, `§3` is incomplete.
- **Recommendation:** State explicitly whether custom `BaseTypeChecker` subclasses are permitted; if not, replace the canary with a "vacuous hierarchy" check (single qualifier, or top = bottom).

**D-8 — Metrics reference data the pipeline never produces. (Moderate)**
- **Evidence:** `§6`: "`triage.continuous_scores`: Array of continuous scores $[0.0, 1.0]$" vs `§7.4`: "Prompt the model for a continuous score…" and `§5`, which has **no** `triage.bugfix_threshold` (grep-verified).
- **Impact:** A registered metric cannot be populated by the specified Step 2; a future-development parameter is missing from the matrix.
- **Recommendation:** Either remove `continuous_scores` from `§6` (and mark it future-only) or add `triage.bugfix_threshold` to `§5`. Ensure the metrics registry only contains metrics the specified steps emit.

**D-9 — The Step 4 AST-gate equality is underspecified. (Moderate)**
- **Evidence:** `§3 Step 4`: "$$\text{strip\_annotations}(AST_{\text{original}}) == \text{strip\_annotations}(AST_{\text{modified}})$$", followed by "If the agent modified even a single executable statement, branch, or literal, the edit is rejected."
- **Impact:** Adding `import qual.X;` changes the compilation unit; naive equality after stripping annotations still differs, producing false rejections. "strip" is undefined (modifiers? javadoc? imports?).
- **Recommendation:** Define `strip_annotations` precisely, including an import allowlist rule (ignore `ImportDeclaration` deltas that only import qualifier packages). Improved state: the gate is implementable without false positives.

**D-10 — Frozen local variables vs. permitted validator calls is undefined. (Moderate)**
- **Evidence:** `§5`: "`step5.local_variable_annotations` | `"frozen"`, `"allowed"` | `"frozen"`" vs `§3 Step 5 'Permitted Actions'`: "The fixer may call pure validators/coercions from `custom-quals.jar` inside method bodies."
- **Impact:** `CustomerId.of(raw)` usually requires an annotated local or a type change; "frozen" may render the fix impossible, or conversely permit evasion.
- **Recommendation:** Define `frozen` operationally (e.g., "may not add/remove annotations on existing locals, but may introduce new locals whose types are inferred") and give one worked example.

**D-11 — Turn budgets are implausible for real Gradle+Checker loops. (Moderate)**
- **Evidence:** `§5`: "`agent.steps.synthesis` | `int` | `8`" and "`agent.steps.fixer` | `int` | `15`".
- **Impact:** Synthesizing qualifiers, compiling a jar, and iterating `javac` within 8 turns — and repairing across files with Checker runs (typically minutes each on multi-module builds) within 15 turns — is optimistic for real repos.
- **Recommendation:** Justify budgets from a pilot on `synthetic-id-swap`/`cmsx` or raise defaults (e.g., 20/30) and add `agent.timeout_ms` and `max_checker_invocations`.

**D-12 — Diagram omits failure edges and retry semantics; verdict mapping is incomplete. (Moderate)**
- **Evidence:** `§2` mermaid: `AST_Gate -->|Executable changes| Reject_S4["Reject / Prompt Error Feedback"]` has no back-edge; `Reg_Test{"Existing C-1 Unit Tests Pass?"}` has no fail edge; `Eval_Result` has only two terminals, while `§3 Step 6` defines four verdicts (`full_success`, `type_success_runtime_fail`, `regression_introduced`, `fixer_incomplete`).
- **Impact:** The implementer cannot derive when to emit the middle two verdicts or how retries terminate.
- **Recommendation:** Add retry back-edges with counters and map diagram terminals to all four `eval.verdict` values with an explicit check order (checker → existing tests → regression test).

**D-13 — Default model choice is unjustified for code reasoning. (Moderate)**
- **Evidence:** `§5`: "`model.default` | `string` | `"nvidia/nemotron-3.5-super-vl-free"`". The ID contains the token `-vl-`, and `README.md:85` names the model "Nemotron 3.5 Super VL Free", which *suggests* a vision-language variant; the provider's naming convention was not independently verified. Either way, it is the default for qualifier synthesis and blind repair with no justification given, and the capacity question is inferred from the model family name rather than from a benchmark on this task.
- **Impact:** Weak baseline results may be misattributed to the design rather than to model capacity; the ID should also be checked against the provider's current model registry so the config is runnable.
- **Recommendation:** Justify the choice with a small task-specific pilot or default to a code-strong model named in the repository (e.g., the Claude 3.5 Sonnet alternative in `README.md:85`), keeping Nemotron as one ablation point. Improved state: the default is justified and resolvable.

**D-14 — `§5` lacks a model and turn budget for Step 4 (the applier agent). (Moderate)**
- **Evidence:** `§5` defines `model.triage`, `model.synthesis`, `model.fixer` but no `model.application`, and `agent.steps.synthesis`/`agent.steps.fixer` but no `agent.steps.application`, even though `§2`/`§3 Step 4` specify a multi-turn OpenCode applier agent.
- **Impact:** Step 4 inherits `model.default` (design:202, "across all stages"), so it is indirectly configurable, but it has no per-stage override and no turn budget; the whole stage is unidentifiable in the experiment matrix. (The plan's `models.application` and `limits.max_turns_application` are therefore *justified additions that reveal a design omission*, not design violations.)
- **Recommendation:** Add `model.application` and `agent.steps.application` to `§5` (the plan's values are 10 turns at `implementation_plan §9:412`). Improved state: every stage is independently configurable and ablatable.

**D-15 — "Read-only" Gradle cache is operationally infeasible. (Minor)**
- **Evidence:** `§3 Step 1 'Cache Sharing'`: "A centralized Gradle user home (`~/.gradle/caches` and `~/.gradle/wrapper`) is mounted read-only or with shared lock semantics across runs."
- **Impact:** Gradle writes locks and metadata; a read-only mount fails, and "shared lock semantics" is undefined.
- **Recommendation:** Specify a read-write cache with `--parallel` guidance or per-worktree `GRADLE_USER_HOME` with a shared read-only dependency mirror.

**D-16 — Tree-sitter cannot enforce purity or a call whitelist alone. (Minor)**
- **Evidence:** `§3 Step 3 'Purity Verification'`: "3. Tree-sitter AST scanning (zero heap field writes, call target whitelist)."
- **Impact:** Tree-sitter has no symbol resolution; call targets and aliasing cannot be reliably resolved syntactically.
- **Recommendation:** Scope tree-sitter to a syntactic pre-filter and make `PurityChecker`/JavaParser with symbol resolution the authoritative gate.

**D-17 — AST-engine ambiguity: design says tree-sitter, README and plan say JavaParser. (Moderate)**
- **Evidence:** `§2` table and `§2` mermaid say "`tree-sitter`" / "tree-sitter AST Gate" (`docs/design_plan.md:24,48`); `README.md:83` says "**AST Parsing & Validation**: JavaParser (`com.github.javaparser:javaparser-core:3.26.1`)"; the design itself hedges at `:335` ("JavaParser / Tree-sitter utility").
- **Impact:** No normative parser; the plan implements JavaParser.
- **Recommendation:** Declare one normative AST library in the design and list the other as an alternative.

**D-18 — Step 6 does not state that synthesized helpers must be on the runtime classpath. (Minor)**
- **Evidence:** `§3 Step 6 'Mechanics'`: "(Checker annotations on the classpath are treated as inert bytecode metadata by standard `javac`)." The statement is correct for annotations. But `§3 Step 5` permits helper calls (design:136), and Step 6 never says `custom-quals.jar` is on the test runtime classpath or that helper behaviour is part of the verdict.
- **Impact:** Helper invocations are live code: a missing jar yields `NoClassDefFoundError`, and a nondeterministic helper can change the runtime result. The design neither provisions the jar nor records helper cost.
- **Recommendation:** State in `§3 Step 6` that `custom-quals.jar` is added to the test runtime classpath, that helper behaviour is covered by the regression verdict, and that helper latency is recorded. Improved state: the runtime environment matches the code the fixer was allowed to write.

**D-19 — `§7.8` motivation contradicts Step 6's "standard compilation". (Minor)**
- **Evidence:** `§7.8`: "If regression tests interact with annotated methods, standard compilation might flag type mismatches in the test itself." vs `§3 Step 6`: "Execute the test under standard `gradle test`."
- **Impact:** Plain `javac` without the Checker processor will not flag qualifier mismatches, so the stated motivation is incorrect unless Checker remains enabled on tests.
- **Recommendation:** Clarify whether Step 6 enables Checker on test sources; if not, reframe `§7.8` accordingly.

**D-20 — The Level 0 parenthetical is ambiguous about defaulting. (Minor)**
- **Evidence:** `§3 Step 3`: "*Level 0 (Pure Annotations)*: Type qualifiers only. (Cannot ingest unannotated external data)."
- **Impact:** Read literally, "cannot ingest" suggests rejection, whereas Checker defaulting assigns a default qualifier to unannotated values. The intended meaning — that without Level-1 coercions an external raw value cannot acquire a custom qualifier — is defensible but not stated.
- **Recommendation:** Reword to "unannotated external values receive the default qualifier; converting them to a custom qualifier requires a Level-1 coercion". Improved state: Level 0's limitation is precise.

**D-21 — Dead-code guard is underspecified. (Minor)**
- **Evidence:** `§4` row: "**Dead-Code Pruning** | Inserting `if (true) return;` before buggy code. | Dynamic Tests + AST | AST detects dead-code exits".
- **Impact:** Without a precise rule, legitimate early returns produce false positives.
- **Recommendation:** Restrict the rule to "an unconditional exit inserted in the Step 5 diff that dominates the exact hunks of that diff, with existing unit tests still passing", and test it against a legitimate early return. Improved state: the guard catches inserted exits without rejecting ordinary control flow.

**D-22 — No Checker baseline on `C-1`; built-in checkers may surface pre-existing errors, making "0 errors" unattainable. (Major)**
- **Evidence:** `§3 Step 4 'Non-Triviality Check'` requires "$\ge 1$ Checker Framework error", and `§3 Step 5` requires the fixer to reach "0 Checker errors" (also `§3 Step 6` verdict 1). `§5`'s `step3.allowed_checkers` defaults to `"all"`, and `§3 Step 3` permits built-in checkers (Nullness, Index, Regex, FormatString). `implementation_plan.md §8` enables the processor on every `JavaCompile` (`:326-327`) with no scoping to the buggy package.
- **Impact:** On real legacy repositories, built-in checkers (nullness in particular) routinely emit many pre-existing errors in unrelated code. The blind fixer would then be asked to repair unrelated files, `fixer.remaining_type_errors == 0` would be effectively unreachable, and `full_success` would be depressed for reasons unrelated to the hypothesis.
- **Recommendation:** Snapshot the checker's diagnostics on clean `C-1` before Step 3, and define success as "**0 new errors relative to the baseline**" while recording the pre-existing count as a covariate. Add a `checker.scope`/`-AskipDefs`-equivalent scoping option, and wire `step3.allowed_checkers` (I-5).

**D-23 — No execution isolation contract for cloning/building untrusted repositories and running LLM-generated helpers. (Major)**
- **Evidence:** `§1`–`§3` describe cloning arbitrary Git repositories and building them at `C-1`, and `§3 Step 3` synthesizes helper code that `implementation_plan.md §8` compiles into those builds. A container is mentioned only as a future *host* for multi-JDK management (`design §7.10:324-327`, "Pre-install JDKs … in the execution container") and in passing at `implementation_plan §2:29` ("Linux x86 containers"); neither document specifies an isolation contract, resource caps, a filesystem boundary, or an egress policy. `README.md:21` lists a "network access vs. airgapped sandbox" toggle, and `design_plan.md §5` defines `step5.internet_access`, but the toggle's semantics are undefined and the plan never wires the airgap (I-5).
- **Impact:** Building and executing untrusted third-party code (and LLM-authored code) runs arbitrary instructions on the host; a single malicious or malfunctioning build step can exfiltrate data or damage the environment.
- **Recommendation:** Specify a sandbox contract in `design_plan.md §3 Step 1` and `implementation_plan.md §4`/`§6` (one container per run, no network by default, CPU/memory/wall-clock limits, worktree-scoped filesystem, explicit egress allowlist for the LLM API), and wire `step5.internet_access` to that boundary. (Proposal-level counterpart: P-13.) Improved state: executing an untrusted build cannot affect the host or exfiltrate data.

**D-24 — Synthesis is conditioned on the bug context, so the lattice can encode the known fix. (Major)**
- **Evidence:** `§1 'High-Level Workflow'` step 2: "Given commit $C-1$ and the bug context, an LLM agent synthesizes a custom Checker Framework qualifier hierarchy"; `§2` Step 3 inputs: "Buggy code at $C-1$, bug/PR description" (design:23). The only anti-vacuity gate is "≥ 1 Checker Framework error" (design:127), which a bug-specific lattice easily satisfies. D-1's proposed arms vary the *fixer* only; `§5` has no synthesis-context parameter.
- **Impact:** A lattice that rejects exactly the one buggy literal or call path proves only "an LLM can encode this known bug as a type", not the README's claim that synthesized types guide a repair. This is distinct from D-2 (leakage to the fixer) and P-8 (vacuity): it is bug-specific overfitting.
- **Recommendation:** Add `synthesis.bug_context: full|metadata_only|none` to `§5`, with `metadata_only` (commit message removed) and `none` (only `C-1` plus the failing regression test name) as ablation arms; report the overfitting delta. Add a held-out bug or a second, semantically similar bug as an acceptance test. Improved state: the headline claim is about type-guided repair, not bug memorization.

**D-25 — Synthesized helpers are an unmeasured fix-leak channel and can be vacuous. (Major)**
- **Evidence:** `§3 Step 3` permits "pure validation/coercion helper methods" (design:106) and packages "pure validator classes" in `custom-quals.jar` (design:113); `§3 Step 5` lets the fixer call them (design:136). Purity is enforced by templates, `PurityChecker`, and tree-sitter (design:107-111), and the canary tests only a dummy `isSubtype` (design:190). Nothing requires a helper to test the predicate it names.
- **Impact:** A helper such as `isValidCustomerId` embodies the fix and tells the "blind" fixer what to do; a no-op `CustomerId.of(String s) { return s; }` or a `.*` regex validator is pure, type-correct, and canary-clean yet enforces nothing. The pipeline can force a call to a semantic no-op and score a full success.
- **Recommendation:** Give every synthesized helper a required positive/negative fixture and a property or mutation test; reject helpers whose rejection set is empty; synthesize helpers under the same reduced bug context as D-24; record helper complexity and fixture pass rate. Improved state: a helper's semantics, not merely its purity, is verified.

**D-26 — The non-triviality check does not require the error to be in the buggy scope. (Moderate)**
- **Evidence:** `§3 Step 4 'Non-Triviality Check'`: "The codebase must trigger ≥ 1 Checker Framework error" (design:127), with no location constraint. `§7.3` (design:282) itself warns: "If synthesized types trigger errors in irrelevant classes, the fixer may waste time fixing non-buggy code", and localization/SNR is deferred to future work.
- **Impact:** `full_success` can be driven by errors unrelated to the bug, and the fixer's turn budget can be spent off-target. This is distinct from D-22, which concerns *pre-existing* checker noise: here the *new* errors are unlocalized.
- **Recommendation:** Require at least one new error in the buggy method or the diff hunk (Tier 1/2 of `§7.3`), record `application.errors_in_bug_scope` and SNR, and warn/fail when the count is zero. Improved state: non-triviality means the bug itself is untypeable.

**D-27 — Constant-return / deleted-branch repairs are not a guarded attack vector. (Major)**
- **Evidence:** `§3 Step 5 'Permitted Actions'` lets the fixer "edit statements inside existing method bodies" (design:135). The 19-vector matrix covers dead-code *insertion* ("Inserting `if (true) return;` before buggy code", design:191) but not replacing the buggy computation with a constant/stub return or deleting the branch. The AST gate applies to Step 4 only; Step 5 has signature and suppression guards, not a body-semantics guard.
- **Impact:** If the regression test asserts an output, a hardcoded return passes it while removing the computation. That is a direct reward hack and a larger hole than several enumerated vectors.
- **Recommendation:** Add a 20th vector, "semantic body replacement", with a diff-aware check that the buggy region's computation is preserved (or a mutation test that fails when the body is stubbed), and include it in I-4's matrix. Improved state: passing the regression test requires a computation, not a constant.

**D-28 — Data contamination and model provenance are unaddressed. (Moderate)**
- **Evidence:** The pipeline mines historical public commits and their fixes (e.g., Kafka, proposal `§D:34`), which are plausibly in LLM pretraining data. `§5` stores `model.*` as a string (design:202-205) with no revision/date, and `§6` records no model fingerprint.
- **Impact:** The fixer may "repair" from memorization rather than from the synthesized types, inflating success; results are not reproducible across model updates. This is distinct from D-1 (baseline) and D-2 (artifact leakage).
- **Recommendation:** Record model ID, revision/date, and provider in `config_snapshot`; prefer post-cutoff commits or held-out repositories; run a memorization probe (base model, no types, same bug) and report the gap. Improved state: success is attributable to the pipeline, not to pretraining.

**D-29 — One stochastic run per bug; no temperature, seed, or variance reporting. (Major)**
- **Evidence:** `§5` (design:199-212) defines 12 parameters but no temperature, seed, or repetition count; `§6` has no variance/pass@k metric; the design implies a single run per bug.
- **Impact:** LLM triage, synthesis, and repair are stochastic; a one-sample success rate cannot support a causal claim and may reflect luck. This is at least as serious as D-1 for "meaningful research results".
- **Recommendation:** Add `experiment.repetitions` (≥ 3), `model.temperature`, and `model.seed` to `§5`; report mean, confidence interval, and pass@k per condition in `§6`; stratify by repository and bug. Improved state: the headline result has an error bar.

**D-30 — Nothing establishes that the pre-existing test suite is green on `C-1`. (Moderate)**
- **Evidence:** `§3 Step 4` requires the codebase to "pass existing $C-1$ unit tests" (design:126), and `§3 Step 6` verdict 1 requires "all original unit tests pass" (design:148), but Step 2 verifies only that the regression test fails on `C-1` (design:88-94). No pre-existing-failure baseline or discard reason exists.
- **Impact:** On legacy repositories with pre-existing failures, the gates are unattainable for reasons unrelated to the hypothesis — the test-suite analogue of D-22.
- **Recommendation:** Run the full suite on clean `C-1` in Step 2, record `discard.preexisting_test_failures`, and define success as "no new failures relative to the baseline". Improved state: test verdicts measure the fix, not repository decay.

**D-31 — PR-metadata acquisition is unspecified. (Moderate)**
- **Evidence:** `§2` Step 2 inputs include "commit msg, PR body" (design:22); `§3 Step 2` requires "PR title, and PR description" (design:84); `§6` records "PR numbers, titles, and URLs" (design:234). Step 1 is a local mirror/worktree flow (design:75-76); no GitHub API, authentication, squash-merge handling, or commits-outside-PRs fallback is specified.
- **Impact:** Triage cannot obtain its declared inputs on a local clone, and PR extraction silently degrades or fails.
- **Recommendation:** Specify a metadata client (GitHub API + token, rate-limit handling), squash-merge/PR-number resolution, and a commit-message-only fallback recorded as `triage.metadata_source`. Improved state: every declared Step 2 input has a defined source.

**D-32 — Prompt injection from untrusted repository content is outside the threat model. (Moderate)**
- **Evidence:** All 19 vectors (design:173-193) are type-system or build escapes. None covers instructions embedded in repository comments, Javadoc, test names, or PR bodies that the Step 2/3/4/5 agents ingest; `§3 Step 3` and `§3 Step 5` read arbitrary repository text.
- **Impact:** A malicious or merely adversarial repository can steer an agent to add suppressions, exfiltrate data, or edit outside the worktree. This compounds D-23's untrusted-build risk.
- **Recommendation:** Treat repository text as data, add an injection canary and instruction-stripping/spotlighting, log the exact prompt/context, and add "instruction injection" as a 21st vector with a test. Improved state: agent instructions cannot be overridden by target-repository content.

**D-33 — Mode A contradicts the design's unconditional guarantees. (Moderate)**
- **Evidence:** `§4` Mode A says "No AST guardrails or compiler restriction flags are enforced. The agent is only asked in the prompt not to cheat" (design:160-161), while `§3 Step 5` states unconditionally that "`src/test/**` is made read-only" and "Method signatures remain locked" (design:139-140). The instruction actually quoted at design:132 contains no anti-cheat clause.
- **Impact:** An implementer cannot tell which guarantees survive `disabled`; the "measuring natural cheating" arm may also be measuring missing test protection.
- **Recommendation:** Define Mode A as "no *heuristic* guards; test read-only and signature lock always on" (or explicitly turn them off and record it), and quote the exact prompt per mode. Improved state: each mode is a declared, testable guard set.

**D-34 — `step5.internet_access` and two README guarantees are semantically undefined. (Moderate)**
- **Evidence:** `§5` defaults `step5.internet_access` to `false` (design:210) while Step 5 necessarily calls a model provider; the parameter does not say whether it blocks all egress, only agent web tools, or the LLM API. `README.md:24` lists "issue keys" among the fixer's isolation guarantees, but design:133 does not mention them. `README.md:25` bans "field modifications", but design:139-140 guards only tests and method signatures (the plan's field gap is I-12).
- **Impact:** The airgap toggle can either disable the pipeline or fail to isolate it; two README guarantees have no design mechanism, so the philosophy overstates enforcement.
- **Recommendation:** Define `internet_access` as an egress allowlist (LLM API only) and record it in the sandbox contract; add issue keys to design:133; add a field-declaration guard to `§3 Step 5`/`§4`. Improved state: the toggle and the README guarantees are executable.

**D-35 — The "Trivial Lattice" terminal has no `eval.verdict` value. (Minor)**
- **Evidence:** The `§2` mermaid has a terminal "Fail: Record Ineffective Types" for a trivial lattice (design:51), and `§3 Step 4` says "halt and record failure" (design:127), but the `eval.verdict` enum has only four values, none for triviality (design:258); only `application.is_trivial` exists (design:248).
- **Impact:** A halted trivial run cannot be represented in the verdict column, so downstream queries must infer it from a separate metric.
- **Recommendation:** Add `trivial_lattice` to the verdict enum (and the plan's SQL comment) or record `eval.verdict = NULL` with `application.is_trivial = true` as an explicit rule. Improved state: every terminal in the diagram maps to a stored verdict.

---

## 6. Implementation Plan Critique (F4/F5/F6 internal)

Target: `docs/implementation_plan.md`. (Design-fidelity divergences are in §8.2; this section is internal soundness.)

### Strengths (verified)

- **I-S1 — Sensible language split.** `§1` argues Kotlin for compile-time safety and in-process JavaParser, Python for offline analytics via SQLite.
- **I-S2 — Concrete DDL and directory layout.** `§3` gives real DDL; `§4` a full tree.
- **I-S3 — Per-stage model configurability.** `§9` exposes `models.triage/synthesis/application/fixer`.
- **I-S4 — Non-intrusive injection sketch.** `§8` provides a working-shaped `checker.init.gradle`.
- **I-S5 — Typed result micro-pattern.** `§7`'s `sealed class ValidationResult { Valid; Invalid(reason) }` is a clean extension point.

### Weaknesses

**I-1 — The Step 4 AST gate ("annotations only") has no implementation in the shown plan. (Major)**
- **Evidence:** `docs/design_plan.md §3 Step 4` defines the gate; the plan's only validator is `docs/implementation_plan.md §7 validateStep5(originalCode, modifiedCode, mode)`, and `§4` merely comments "`AstGuard.kt # JavaParser strip-and-compare & signature locks`". No function performs strip-and-compare, and no feedback channel to the agent is shown.
- **Impact:** The single guard that makes "annotations without executable changes" enforceable at Step 4 is absent; the comment describes behavior the code does not implement.
- **Recommendation:** Add `validateStep4` to `AstGuard.kt` that canonicalizes via `ModifierVisitor`, strips annotations (per D-9's precise definition), compares, and returns `Invalid` with a message routed back into the applier's next turn. Improved state: the design invariant is executable.

**I-2 — `placement_guide.md` is absent from the plan's tree and schema. (Major)**
- **Evidence:** `docs/design_plan.md §3 Step 3 'Output Artifacts'` names it; `README.md:48` restates it ("generates custom-quals.jar + placement_guide.md"); grep shows **zero** occurrences in `docs/implementation_plan.md`, whose `§4` tree and `§3` DB schema have no field/path for it.
- **Impact:** Step 3→Step 4's primary handoff does not exist in the engineering spec.
- **Recommendation:** Add `placement_guide.md` to `§4` (e.g., a per-run artifacts directory), record its path in `step_trajectories.diff_patch`/a new column, and reference it as the Step 4 input.

**I-3 — The canary test has no shown counterpart. (Major)**
- **Evidence:** `docs/design_plan.md §4` row: "**Trivial Custom Checkers** … | Canary test required | Canary test required." Grep shows no `canary` in the plan.
- **Impact:** Vector 16 is unenforced; a vacuous lattice can pass.
- **Recommendation:** Add a canary fixture (a class the synthesized checker *must* reject, plus one it must accept) and a `S3Synthesis` step check.

**I-4 — 11 of the 19 attack vectors are unimplemented and a further 5 are only partially enforced. (Major)**
- **Evidence (grep-verified):** Fully implemented (3): `@SuppressWarnings` (row 1, `:290-296`), `@AssumeAssertion` (row 2, `:298-304`), covariant array aliasing (row 6, strict flag `:359`). Partially enforced (5): raw types (row 4 — design:178 specifies "Compiler Flag + AST", but only `-AignoreRawTypeArguments=false` is added, `:358`, and it is ignored outside `strict`), double/unsafe casts (row 5 — flag only, `:361`, no AST nested-cast ban), unverified purity (row 7 — flag only, `:360`), contract injections (row 10 — signature lock is annotation-blind, see I-12), signature drift (row 19 — method parameter/return changes are caught by `declarationAsString`, but constructors and fields are not, see I-12). No counterpart at all (11): utility casts `castNonNull` (3), `@Covariant` (8), initialization-escape qualifiers (9), scoped defaulting `@DefaultQualifier(Bottom.class)` (11), explicit `@Bottom` (12), `.astub` injection (13), command-line bypasses (14), `-Awarns` masking (15), trivial custom checkers / canary (16), dead-code pruning (17), reflection/`Unsafe` (18).
- **Impact:** The "Full 19-Vector Hardened Defense" of `§4 Mode C` is not delivered; the gap is exactly the reward-hacking surface the README warns about. The implemented checks are also Step-5-scoped: the Step 3 jar and Step 4 placement have no shown guard, and the Step 5 guard has its own bypasses (I-26).
- **Recommendation:** Add the missing AST checks to `BackdoorDetector.kt` and the missing file/build guards to `checker.init.gradle` + a filesystem guard, with one unit test per vector (`@Covariant`, `@Initialized` on locals, `@RequiresQualifier` on headers, `@DefaultQualifier(Bottom.class)`, `@Bottom` locals, `.astub` additions, `-AskipDefs` in build args, `-Awarns`, `Method.invoke`/`Field.set`, plus the canary fixture). Add a build-argument allowlist that rejects `-AskipDefs`/`-Astubs`, and a worktree diff guard for new `.astub` files. Improved state: vector coverage equals the matrix.

**I-5 — Design configuration keys are missing, making several design toggles unrepresentable. (Major)**
- **Evidence (grep-verified):** `docs/design_plan.md §5` defines `prompt.tell_non_triviality_error`, `step5.local_variable_annotations`, `step5.internet_access`, `step3.allowed_checkers`, `step3.freedom_level`; none appear in `docs/implementation_plan.md §9`, which otherwise exposes `guardrails.mode`, `models.*`, `limits.*`, `build.*`, `environment.*`, and `paths.*`.
- **Impact:** Five of the design's stated ablation knobs (including the README principle-2 "prompt hinting vs. blind feedback" and "network access vs. airgapped sandbox" toggles) cannot be set.
- **Recommendation:** Add the five keys to `§9 project_config.yaml` and wire each (freedom level → synthesis prompt/checker choice; allowed checkers → `CF_PROCESSOR`/`-Aquals`; local-variable policy → Step 5 guard; internet access → sandbox egress; trivia hint → synthesis prompt).

**I-6 — No shown consumer carries the configuration to the build or the runner. (Major)**
- **Evidence:** `docs/implementation_plan.md §9` defines the full `ProjectConfig` YAML (`models.*`, `limits.*`, `build.*`, `environment.*`, `paths.*`), and `§4` names `ProjectConfig.kt`, but no shown code parses the YAML or passes it anywhere. `§8` reads `System.getenv('GUARDRAILS_MODE')`; `§6 runAgent` takes raw parameters and sets no environment; nothing shown sets `GUARDRAILS_MODE`/`CHECKERFRAMEWORK_HOME`/`CUSTOM_QUAL_JAR`/`CF_PROCESSOR`/`CF_AQUALS`, and no call site of `runAgent` is shown. Additionally, `§8` executes inside a Gradle daemon; environment changes may not be observed when a daemon is reused (documented Gradle/JDK failure modes), so a mode change can be silently ignored.
- **Impact:** The mode selector and most config sections have no shown effect; the shipped behavior is whatever fallbacks the script has. This is broader than the guardrail toggle: no configuration consumer is specified at all.
- **Recommendation:** Add a typed `ProjectConfig` loader and pass a config object (or rendered `-P` properties) into each runner; render the init script per invocation and use `--no-daemon` or explicit `ProcessBuilder.environment()`; add an integration test that asserts strict flags appear only under `strict` and that every YAML key has a consumer. Improved state: every exposed knob is provably wired.

**I-7 — Step 2 triage has no implementation and the wrong runner is provided. (Major)**
- **Evidence:** `docs/design_plan.md §2` assigns Step 2 the harness "Direct LLM API + Gradle Runner" and `§3 Step 2` calls it a "Simple LLM (single-shot call per commit)". The plan's `§4` lists `S2Triage.kt` and `templates/prompts/triage.md` but shows no code; `§6` provides only `OpenCodeRunner`, an *agentic* multi-turn runner.
- **Impact:** A whole early stage (plus its discard bookkeeping and FQ test-name extraction) is unspecified, and the only runner shown is poorly suited to single-shot classification.
- **Recommendation:** Add a small `LlmClient` (single-shot, structured output) plus `S2Triage` implementation, or state that triage runs via a single OpenCode turn and specify the prompt's output schema.

**I-8 — Non-triviality halt and `application.is_trivial` have no shown counterpart. (Major)**
- **Evidence:** `docs/design_plan.md §3 Step 4 'Non-Triviality Check'`: "The codebase must trigger ≥ 1 Checker Framework error. If 0 errors are emitted, the types are ineffective (halt and record failure)." `§6` registers `application.is_trivial`. The plan contains neither (grep: no `is_trivial`, no triviality halt).
- **Impact:** Trivial lattices silently proceed to Step 5, wasting runs and biasing results.
- **Recommendation:** After Step 4's Checker run, parse the error count; if 0, halt and write `application.is_trivial = true` with `eval.verdict`-adjacent status. This also makes the design's `§2` mermaid "Fail: Record Ineffective Types" terminal real.

**I-9 — `OpenCodeRunner.runAgent` does not compile (`prompt: str`). (Moderate)**
- **Evidence:** `§6 line 208`: "`prompt: str,`". Kotlin's type is `String`; `str` is undefined.
- **Impact:** The only agent-runner code in the plan is invalid Kotlin.
- **Recommendation:** Change to `prompt: String` and add the file to a compiled module so CI catches this class of error.

**I-10 — stderr is never drained, risking a subprocess deadlock. (Moderate)**
- **Evidence:** `§6 line 224`: "`.redirectErrorStream(false)`" with only `proc.inputStream` consumed (`:231`) and no separate stderr reader.
- **Impact:** If the child writes enough to stderr, its pipe buffer fills and it blocks while the parent waits on stdout — a classic deadlock.
- **Recommendation:** Either `.redirectErrorStream(true)` or start a dedicated stderr-draining thread/consumer.

**I-11 — `timeoutSeconds` is unused; only a step-count kill exists, and its result is discarded. (Moderate)**
- **Evidence:** `§6 line 213` declares `timeoutSeconds: Long = 600` but the body never uses it. A step-count kill does exist (`stepsCounted > maxSteps`, `:239-243`), but it fires only when a parseable `step_start` event arrives. After stream close, `proc.waitFor(10, TimeUnit.SECONDS)` (`:250`) discards its boolean result, so a timeout returns `exitCode = -1` with no timeout flag; `AgentRunResult` (`:200-204`) has no `timedOut` field. `§4 line 129` calls the file "OpenCode CLI runner with watchdog"; `§6`'s title says "& Watchdog".
- **Impact:** A live-but-silent process hangs `useLines` indefinitely; on a timeout the process is leaked and the caller cannot distinguish a timeout from a crash. The advertised *wall-clock* watchdog is absent.
- **Recommendation:** Enforce a wall-clock deadline (kill on expiry), check the `waitFor` result, add `timedOut` to `AgentRunResult`, and rename the file/comment to match reality. Improved state: every run terminates and its termination cause is recorded.

**I-12 — The signature lock is blind to annotations and to constructors/fields. (Moderate)**
- **Evidence:** `§7 lines 307-310` keys methods by `it.declarationAsString` and compares key sets. In JavaParser 3.26.x (checked against that version's `MethodDeclaration` source), `getDeclarationAsString()` renders access specifier, keyword modifiers (`static`, `final`, …), return type, name, parameters, and throws, but **not annotations** (annotations are a separate node list); so adding `@RequiresQualifier` to a header does not change the key — design vector 10 (contract injections) is undetected. `findAll(MethodDeclaration)` also excludes `ConstructorDeclaration` (constructors extend `CallableDeclaration`, not `MethodDeclaration`), so constructor signature drift is undetected, and no field check exists despite `README.md:25` forbidding "field modifications". Ordinary method parameter/return drift *is* caught, so vector 19 is partially enforced rather than wholly absent.
- **Impact:** Vector 10 and the README's field-modification ban are unenforced, and vector 19 is only partially enforced; the plan advertises a stronger lock than the code provides.
- **Recommendation:** Compare a normalized signature that includes annotations and modifiers, include `ConstructorDeclaration`, and add a field-declaration guard. Add a unit test per bypass (annotation added; constructor parameter changed; field type changed) so the claim is checkable. Improved state: the lock matches README:25.

**I-13 — Purity verification is unimplemented. (Moderate)**
- **Evidence:** `docs/design_plan.md §3 Step 3 'Purity Verification'` lists pre-certified templates, `PurityChecker`, and tree-sitter scanning. The plan names `PurityValidator.kt` (`§4:127`) but shows no code, and `-AcheckPurityAnnotations` is enabled only in `strict` (`§8:356-362`).
- **Impact:** Helping methods annotated `@Pure` can mutate state, breaking the Step 4/5 assumptions.
- **Recommendation:** Implement `PurityValidator` using JavaParser symbol resolution plus `-AcheckPurityAnnotations` in all modes where helpers are injected, and gate helper inclusion on a pass.

**I-14 — `src/test/**` read-only protection is absent. (Major)**
- **Evidence:** `docs/design_plan.md §3 Step 5`: "Test files are protected (`src/test/**` is made read-only)." Grep shows no read-only/readonly handling in the plan; `paths.test_dir` is only declared (`§9:418`).
- **Impact:** The fixer can edit tests to make them pass — a direct reward hack the README forbids.
- **Recommendation:** Make the test directory read-only (filesystem permissions or a path-scoped diff guard that rejects any `src/test/**` write) as a Step 5 precondition, and assert it in a test.

**I-15 — Build-injection invocation is unspecified, and the shared qual-jar path collides across runs. (Moderate)**
- **Evidence:** `§8` provides `checker.init.gradle` content but never shows how it is passed (`--init-script` is not in the plan; grep-verified). The custom jar path is a fixed `/tmp/custom-quals/custom-quals.jar` (`§8:341`, `§9:419-420`), and `CHECKERFRAMEWORK_HOME` falls back to `/opt/checker-framework` (`§8:338`).
- **Impact:** If `--init-script` is not passed, injection never happens; a fixed `/tmp` path collides under parallel or multi-commit runs and leaves stale artifacts.
- **Recommendation:** Document and implement `--init-script <templates/checker.init.gradle>` in `GradleRunner`, and derive the qual-jar path per run (per worktree/run-id). Centralize paths in `ProjectConfig`.

**I-16 — The CLI binary path is likely wrong. (Moderate)**
- **Evidence:** `§10` (`:434,437,440`) uses `./build/install/pipeline/bin/pipeline`, but `§5` (`:181-183`) sets only `mainClass` and defines no `applicationName`; Gradle's `installDist` names the distribution after the project (the repo directory is `llm-type-annotation-synthesis`).
- **Impact:** The documented commands fail.
- **Recommendation:** Either set `applicationName = "pipeline"` in `§5` or correct the paths in `§10`; add a smoke test invoking `installDist`.

**I-17 — Cache pre-warm and worktree lifecycle are unspecified. (Moderate)**
- **Evidence:** `docs/design_plan.md §3 Step 1` specifies ephemeral `git worktree`s, a local mirror clone, and a pre-warm pass `./gradlew build -x test`. The plan names `S1Ingestion.kt` (`§4:118`) and the `init` "pre-warm caches" command (`§10:433`) but shows no implementation and no teardown.
- **Impact:** The ingest stage is only a named file; its design is not shown, and failed runs can leak worktrees, locks, and disk.
- **Recommendation:** Specify a contract such as `createWorktree(repo, commit): Worktree` / `destroyWorktree(wt, keepOnFailure: Boolean)` in `S1Ingestion.kt`, the mirror-refresh policy, the exact pre-warm command, and `finally`-style cleanup with a `worktree.leaked` metric. Improved state: the stage is implementable and leak-free.

**I-18 — Discard-reason taxonomy diverges from the design. (Minor)**
- **Evidence:** `docs/design_plan.md §6` uses `discard.no_test_found`, `discard.test_compilation_failure`, `discard.test_passes_unpatched`, `discard.build_broken_unpatched`; `§3` (`:63`) uses `'discarded_non_compiling'`, `'discarded_passes_unpatched'`, `'non_bugfix'`.
- **Recommendation:** Align the enum one-to-one (and add the two missing design reasons to the DB comment).

**I-19 — `regression_test_name` is singular; the design expects class + method names. (Minor)**
- **Evidence:** `docs/design_plan.md §3 Step 2`: "Extract fully qualified test method and class names"; `§6` counts "individual test methods". The plan stores one `regression_test_name TEXT` (`§3:62`).
- **Recommendation:** Replace the single column with `regression_test_class` and `regression_test_method` (or a JSON array with a documented schema) and update the Step 2 writer. Improved state: class and method provenance is recorded without parsing a composite string.

**I-20 — `build.gradle.kts` pins no JDK toolchain. (Minor)**
- **Evidence:** `§5` has no `jvmToolchain`/`jvmTarget`, while `§9` targets Java 17 (`source_compatibility: "17"`).
- **Recommendation:** Add `kotlin { jvmToolchain(17) }` for reproducibility.

**I-21 — No dependency injection, no test scaffold. (Minor; the modularity failure is rated Major in M-2/M-4)**
- **Evidence:** `§5` declares only `testImplementation(kotlin("test"))`; `OpenCodeRunner` and `AstGuard` are concrete classes with no constructor parameters; no `src/test` appears in `§4`.
- **Impact:** The plan's only test configuration would discover no tests (no `junit-jupiter-engine`; see I-33), and no component can be exercised without spawning a process or parsing a real file.
- **Recommendation:** Follow M-2/M-4: add constructor-injected interfaces, a `src/test/kotlin/pipeline` tree, and fakes; add `testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")`. Improved state: each component is testable in isolation.

**I-22 — `-Awarns` masking and `.astub`/`-AskipDefs` build bypasses are unguarded. (Minor; a component of I-4)**
- **Evidence:** `docs/design_plan.md §4` rows 13–15; no counterpart in the plan (grep-verified). This is three of the 11 missing vectors counted in I-4.
- **Impact:** Individually these bypasses require the agent to edit the worktree or build args; they are rated Minor as isolated vectors, while the aggregate gap (11 unguarded vectors) is the Major finding in I-4.
- **Recommendation:** Parse Gradle/Checker output strictly regardless of `-Awarns`, and add a build-argument allowlist that rejects `-AskipDefs`/`-Astubs` introduced by the agent, plus a diff guard on the worktree for new `.astub` files. Improved state: I-4's matrix has no unguarded build escape.

**I-23 — `single_test_cmd` placeholders have no formatter. (Minor)**
- **Evidence:** `§9:394`: "`single_test_cmd: "./gradlew test --tests {test_class}.{test_method}"`" — no code shows how `{test_class}`/`{test_method}` are substituted.
- **Recommendation:** Specify the substitution mechanism (e.g., `cmd.replace("{test_class}", cls).replace("{test_method}", meth)`, with `$` in nested-class names escaped for Gradle) and add one test per placeholder. Improved state: the command is reproducible for nested and parameterized tests.

**I-24 — `--depth` duplicates the config value and `python` may be `python3`. (Minor)**
- **Evidence:** `§10:437` passes `--depth 30` while `§9:414` sets `commit_depth: 30`; `§10:444` calls `python`.
- **Impact:** Two sources of truth can diverge; `python` is absent on many Linux images.
- **Recommendation:** Remove the `--depth` flag and read `limits.commit_depth` from the config; invoke `python3`. Improved state: one source of truth and a portable command.

**I-25 — The default Gradle injection configuration cannot work and can break target builds. (Major)**
- **Evidence:** `§8:349` defaults the processor to `org.checkerframework.common.subtyping.SubtypingChecker`, but that checker requires `-Aquals` or `-AqualDirs` and aborts otherwise; `-Aquals` is added only when `CF_AQUALS` is set (`:365-368`), and nothing shown sets it. Separately, passing `-processor <name>` (`:348-354`) bypasses javac's annotation-processor discovery, so a target project's own processors (Lombok, Dagger, AutoValue) will not run.
- **Impact:** On the default path, every build either fails in the checker ("must provide -Aquals") or compiles with no type-checking; on projects that rely on annotation processors, the injected build can fail for reasons unrelated to the hypothesis.
- **Recommendation:** Default to a concrete built-in checker (e.g., `org.checkerframework.checker.nullness.NullnessChecker`) or require `-Aquals` and fail fast when it is absent; omit `-processor` unless the user explicitly overrides it, and otherwise keep javac discovery (or list all processors explicitly with `-processorpath`). Add an integration test on `synthetic-id-swap` that asserts a known error is reported. Improved state: the default invocation type-checks and preserves the target build.

**I-26 — `AstGuard` has false positives and bypasses that undercut its guarantees. (Major)**
- **Evidence (all in `§7`):** it scans only the modified compilation unit (`:290-296`), so a pre-existing `@SuppressWarnings` — common in legacy Java — rejects every modification; `endsWith("SuppressWarnings")` (`:292`) rejects user annotations such as `@MySuppressWarnings`; `@AssumeAssertion` is a literal message substring (`:300`) and is bypassed by concatenation; `associateBy { declarationAsString }` (`:307-308`) has no enclosing-class key, so identical signatures in different classes collapse; only `MethodDeclaration` is inspected, leaving new files, imports, fields, static initializers, enum constants, and class-level edits unchecked; annotation *removal* is unchecked (the key is annotation-blind), which is the direct way to silence errors; `mode` branches only on `"disabled"` (`:285`), so `lenient` and `strict` behave identically; and the API takes one `String` pair (`:284`), so no multi-file caller is shown.
- **Impact:** The validator can reject legitimate edits and accept the most direct cheats; it does not implement the mode matrix it advertises.
- **Recommendation:** Compare original and modified files per compilation unit, key methods by `(enclosing type, normalized signature)`, scan fields/constructors/initializers/imports, reject annotation removal as well as addition, add a multi-file `validateRun(runDir)` API, implement a mode-to-checks table, and add a test per bypass. Improved state: `AstGuard` is a sound (if conservative) gate rather than a filter with holes.

**I-27 — The plan cannot produce or acquire several artifacts the pipeline consumes. (Major)**
- **Evidence:** Design:23 specifies the Step 3 harness as "OpenCode + `javac`"; the plan has no `javac` runner (its only `javac` strings are `--add-opens` package names, `:330-335`). Design:113 makes `custom-quals.jar` a Step 3 output, but the plan only *reads* `/tmp/custom-quals/custom-quals.jar` (`§8:341`, `§9:420`) and shows no `javac`/`jar`/Gradle task that builds it. `validateStep5(originalCode, modifiedCode, …)` (`§7:284`) has no shown input-acquisition path (`git diff` appears nowhere), and `commits` stores only `commit_hash` (`§3:52-66`) — no `parent_commit` and no test-patch column — although Steps 2 and 6 apply patches to `C-1`.
- **Impact:** Three core stages cannot run from the specified plan: synthesis cannot compile its jar, the Step 5 validator has nothing to validate, and `C-1`/test provenance cannot be reconstructed.
- **Recommendation:** Add a `JavacRunner`/`JarBuilder` contract to `§4`/`§6`, a `DiffProvider` (`git diff C-1..worktree`) feeding `validateStep5` per file, and `parent_commit`/`test_patch` columns to `commits`; state which task builds `custom-quals.jar`. Improved state: every consumed artifact has a producer and a recorded path.

**I-28 — There is no Checker Framework provisioning or "processor actually ran" assertion. (Moderate)**
- **Evidence:** `§8:338-340` assumes `${CHECKERFRAMEWORK_HOME}/checker/dist/checker.jar` exists, falling back to `/opt/checker-framework`; nothing in the plan downloads, pins, or verifies it. No code checks for a "processor not found" diagnostic or asserts a nonzero diagnostic count.
- **Impact:** A missing or incompatible checker can produce a clean build with no type-checking, which Step 4/6 then record as success — compounding I-8.
- **Recommendation:** Add a provisioning step (download/verify a pinned version, or fail fast with a clear message), run a canary compilation that must emit a known error, and abort the run when the processor did not load. Improved state: "0 errors" cannot mean "no checker".

**I-29 — Prompt/agent-role contracts are absent, and the OpenCode CLI contract is unverified. (Moderate)**
- **Evidence:** `§4:134-138` names `triage.md`/`synthesis.md`/`application.md`/`fixer.md` with no content or output schema; `--agent agentRole` (`:220`) references roles defined nowhere in `§4`; no auth/retry/backoff handling is shown; the prompt is passed as one argv element (`:221`), risking `E2BIG` on large contexts; and the `--auto`/`--format json`/`step_start` event schema (`:216-220,237`) is assumed, never validated against a pinned CLI version.
- **Impact:** Prompts are unreproducible, roles may not exist, transient failures abort runs, and the only runner may be incompatible with the installed CLI.
- **Recommendation:** Define a JSON output schema per step and role files under a configurable agents directory; add retry/backoff with jitter; pass the prompt via stdin or a file; pin the CLI version and add a contract test that asserts the event schema. Improved state: the harness is a specified, testable interface.

**I-30 — Agent filesystem access is not scoped to the worktree. (Moderate)**
- **Evidence:** `runAgent(prompt, cwd, …)` (`§6:207-224`) accepts an arbitrary `cwd`; no caller, allowlist, or containment check is shown, and the fixer's permitted actions (design:135-136) are enforced only by prompt and a single-file AST check.
- **Impact:** A fixer (or a prompt-injected agent) can edit the pipeline, the database, or files outside the worktree while the run still reports success.
- **Recommendation:** Resolve `cwd` under a per-run root, reject symlinks/`..`, run as an unprivileged container user, and add a post-run diff-scope assertion that every changed path is inside the worktree. Improved state: the fixer's write set is mechanically bounded.

**I-31 — SQLite concurrency and the JSON column schemas are unspecified. (Moderate)**
- **Evidence:** `§1:19` and `§3:37` use a single `data/pipeline.db`, while Step 1 creates parallel ephemeral worktrees; no WAL mode, `busy_timeout`, connection policy, or writer serialization is specified. `config_snapshot JSON` (`:73`) and `triage_metadata JSON` (`:64`) have no schema.
- **Impact:** Parallel runs can hit `SQLITE_BUSY` or lose writes, and snapshots cannot be reliably queried or migrated.
- **Recommendation:** Enable WAL and a busy timeout, serialize writes (single writer or per-run DB merged post hoc), and publish JSON schemas/version fields for both columns. Improved state: parallel evaluation is safe and snapshots are reproducible.

**I-32 — There is no token or cost cap for agent loops. (Moderate)**
- **Evidence:** `§9:411-414` caps turns (`8`/`10`/`15`) and depth (`30`) but not tokens or dollars; `§6` records `eval.token_usage` only after the fact (design:259); a single run can invoke several agents across 30 commits.
- **Impact:** A runaway loop or a large repository can exhaust the project's budget before results are collected.
- **Recommendation:** Add per-run and per-commit token/cost caps with a kill switch, record spend per stage, and add a `--dry-run`/budget estimate before evaluation. Improved state: cost is a bounded, reported quantity.

**I-33 — The test configuration would discover no tests. (Minor)**
- **Evidence:** `§5:178` declares only `testImplementation(kotlin("test"))` and `§5:185-187` sets `useJUnitPlatform()`, but no `junit-jupiter-engine`/`junit-platform-launcher` runtime dependency is declared.
- **Impact:** With the JUnit Platform and no engine, Gradle discovers zero tests and reports success — a silent green build.
- **Recommendation:** Add `testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")` (and the launcher) and one smoke test that fails if discovery is empty. Improved state: "tests pass" means tests ran.

**I-34 — External identifiers are unvalidated; the CMSX URL is dead. (Minor)**
- **Evidence:** `§9:384` sets `repo_url: "https://github.com/cornell-cmsx/cmsx.git"`; the repository and organization return 404 (checked 2026-10-19), and `X-6` notes the empty `eval-repos/` directory but not the dead URL. The default model ID (`§9:404-408`) is likewise unverified against the provider's registry.
- **Impact:** `init` cannot populate the primary real-world target, and the default model may not resolve.
- **Recommendation:** Replace the URL with a verified repository (or mark it a placeholder), and make `init` validate the repo URL and model ID before writing the DB. Improved state: onboarding fails fast with an actionable message.

---

## 7. Cross-Cutting Findings

### 7.1 Continuity: proposal → design → README

README-specific findings are X-4 (shipping default vs philosophy), X-6 (documented evaluation repositories), X-9 (prevent vs repair), and X-10 (unmirrored guarantees). They sit here rather than in a separate section because the README is the governing document for all three artifacts.

**X-1 — Success-criteria drift; the developer-burden criterion is unmeasured. (Major)** — *(ties P-3; absorbs the former X-7)*
- **Evidence:** `proposal/Project Proposal.md §B` (lines 9–10) states two criteria; `docs/design_plan.md §1` restates the hypothesis as "Can domain-specific static type qualifiers… be automatically synthesized by an LLM to prevent real-world bugs, **and can those types guide a downstream 'fixer' agent**…?" (`:6`), which **adds** the blind-fixer conjunct and **drops** the burden conjunct; `README.md:10` repeats the design's version. All 27 top-level metrics in `design_plan.md §6` are machine-side; "burden" appears nowhere in design or README (grep-verified). Effort is concentrated accordingly: `design §4`–`§5` devote about 55 lines to the 19-vector matrix and guardrail modes, while `§6` has no adoption metric.
- **Impact:** Half the proposal's primary goal is structurally unfalsifiable; the design's metrics and engineering effort were built for the narrowed hypothesis, so the paper can report fix efficacy but not the adoption story that motivates the project.
- **Recommendation:** Add an "Acceptance Criteria" subsection to `design_plan.md §1` mapping each `§B` criterion to its operationalization **or** an explicit descope; add at least two burden proxies (`annotations_per_kloc`, `manual_interventions_count`) and a short qualitative discussion to `§6`. Improved state: both proposal criteria have a metric or a declared descope.

**X-2 — Several proposal promises are not carried forward. (Major)**
- **Evidence (grep-verified absent from both design and plan):**
  - Preventability classification + heuristic — `proposal §C.1` (lines 18–20): "classify it into either statically preventable or statically infeasible"; the design instead classifies "`bugfix` vs `non-bugfix`" (`design §3 Step 2:86`). Non-preventability is discovered only post hoc at `design §3 Step 4:127` after a wasted synthesis.
  - Modular prompt decomposition — `proposal §C.2` (line 22): "structure prompts modularly to independently request declarations and helper methods".
  - LLM-generated test baseline — `proposal §C.2` (line 23). (Replacement by ground-truth tests is an improvement but is undisclosed.)
  - Failure-driven fallbacks — `proposal §C.3` (lines 25–26): retry-with-feedback and per-function callgraph harnesses.
  - The "pass on the new (fixed) commit" half of the gold-standard check — `proposal §D.4` (line 38): "annotations always fail on the old commit **and pass on the new (fixed) commit**". Step 6 only runs the regression test on patched `C-1` (`design §3 Step 6:142-151`); nothing compiles commit `C` under the synthesized annotations.
  - Paper/presentation deliverables — `proposal §E:46-47`, `§F:51`.
- **Impact:** (a) The missing pass-on-`C` check means a `full_success` can coexist with an over-restrictive lattice that would make the human's true fix untypeable. This is the opposite failure direction from the one `design §7.1:267` worries about: §7.1 catches a *too-permissive* lattice (bug still typeable), while an over-restrictive lattice is never tested. (b) Reviewers comparing proposal to design see unexplained drops.
- **Recommendation:** Add a Step 6b "annotations accept ground-truth fix" check that ports `C`'s source fix onto annotated `C-1`, runs the Checker pass, and records `eval.annotations_accept_ground_truth_fix` + error count (or descope it explicitly). Document it as the complement to §7.1's adversarial game. For the rest, add a short "Scope changes vs. proposal" paragraph to `design_plan.md §1` and a paper/presentation section to `README.md §4`. Improved state: both over- and under-restrictive lattices are detected.

**X-3 — Terminology and tooling drift between design and plan. (Moderate)**
- **Evidence:** (1) AST engine: "tree-sitter" (`design §2:24,48`, `§3 Step 3:111`, `§4` rows) vs "JavaParser" (`README.md:83`, `implementation_plan.md §2:26`, `§7:287-288`). (2) `model.*` (design `§5:202-205`) vs `models.*` (plan `§9:403-408`). (3) `agent.steps.synthesis/fixer` (design `§5:206-207`) vs `limits.max_turns_synthesis/fixer` (plan `§9:411-413`). (4) `discard.test_compilation_failure` (design `§6:228`) vs `'discarded_non_compiling'` (plan `§3:63`). (5) `custom-quals.jar` vs `custom_quals_jar` vs `CUSTOM_QUAL_JAR` (design `§3:113`, plan `§9:420`/`§8:341`). (6) Step names in `step_trajectories` (`plan §3:85`) drop "blind"/"ground-truth" and omit ingestion entirely: five values (`triage`, `synthesis`, `application`, `fixing`, `verification`) for six steps.
- **Impact:** An implementer must guess which names are normative; metrics/config cross-references break.
- **Recommendation:** Add a one-page "canonical names" appendix to `design_plan.md` (step names, config keys, artifacts, discard reasons, AST library) and make the plan import from it verbatim.

**X-4 — The shipping default contradicts the stated philosophy. (Moderate)**
- **Evidence:** `README.md:25` states the pipeline "strictly disallow[s] `@SuppressWarnings`, `@AssumeAssertion`, framework utility casts… double-casts, raw types, signature drift, field modifications, and edits to test files." But `design §5:201` defaults `guardrails.mode` to `"lenient"`, and `design §4 'Mode B'` "Does *not* enforce strict raw-type or array covariance bans" (`:167`) and gives double-casts only a "Prompt warning only" (`:179`). The plan inherits the lenient default (`§9:401`).
- **Impact:** The default pipeline does not enforce the README's guarantees; a reader of the README would reasonably conclude otherwise.
- **Recommendation:** Either default to `strict` for headline results (using `lenient`/`disabled` as ablation arms, exactly as `§4`'s research purpose intends) or amend `README.md:25` to say "strictly disallowed **in strict mode** (default: lenient, see design §4)". Improved state: philosophy and default agree.

**X-6 — README documents evaluation repositories that do not exist, one with a dead URL. (Moderate)**
- **Evidence:** `README.md:71-73`: "`eval-repos/synthetic-id-swap/`: A minimal synthetic Java project with a known ID-conflation bug… `eval-repos/cmsx/`: Cornell CMSX codebase for real-world evaluation." The `eval-repos/` directory exists but is **empty** (verified: `ls -la eval-repos` shows no entries), and the configured CMSX URL (`implementation_plan §9:384`, `https://github.com/cornell-cmsx/cmsx.git`) returns 404 (checked 2026-10-19). Also, only `.gitignore` and `proposal/Project Proposal.md` are tracked by git; `README.md` and `docs/` are untracked.
- **Impact:** A reader following the README finds nothing; `init` cannot populate the primary real-world target; the repo's documentation overstates its current contents.
- **Recommendation:** Mark `eval-repos/*` entries as planned ("planned; not yet populated"), replace the dead URL with a verified repository or an explicit placeholder, and (as advice to the authors) commit `README.md`/`docs/` so the documentation guide matches the tree. Improved state: every documented path resolves.

**X-8 — Scope-vs-timeline tension. (Moderate)**
- **Evidence:** The design defers 11 future developments (`§7`) and the plan specifies a Kotlin engine, JavaParser validators, a Gradle init script, SQLite, an OpenCode watchdog, and a Python analytics layer — against the proposal's four-week implementation window (`proposal §E:45`).
- **Impact:** Risk that infrastructure is built at the expense of results.
- **Recommendation:** Define a thin-slice milestone (synthetic repo end-to-end through Step 6, one real bug) that produces a usable result before broad infrastructure, and add it as a milestone table in `README.md §4` with one row per stage and a target date. Improved state: the README shows a dated path to a usable result.

**X-9 — The goal is reframed from *prevention* to *repair* without an explicit note. (Moderate)**
- **Evidence:** `proposal/Project Proposal.md §B` goal 1 (line 9): "The new types meaningfully prevent domain-specific bugs in practice." The pipeline synthesizes annotations at `C-1` *from a bug that is already fixed downstream*, then evaluates whether a fixer *repairs* it (`design_plan.md §1`, `§3 Step 5`–`Step 6`); `README.md:10` restates the hypothesis as "curing the underlying runtime bug".
- **Impact:** The headline claim ("prevent") is broader than what is measured ("make a known historical bug statically detectable and enable a faithful repair"). A reader can over-generalize from a retrospective benchmark to prevention on unwritten code.
- **Recommendation:** Reconcile the wording across `proposal §B`, `design_plan §1`, and `README.md §1` to describe the retrospective detect-and-repair design explicitly (e.g., "the synthesized types make the latent bug statically detectable and guide a faithful repair"), and add a forward-looking demonstration (apply the lattice to a later commit that reintroduces the bug) to `§7 Future Developments` as a prevention test. Improved state: one claim, measured consistently.

**X-10 — Three README guarantees are not mirrored in the design or plan. (Moderate)**
- **Evidence:** `README.md:24` lists "issue keys" among the information the fixer never sees; `design §3 Step 5:133` omits them. `README.md:25` bans "field modifications"; `design §3 Step 5:139-140` protects tests and method signatures but not fields, and the plan's `AstGuard` has no field check (I-12). `design §5:211` offers `step3.allowed_checkers: "custom_only"` even though `§3 Step 3` permits only declarative `@SubtypeOf` hierarchies and built-ins (`design:98-100`).
- **Impact:** A reader of the README believes three behaviours are enforced that the design either omits or contradicts; one of them (field edits) is a live reward-hack surface.
- **Recommendation:** Add issue keys to design:133, add a field-declaration guard to `§3 Step 5`/`§4`, and either remove `"custom_only"` or define the custom-`BaseTypeChecker` policy it implies (D-7). Improved state: README, design, and plan enforce the same guarantee set.

---

## 8. Requirement Verification (F3–F6)

### 8.1 F3 — Is the design reasonable and likely to achieve meaningful research objectives, including the README's overarching goal?

**README goal (verbatim, `README.md:10`, §1 "Core Hypothesis & Problem of Interest"):**

> "**The Core Hypothesis**: An LLM can automatically synthesize domain-specific type annotations (and lightweight qualifier hierarchies) at commit $C-1$ (before a bug was fixed) such that a downstream **blind "fixer" agent**—which has never seen the bug report, PR description, or regression test—can fix the method bodies purely by resolving static type checker errors, thereby curing the underlying runtime bug."

Supporting philosophy (`README.md:18-31`, §2): modularity & flexibility above all; empirical configurability; rigorous agent isolation & anti-reward-hacking; rich relational observability; Gradle-first non-intrusive injection; aggressive descoping.

**Verdict: PARTIALLY SATISFIED.**

**Evidence for plausibility.** The design mirrors the hypothesis clause-by-clause: synthesize at `C-1` (`design §3 Step 3`), blind fixer resolves type errors (`§3 Step 5`), regression test proves the runtime cure (`§3 Step 6`). The threat model (`§4`), experimentation matrix (`§5`), and metrics registry (`§6`) give the project real experiment infrastructure, and `§7` correctly defers heavy scope — all consistent with README principles 2, 4, 6.

**Evidence against (threats to meaningful results):**
1. **No baseline/control (D-1)** — `§6` registers only outcome-level fields (`eval.regression_test_passed`, `eval.verdict`, `eval.token_usage`); a `full_success` cannot be attributed to the types. The README's causal claim would remain unproven.
2. **Blindness and synthesis independence are leaky (D-2, D-24, D-25)** — `§3 Step 3` consumes "bug/PR description"; helpers can name or encode the fix; the fixer worktree may retain git history. The "never seen the bug" clause is not literally true, and no leakage or overfitting metric exists.
3. **Developer burden (the proposal's co-equal goal) is unmeasured (X-1).**
4. **Yield collapse risk (D-4)** — the design itself concedes test patches often fail to compile on `C-1`; the core loop discards those commits, so the benchmark may be too small for meaningful conclusions.
5. **Philosophy/default mismatch (X-4)** — the default `lenient` mode ignores raw types, array covariance, and double-cast bans that `README.md:25` presents as strict guarantees.
6. **Non-intrusive injection absent from the design (D-5)** despite `README.md:29`; the plan implements it, so the design under-describes a load-bearing mechanism.
7. **Sample breadth** — `README.md:73` names a synthetic repo and one real repo; the proposal promised "a variety of open-source codebases" (`proposal §F:51`).
8. **Unmodeled repair shortcuts (D-27)** — a constant-return or deleted-branch "fix" can pass the regression test and every current gate.
9. **One run per bug (D-29)** — with no temperature, seed, or repetitions, a success cannot be separated from sampling luck.

**Concrete mitigations (in priority order):** (1) add the `fixer_no_types`/`fixer_builtin_only` baseline arms and report lift (D-1); (2) add `synthesis.bug_context` arms and helper-semantics fixtures, redefine blindness precisely, and log SNR/localization per run (D-2, D-24, D-25); (3) add burden proxies or descope explicitly (X-1); (4) promote `§7.5` test adaptation behind a toggle and set a yield target (D-4); (5) default headline runs to `strict` or amend the README (X-4); (6) port the `--init-script` injection contract into the design (D-5); (7) commit to ≥ 3 evaluation targets or state the generalization caveat (X-8); (8) add the semantic-body-replacement vector and a body-preservation check (D-27); (9) add repetitions, temperature, seed, and confidence intervals (D-29).

**With mitigations 1–4 and 8–9 applied, the design is, in this reviewer's judgment, *likely* to produce a meaningful result; without the baseline control and the overfitting/repetition controls in particular, it is at risk of producing an engineering report rather than evidence for the stated hypothesis.**

### 8.2 F4 — Does the implementation plan match the refined design exactly?

**Verdict: NOT SATISFIED.** The plan reproduces the design's *skeleton* (six step files, a `verdict` column with the four-value domain, the guardrail-mode value set, the qual-jar) but omits or merely names most design guarantees.

**One-to-one fidelity map** (Design item → Plan counterpart → Status). Status definitions: **MATCHED** = a plan artifact implements the item; **PARTIAL** = named or partly implemented, with a body, check, or input missing; **DIVERGENT** = both artifacts specify the item but inconsistently; **MISSING** = no plan counterpart.

| # | Design item (location) | Plan counterpart | Status |
| :-- | :-- | :-- | :-- |
| 1 | Step 1 worktrees + pre-warmed caches (`§2`,`§3 S1`) | `§4 S1Ingestion.kt`, `§10:433-434 init` | PARTIAL |
| 2 | Step 2 simple LLM triage (`§2`,`§3 S2`) | `§4:119 S2Triage.kt`, `§4:135 triage.md` | PARTIAL (file + prompt named; no code, no single-shot runner — I-7) |
| 3 | Step 3 iterative `javac` synthesis loop (`§2:23`,`§3 S3`) | `§4:120 S3Synthesis.kt`; Gradle only | PARTIAL (no `javac` runner, no jar build — I-27) |
| 4 | Step 4 agent + AST validator (`§2:24`,`§3 S4`) | `§4:121,125 S4Application.kt`, `AstGuard.kt` | PARTIAL |
| 5 | Step 5 blind repair loop (`§2:25`,`§3 S5`) | `§4:122,138 S5Fixer.kt`, `fixer.md` | PARTIAL |
| 6 | Step 6 deterministic Gradle test (`§2:26`,`§3 S6`) | `§4:123 S6Verification.kt`, `GradleRunner.kt` | PARTIAL (files named only; no code) |
| 7 | `placement_guide.md` artifact (`§3 S3:114`) | — (grep: 0) | **MISSING** (I-2) |
| 8 | Step 4 `strip_annotations` gate (`§3 S4:123`) | — (only `validateStep5`) | **MISSING** (I-1) |
| 9 | Step 4 retry-with-feedback (`§3 S4:124`) | — | **MISSING** (I-1) |
| 10 | Non-triviality halt + `application.is_trivial` (`§3 S4:127`,`§6:248`) | — | **MISSING** (I-8) |
| 11 | Purity verification, 3 mechanisms (`§3 S3:107-111`) | `PurityValidator.kt` name only; `-AcheckPurityAnnotations` in strict (`§8:360`) | PARTIAL (validator body absent — I-13) |
| 12 | `src/test/**` read-only (`§3 S5:139`) | — | **MISSING** (I-14) |
| 13 | Method-signature lock (`§3 S5:140`) | `§7:306-312` | PARTIAL (blind to annotations/constructors/fields — I-12) |
| 14 | Guardrail modes A/B/C (`§4:158-169`) | `§9:400-401`, `§8:356`, `§7:285` | PARTIAL (mode never wired; validator ignores lenient/strict — I-6, I-26) |
| 15 | 19 attack vectors (`§4:173-193`) | 3 implemented / 5 partial / 11 missing | PARTIAL (aggregate 3/5/11 — I-4) |
| 16 | `prompt.tell_non_triviality_error` (`§5:208`) | — | **MISSING** (I-5) |
| 17 | `step5.local_variable_annotations` (`§5:209`) | — | **MISSING** (I-5) |
| 18 | `step5.internet_access` (`§5:210`) | — | **MISSING** (I-5) |
| 19 | `step3.allowed_checkers` (`§5:211`) | — (processor hardcoded `§8:349`) | **MISSING** (I-5) |
| 20 | `step3.freedom_level` (`§5:212`) | — | **MISSING** (I-5) |
| 21 | Metrics `synthesis.*` (5), `application.*` (5), `fixer.*` (4), `triage.*` (10), `eval.*` (3) (`§6`) | only `step_trajectories.metrics JSON`, `triage_metadata JSON`, `bugfix_score`, `eval.verdict` | PARTIAL (schema-only; most never written) |
| 22 | 4 verdicts (`§3 S6:147-151`) | `§3:75` `evaluation_runs.verdict` | MATCHED |
| 23 | Discard reasons ×4 (`§6:226-230`) | `§3:63` (2 of 4) | PARTIAL (I-18) |
| 24 | AST library (design: tree-sitter; README: JavaParser) (`§2:24`,`§4`) | JavaParser (`§2:26`) | **DIVERGENT** (X-3) |
| 25 | Config `agent.steps.synthesis/fixer` (`§5:206-207`) | `limits.max_turns_*` | PARTIAL (renamed; needs a canonical map) |
| 26 | Step 3 output `custom-quals.jar` (`§3 S3:113`) | only reads `/tmp/custom-quals/...` (`§8:341`) | **MISSING** (no build step — I-27) |
| 27 | Diff/patch input to `validateStep5` (`§3 S5:122-124`) | `validateStep5(String,String)` (`§7:284`) | **MISSING** (no acquisition path — I-27) |
| 28 | `C-1`/test-patch provenance for Steps 2/6 (`§3 S2:88-90`) | `commits` table (`§3:52-66`) | **MISSING** (no `parent_commit`/test patch — I-27) |
| 29 | Existing-suite baseline before Step 5/6 (`§3 S4:126`,`§6:148`) | — | **MISSING** (D-30) |
| 30 | Trivial-lattice failure verdict (`§2:51`,`§3 S4:127`) | — | **MISSING** (D-35) |
| 31 | Mode A guard set (`§4:160-161`) | `§7:285` (only `disabled` branch) | **DIVERGENT** (D-33, I-26) |
| 32 | PR metadata acquisition (`§2:22`,`§3 S2:84`,`§6:234`) | `commits.pr_*` columns only (`§3:57-59`) | **MISSING** (D-31) |
| 33 | Instruction-injection defense (`§4:173-193`) | — | **MISSING** (D-32) |

**Plan components not specified by the design (some justified, some revealing design omissions):** `models.application` (`§9:407`), `limits.max_turns_application` (`§9:412`) are justified additions that expose design omissions (D-14); `limits.commit_depth` (`§9:414`) is justified by design `§2` ("Commit Depth $d$"); `project.*`/`build.*`/`environment.*`/`paths.*` (`§9:382-420`) are necessary plumbing the design never specifies, so their contracts should be stated in the plan (I-6).

**Divergences with exact locations** are enumerated in items 7–33 above and detailed in I-1…I-34, D-30…D-35, and X-3. Additional bare divergences: design says Step 4's validator harness is "`tree-sitter`" (`design §2:24`) while the plan's `§4:125` comment claims "JavaParser strip-and-compare"; the design's `§2` mermaid includes a "Trivial Lattice" fail terminal (`design:51`) and an "Existing C-1 Unit Tests Pass?" gate (`design:59`) with no plan counterpart; the plan stores `regression_test_name` singular (`§3:62`) against the design's plural test-name extraction (`§3 S2:87`); and the plan's `step_name` enum omits ingestion (`§3:85`, X-3).

**Conclusion:** the plan is a faithful *outline* but not an exact instantiation. Major gaps: I-1 (Step 4 gate), I-2 (`placement_guide.md`), I-3 (canary), I-4 (3 full / 5 partial / 11 missing vectors), I-5 (5 config keys), I-6 (config wiring), I-7 (Step 2), I-8 (triviality halt), I-25–I-27 (build/artifact plumbing), D-24/D-25/D-27/D-29 (validity controls).

### 8.3 F5 — Does the implementation plan enforce modularity very strongly?

**Verdict: PARTIALLY SATISFIED** (satisfied on package decomposition and LLM/prompt swappability; **not** satisfied on contracts, isolation, testability, or hidden coupling).

Against `README.md:18-19` ("All pipeline stages, prompt templates, AST validators, and build runners must be loosely coupled and implement strict abstract interfaces"):

Scale for this table: **SATISFIED** / **PARTIAL** / **NOT SATISFIED**; **RISK** = no code is shown, but the structure makes a violation likely.

| Dimension | Verdict | Evidence |
| :-- | :-- | :-- |
| Explicit module boundaries | PARTIAL | `plan §4:110-143` gives a clean package tree, but no dependency rules or visibility enforcement. |
| Defined interfaces/contracts | **NOT SATISFIED** | Only `§4:117` "`PipelineStep.kt  # Step interface`" — no signature anywhere; the only substantive code snippets are in `§5`, `§6`, `§7`, `§8` (none of which define step contracts). No `StepInput/StepOutput` types; handoffs are opaque DB blobs (`§3:81-93`). |
| Decoupling / low coupling | PARTIAL | Kotlin+SQLite+Python split is genuinely decoupled (`§1:17-20`); but steps coordinate via shared mutable DB/filesystem rather than injected ports. |
| Swappable components | PARTIAL | Strong: per-stage model keys (`§9:403-408`), prompt templates (`§4:134-138`). Weak: AST hard-wired to JavaParser (`§2:26`,`§5:169`), build hard-wired to Gradle (`§4:130`), storage hard-wired to SQLite (`§5:171`), "LLM swap" only changes a model string to a fixed `opencode` CLI (`§6:216-221`). |
| Testability in isolation | **NOT SATISFIED** | No `src/test` in `§4`; only `testImplementation(kotlin("test"))` (`§5:178`), with no JUnit engine (I-33). `OpenCodeRunner`/`AstGuard` are concrete with inline `ProcessBuilder`/`StaticJavaParser.parse`; no DI, no fakes. |
| Hidden coupling | **NOT SATISFIED** | Hard-coded `/opt/checker-framework` (`§8:338`), `/tmp/custom-quals` (`§8:341`,`§9:419-420`), a five-variable env bridge (`§8:338-368`), a single `data/pipeline.db`, and a shared Gradle cache. |
| God-modules | RISK | `Main.kt`, `DatabaseManager.kt`, and S1–S6 have no code; a single DAO for four tables and multi-turn S3/S5 steps are large, multi-responsibility modules. |
| Config as a contract | **NOT SATISFIED** | `ProjectConfig` is named (`§4:113`) but not defined or injected in any shown file; no runner accepts it (I-6). |

**Modularity findings and recommendations:**
- **M-1 (Major)** — `PipelineStep` interface is not defined in the shown plan (`§4:117`, absent elsewhere). Recommendation: in `src/main/kotlin/pipeline/steps/PipelineStep.kt`, specify `interface PipelineStep<I,O> { val name: String; fun execute(ctx: RunContext, input: I): StepResult<O> }` plus a sealed `StepResult`, and show S2/S5 conforming. Improved state: the six steps share one contract.
- **M-2 (Major)** — No DI or test seams (`§6:206`,`§7:283`). Recommendation: inject `AgentRunner`, `ParserFacade`, `BuildRunner`, `DatabasePort` into `steps/*` and `validators/*`; add fakes under `src/test/kotlin/pipeline/` and a `src/test` plan. Improved state: every component is constructible with a fake.
- **M-3 (Major)** — Typed I/O absent; steps exchange untyped DB blobs (`§3:81-93`). Recommendation: define per-step `data class` outputs in `steps/` passed through `RunContext`; make `DatabaseManager` a separate `TrajectoryRecorder` port. Improved state: handoffs are typed and checkable.
- **M-4 (Major)** — No testability-in-isolation story (`§5:177-187`). Recommendation: add per-component unit tests under `src/test/kotlin/pipeline/{validators,runners,steps}` with mocked runners (validators are pure and easy to test first). Improved state: the suite runs without a JVM child process or network.
- **M-5 (Moderate)** — `OpenCodeRunner` mixes spawn + watchdog + parsing (`§6:206-262`). Recommendation: split `AgentRunner` (spawn), `TranscriptParser` (JSON events), and `WatchdogPolicy` (deadline/steps) in `runners/`. Improved state: each concern is testable alone.
- **M-6 (Moderate)** — Hard-coded paths + env-var wiring (`§8:338-368`). Recommendation: a single `ToolchainConfig` rendered into `resources/templates/checker.init.gradle`; derive a per-run qual-jar path. Improved state: no global environment is required.
- **M-7 (Moderate)** — `GradleRunner` has no interface (`§4:130`) → Maven (§7.9) unreachable. Recommendation: `interface BuildRunner { fun compile(wt: Worktree, cfg: BuildConfig): BuildResult; fun test(wt: Worktree, sel: TestSelection): TestResult }` + `GradleBuildRunner` + `BuildInjectionStrategy { fun render(cfg: ToolchainConfig): Path }`. Improved state: a Maven runner is a new implementation, not an edit.
- **M-8 (Minor)** — `mode: String` (`§7:284`) is an unvalidated string. Recommendation: `enum class GuardrailMode { DISABLED, LENIENT, STRICT }` + `GuardrailSet.forMode(...)`. Improved state: invalid modes fail at parse time.

### 8.4 F6 — Can every future development in the design/plan be implemented easily, and does anything impede them?

**Verdict: NOT SATISFIED** — 3 accommodated (rows 3, 4, 11), 7 at-risk (rows 1, 5, 6, 7, 8, 9, 10), 1 impeded (row 2). Severity mapping: IMPEDED = Major, AT-RISK = Moderate, ACCOMMODATED = no severity. The verdict is all-or-nothing under F6's "every future development" wording; the tally shows how far the plan is from that bar.

| # | Future development (`design_plan §7`) | Verdict | Enabling structure / blocker (cited) | What to change |
| :-- | :-- | :-- | :-- | :-- |
| 1 | Adversarial bug-preserving fixer game (`§7.1:265-271`) | **AT-RISK** | Enabling: `guardrails.mode=strict` (`§4:168`), generic `runAgent` (`plan §6:207-214`), additive prompts (`plan §4:134-138`). Blocker: `§7.1` says "Replace or augment Step 5" and requires a "Synthesizer ↔ Adversarial Fixer" game, but there is **no orchestrator/loop executor** and no adversarial agent/model key. | Add `fixer.strategy: cooperative\|adversarial`, an outer game-loop executor re-invoking S3↔S5, `templates/prompts/adversarial_fixer.md`, `models.adversarial_fixer`. |
| 2 | Level 3 domain wrapper records + refactoring (`§7.2:273-279`) | **IMPEDED** | `step3.freedom_level` enumerates only `level0/level1/level2` (`§5:212`); policy fixed to "Level 1 + Level 2 Hybrid" (`§3:106`); no refactoring runner or OpenRewrite dep (`plan §4:128-130`,`§5:160-179`); Step 4 `strip_annotations` equality (`§3:123`) and Step 5 signature lock (`§3:140`) contradict signature migration (`§7.2:278`). | Add `"level3"`; add a refactoring step/`RefactoringRunner`; define a signature-lock/AST-gate exemption scoped to refactoring; define a records artifact path. |
| 3 | Error localization & SNR (`§7.3:281-286`) | **ACCOMMODATED** | JavaParser in-process (`plan §2`), git diff available (`§3 S1`), and `metrics JSON`/`compiler_diagnostics`/`application.total_checker_errors` exist. Minor gap: no `localization.tier` key. | Recommended: add `localization.tier` and an `application.snr` metric. |
| 4 | Continuous triage scoring (`§7.4:288-292`) | **ACCOMMODATED** (with a caveat) | `bugfix_score REAL` already exists (`plan §3:61`), `triage.continuous_scores` is registered (`§6:223`), and `triage.bugfix_threshold` is an additive `§5` key. | Add `triage.bugfix_threshold` to `§5` and the YAML; until then the future has no operative threshold. |
| 5 | LLM-assisted regression test adaptation (`§7.5:294-299`) | **AT-RISK** | `S2Triage.kt` is a filename only and Step 2 has no code or runner (I-7); there is no harness for the required three-way check (compiles/fails on `C-1`, passes on `C`), and adapting a discarded commit changes the Step 2 invariant. | Add `step2.test_adaptation: off\|llm`; add a `RegressionTestHarness.verify(test, C-1, C)` that runs compile/fail/pass; add the prompt and model key. |
| 6 | Adversarial test synthesis & falsification loop (`§7.6:301-305`) | **AT-RISK** | Same missing loop executor as #1; `§7.6:305` requires "up to K rounds"; no `K` config, no adversarial-test role. | Add an outer loop executor, `adversarial_tests.max_rounds`, an agent role/prompt, and a runtime-failure probe via `GradleRunner`. |
| 7 | Adversarial type applier (`§7.7:307-312`) | **AT-RISK** | Option A (prompt) is fine (`plan §4:137`); Options B (deterministic AST) and C (mutation) have no home — concrete `S4Application.kt`, no placement-strategy seam, no machine-readable hierarchy, no mutation engine. | Add `step4.strategy: agent\|deterministic\|mutate` and a parseable hierarchy artifact. |
| 8 | Test-suite annotation vs stripping (`§7.8:314-317`) | **AT-RISK** | Injection covers test compilation (`plan §8:326-327`) and `paths.test_dir` is configurable (`§9:418`), but there is no test-scoped Checker toggle, and D-19 shows the Step 6 "standard `gradle test`" path treats qualifiers as inert — so the future has no switch to extend. | Add `test.checker_enabled: bool` and define whether Step 6 runs Checker on tests; add a `test_annotator` role/prompt. |
| 9 | Non-intrusive Maven support (`§7.9:319-322`) | **AT-RISK** | `build.tool` is only a string (`plan §9:389`); `README.md:19` mandates "strict abstract interfaces" but only concrete `GradleRunner.kt` exists (`§4:130`); injection is Gradle-only (`§8`). | Introduce a `BuildRunner` interface + factory; add `MavenRunner.kt` + a Maven compiler-wrapper template. |
| 10 | Dynamic multi-JDK management (`§7.10:324-328`) | **AT-RISK** | `environment.java_home`/`source_compatibility` are single project-level scalars (`plan §9:397-398`); no per-commit JDK resolution; the design *skips* incompatible-JDK commits (`§3 S1:79-80`). | Add per-commit bytecode-target detection + a JDK-selection hook, record the chosen JDK in `config_snapshot`, replace the skip path. |
| 11 | `.astub` overlay & injection (`§7.11:330-335`) | **ACCOMMODATED** | Overlay mirrors the existing `CF_AQUALS` → `-Aquals` pattern (`plan §8:365-368`); `argsList` is mutable; JavaParser available for injection. | Add `CF_ASTUBS`/`-Astubs` + a JavaParser injection utility. |

**Most serious (full finding format):**

- **F6-1 (IMPEDED = Major) — Level 3 records (design `§7.2`).** Evidence: `step3.freedom_level` enumerates only `level0/level1/level2` (`design:212`), the selected policy is fixed to "Level 1 + Level 2 Hybrid" (`design:106`), and no refactoring runner/OpenRewrite dependency exists (`plan §4:128-130`,`§5:160-179`). Impact: not an additive bolt-on; it needs a new freedom-level value, a refactoring subsystem, and exemptions from the annotation-only and signature-lock invariants. Recommendation: add `"level3"` and a `RefactoringRunner` step, define a scoped signature-lock/AST-gate exemption, and record a records-artifact path. Improved state: Level 3 is a strategy value plus one step, not a rewrite.
- **F6-2 (AT-RISK = Moderate) — No orchestrator / inter-step loop executor (design `§7.1`,`§7.6`; plan `§4:110-143`).** Impact: adversarial games and falsification loops cannot be "naturally plugged in" (README §2, principle 1) without retrofitting the core run sequence. Recommendation: define an orchestrator with a step graph and loop semantics; model games as composable step groups; add `adversarial_tests.max_rounds`.
- **F6-3 (AT-RISK = Moderate) — Build-runner abstraction absent, so Maven (design `§7.9`) is unreachable (README §2, principle 1).** Recommendation: `BuildRunner` interface + factory keyed off `build.tool`.
- **F6-4 (AT-RISK = Moderate) — Single hard-coded `java_home`, so multi-JDK (design `§7.10`) is not per-commit.** Recommendation: per-commit JDK resolution recorded in `config_snapshot`.
- **F6-5 (AT-RISK = Moderate) — Adversarial-applier strategies B/C have no seam (design `§7.7`).** Recommendation: `step4.strategy` + machine-readable lattice.

**Note on additional deferred items outside `§7`:** `proposal §D.3:37` ("without information about the specific bug") and `§C.3:25-26` (retry-with-feedback; per-function callgraph harness) are not represented in `design §7` or the plan. If they are to be future work, they need the same extension-point treatment (particularly the blind-synthesis variant, which has neither an isolation nor a config seam).

---

## 9. Prioritized Action Plan

Ordered by (severity, cheapness). "Where" names the artifact and section. The plan is intentionally selective: every Major finding has a P0/P1 row, the highest-leverage Moderates have P2 rows, and the remaining Minor/Moderate items are grouped in the P3 rows. Each row is a complete action, not a pointer.

| Priority | Action | Where | Addresses |
| :-- | :-- | :-- | :-- |
| P0 | Add baseline arms (`fixer_no_types`, `fixer_builtin_only`) and report lift as the primary metric. | `design_plan §5`,`§6`; `project_config.yaml` | D-1 |
| P0 | Snapshot the Checker diagnostics on clean `C-1` and define success as "0 *new* errors relative to baseline". | `design_plan §3 S4/S5`,`§6`; `implementation_plan §7` | D-22 |
| P0 | Implement the Step 4 `strip_annotations` AST gate + feedback retry; define `strip` precisely incl. imports. | `design_plan §3 S4`; `implementation_plan §7` | D-9, I-1 |
| P0 | Add `placement_guide.md`, the canary test, the non-triviality halt + `application.is_trivial`. | `implementation_plan §3`,`§4`,`§7` | I-2, I-3, I-8 |
| P0 | Wire `guardrails.mode` and all toolchain settings from `ProjectConfig` → init script per run; add an integration test. | `implementation_plan §8`,`§9`; `GradleRunner` | I-6, I-15, M-6 |
| P0 | Add `synthesis.bug_context` arms and a held-out/second-bug acceptance test. | `design_plan §5`,`§3 S3`; `implementation_plan §9` | D-24 |
| P0 | Add a semantic-body-replacement vector and a body-preservation check. | `design_plan §4`,`§3 S5`; `implementation_plan §7` | D-27 |
| P0 | Add `experiment.repetitions`, temperature, seed, and confidence-interval reporting. | `design_plan §5`,`§6` | D-29 |
| P0 | Specify the sandbox contract and wire `step5.internet_access` to an egress allowlist. | `design_plan §3 S1`; `implementation_plan §4`,`§6` | D-23, P-13 |
| P1 | Add the five missing design config keys and wire each to its stage. | `implementation_plan §9` | I-5 |
| P1 | Resolve the triage taxonomy (five-way + pure-bugfix filter) and the discard-reason enum. | `design_plan §3`,`§6`; `implementation_plan §3` | D-3, I-18 |
| P1 | Add burden proxies/descope, the pass-on-`C` check (Step 6b), and the proposal's evaluation controls. | `design_plan §1`,`§6`; `README.md`; `proposal §D` | P-3, X-1, X-2, P-19 |
| P1 | Redefine blindness, scrub/rename helpers, strip `.git` from the fixer worktree, add SNR/localization and helper-semantics fixtures. | `design_plan §3 S5`,`§6` | D-2, D-25 |
| P1 | Define the `PipelineStep` contract + typed step I/O + DI seams + a `src/test` plan. | `implementation_plan §4`–`§7` | M-1…M-4, I-21 |
| P1 | Implement the remaining attack vectors (AST + build + FS guards). | `implementation_plan §7`,`§8` | I-4 |
| P1 | Fix `prompt: str`, drain stderr, enforce `timeoutSeconds`, record timeouts; correct the CLI binary path. | `implementation_plan §5`,`§6`,`§10` | I-9, I-10, I-11, I-16 |
| P1 | Fix the default injection config: valid default checker/`-Aquals`, preserve annotation-processor discovery. | `implementation_plan §8` | I-25 |
| P1 | Rewrite `AstGuard` for multi-file comparison, class-qualified keys, removal detection, and mode-specific checks. | `implementation_plan §7` | I-26 |
| P1 | Add the `javac`/jar build, diff acquisition, and `parent_commit`/test-patch columns. | `implementation_plan §3`,`§4`,`§6`,`§7`; `design_plan §2` | I-27 |
| P1 | Make `src/test/**` read-only and add a path-scoped diff guard. | `implementation_plan §6`,`§7` | I-14 |
| P1 | Add the proposal's RQs/related work/central citation, and fix the `@NotNull` and risk claims. | `proposal §A`,`§B`,`§C` | P-1, P-2, P-8, P-17, P-18, P-23 |
| P2 | Add `BuildRunner`, `AgentRunner`, `StoragePort`, `Guardrail`, and `ConfigSource` interfaces; make JavaParser/Gradle/SQLite swappable. | `implementation_plan §4`–`§8` | M-2, M-3, M-5, M-7, M-8, I-6, F6-3 |
| P2 | Add a `PipelineOrchestrator` with loop semantics (unblocks adversarial futures). | `implementation_plan §4` | F6-2 |
| P2 | Add `"level3"`, a `RefactoringRunner`, and scoped gate exemptions for Level 3 records. | `design_plan §3`,`§5`,`§7.2` | F6-1 |
| P2 | Add `model.application`/`agent.steps.application`; resolve `level1+2`; resolve the canary/declarative-lattice contradiction. | `design_plan §3`,`§5` | D-6, D-7, D-14 |
| P2 | Promote `§7.5` test adaptation behind a toggle; add a three-way regression-test harness; set a yield target. | `design_plan §3 S2`,`§7.5`; `implementation_plan §9` | D-4, F6-5 |
| P2 | Port the `--init-script` contract into the design; declare JavaParser normative; add a canonical-names appendix. | `design_plan §2`,`§3`; new appendix | D-5, D-17, X-3 |
| P2 | Add Step 2 triage implementation (single-shot `LlmClient`), the prompt output schema, and a metadata client. | `implementation_plan §4`,`§6`; `design_plan §3 S2` | I-7, D-31 |
| P2 | Add a green-suite baseline on `C-1` (test analogue of D-22). | `design_plan §3 S2`,`§6`; `implementation_plan §3` | D-30 |
| P2 | Add an instruction-injection vector, canary, and prompt logging. | `design_plan §4`; `implementation_plan §6` | D-32 |
| P2 | Add Checker provisioning/load assertion and prompt/role/CLI contracts. | `implementation_plan §8`,`§6`,`§4` | I-28, I-29 |
| P2 | Scope agent writes to the worktree; add WAL/busy-timeout and JSON schemas; add token/cost caps. | `implementation_plan §3`,`§6`,`§9` | I-30, I-31, I-32 |
| P2 | Add per-commit JDK resolution and strategy seams for the adversarial applier. | `design_plan §7.10`,`§7.7`; `implementation_plan §9` | F6-4, F6-5 |
| P3 | Fix `AstGuard` signature normalization (annotations, constructors, fields) and implement `PurityValidator`. | `implementation_plan §7` | I-12, I-13 |
| P3 | Add `-Awarns`/`.astub`/`-AskipDefs` guards; add the JDK toolchain to `build.gradle.kts`; add the JUnit engine. | `implementation_plan §8`,`§5` | I-20, I-22, I-33 |
| P3 | Add the proposal's triage protocol, contribution list, context/scope section, and cosmetics/header. | `proposal §A–§F` | P-9, P-10, P-14, P-15, P-16, P-20, P-21, P-22 |
| P3 | Mark `eval-repos/*` as planned; fix the dead CMSX URL; advise the authors to commit `README.md`/`docs/`. | `README.md §4`; `implementation_plan §9` | X-6, I-34 |
| P3 | Align the default guardrail mode with the README (or amend the README). | `README.md §2, principle 3`; `design_plan §5` | X-4 |
| P3 | Clarify remaining design details (cache writability, tree-sitter purity scope, Step 6 helper calls, `§7.8` motivation, Level 0 wording, dead-code rule, turn budgets, diagram edges, Mode A, trivial verdict). | `design_plan §2`–`§7` | D-11, D-12, D-15, D-16, D-18, D-19, D-20, D-21, D-33, D-34, D-35 |
| P3 | Reconcile the "prevent" vs. "repair" framing and add a forward-looking prevention test to future work. | `proposal §B`; `design_plan §1`,`§7`; `README.md §1` | X-9 |
| P3 | Add the README-guarantee fixes (issue keys, field guard, `custom_only`). | `README.md`; `design_plan §3`,`§5` | X-10, D-7 |

---

## 10. Areas Examined and Found Adequate

Stated explicitly, per the coverage/honesty requirement (Q4), so the report does not overstate the defect count:

- **Proposal citation accuracy.** The `§A` claim that Android memory-safety vulnerabilities fell "below 20% in 2025" matches the cited source (verified).
- **Pipeline stage ordering and interfaces of *concept*.** In both the proposal (`§C`) and design (`§1`,`§3`), each stage's output is a well-defined input to the next, and each has a verification point — internally coherent.
- **Ground-truth strategy.** Using the pre-fix commit and the historical human fix as an oracle is methodologically sound; `design §3 Step 2`'s deterministic fail-on-`C-1` invariant is a genuinely good cheap soundness proxy.
- **Threat-model breadth.** `design §4`'s 19-vector enumeration is unusually thorough for a course project. Most gaps are in coverage or wiring (I-4: 3 fully, 5 partially, 11 not implemented), but not all: a few vectors rest on mechanisms that cannot deliver the claimed guarantee (tree-sitter purity/call-target checks, D-16), and one relevant channel — instructions embedded in repository content — is absent (D-32).
- **Descoping philosophy.** `design §7` + `README.md:31` deliberately defer Level 3, Maven, multi-JDK, and `.astub`; this is the right instinct.
- **Non-intrusive build injection concept.** `implementation_plan §8`'s init-script approach avoids editing target build files, which is the right shape; the defects are in the invocation, not the concept (I-15, I-25).
- **Language split.** Kotlin + SQLite + Python (`implementation_plan §1`) is a sound decoupling choice, and `§3`'s DDL is concrete; the single-writer and schema issues are I-31.
- **Checker Framework idioms.** `@SubtypeOf`/`SubtypingChecker`, `@EnsuresQualifierIf`/`@EnsuresQualifier`, `@Pure`/`@SideEffectFree`, `-AinvariantArrays`, `-AignoreRawTypeArguments`, and `-Alint=cast:unsafe` are genuine framework constructs rather than invented APIs; their exact semantics are asserted from background knowledge, not verified against upstream documentation (see §11). Specific purity enforcement remains open (D-16, I-13).
- **LLM/prompt swappability.** Per-stage model keys (`§9:403-408`) and prompt templates (`§4:134-138`) are a real extension point for model and prompt changes; the runner itself remains the fixed `opencode` CLI (§8.3).
- **Verdict taxonomy.** The four `eval.verdict` values (`design §3 S6:147-151`) are well chosen and consistently mirrored in the DB (`plan §3:75`).

---

## 11. Limitations and Confidence

- **No code exists to review.** The repo contains only documentation; every finding about "the plan" concerns planned structures, and modularity/fidelity findings are about the *specification*, not running software. If implementation has progressed elsewhere, re-verify.
- **Line numbers** refer to the files as read in this review. The proposal file may lack a trailing newline (`wc -l` reports 55 while the last content line is 56); citations use section headings wherever possible.
- **Hidden files not shown.** The plan references `PipelineStep.kt`, `GradleRunner.kt`, and `S2Triage.kt` bodies that are not included; where behavior depends on them (e.g., whether `GradleRunner` sets `GUARDRAILS_MODE`), findings are phrased as "no shown mechanism" rather than "absent", to avoid overclaiming.
- **Verification split.** Claims about the artifacts themselves (presence, absence, counts, quotations) were read- or grep-verified. External facts were checked only where an argument depends on them: the Android/Rust statistic was verified against the cited post; the CMSX URL was checked and returns 404; and the JavaParser behavior in I-12 was checked against the `MethodDeclaration` source for 3.26.1. Other statements rest on background knowledge and were not independently verified upstream: that Kafka's core is Scala with Java clients (P-5); that the `-vl` token denotes a vision-language variant and whether that model is suitable for code (D-13); that Gradle's `installDist` names the distribution after the project (I-16); and that the Checker Framework flags and annotations named in §10 exist with the stated semantics. Treat these as hypotheses to confirm at implementation time.
- **Scope-outs.** The review flags but does not fully analyze: SQLite concurrency under parallel runs (I-31), prompt injection through repository content (D-32), determinism of LLM sampling (D-29), JSON column schemas (I-31), token/cost caps (I-32), and the Checker-version × target-JDK compatibility matrix. It also does not execute any Checker Framework, Gradle, or JavaParser code; all such claims are static-reading claims.
- **Bias controls.** Per-artifact extraction and cross-cutting audits were performed independently and then reconciled; where a sub-audit overreached, it was corrected here (e.g., `limits.commit_depth` is justified by the design's "$d$", and `models.application` is a *design omission*, not an unjustified plan addition).
- **Corrections applied during drafting (visible in the current text).** The attack-vector tally is stated as 3 fully / 5 partially / 11 unimplemented (I-4); the metric count is 27, not 28; the "strictly isolated" quote is attributed to `design_plan.md §1` rather than `§3 Step 5` (D-2); yield risk is stated as unmeasured rather than near-zero (D-4); the missing design keys are counted as five (I-5); and the model-capacity claim is marked as an inference (D-13).
- **Confidence.** High for artifact-internal findings (quotations, presence/absence, counts), which were re-read or grep-checked; medium for the external-knowledge claims above; lower for severity judgments that depend on unmeasured quantities (yield, model capacity, reviewer expectations), which are stated as hypotheses together with the evidence that would change them.

---

*End of report. No tracked source file — proposal, design, plan, README, or code — was modified; the working tree also contains `docs/` and `README.md` as untracked files, and this report is the only file added by this review.*
