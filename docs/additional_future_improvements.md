# Additional Future Improvements for the LLM Type-Annotation-Synthesis Pipeline

**Status:** design-stage research agenda, 19 October 2026. The repository contains a proposal, a design plan, an implementation plan, a critique report, and a `README.md`; `eval-repos/` is empty. There is no implementation code, experiment, or result. This document proposes work; it reports none.

## Executive summary

This document specifies 26 additional research proposals for a pipeline that synthesizes domain-specific Checker Framework qualifiers at commit `C-1` and asks a blind fixer to repair a bug by resolving type errors. "Additional" is strict: the eleven sketches in `docs/design_plan.md` §7 and the 41-row action plan in `docs/critique_report.md` §9 are excluded unless a proposal adds a new causal contrast, measurement, or population.

The catalogue covers nine themes: causal identification, benchmark and outcome validity, type-system expressiveness and burden, agent architecture, adversarial robustness, reproducibility and cost, external validity, human factors, and falsification. Each gives its gap, mechanism, evaluation, falsifiable prediction, risks, and delta versus the closest existing item.

The highest-value additions are: (1) a pre-registered hierarchical analysis with repeated runs and censoring-aware estimands; (2) a factorial decomposition of the diagnostic, annotation, and helper channels; (3) behavioral-equivalence and placebo outcomes for whether a test pass is a real fix; (4) a preventability-stratified benchmark with selection-bias correction; (5) a prompt-injection audit treating repository text as untrusted input; and (6) a checker-versus-helper ablation separating type checking from a generated conversion API. These are ranked in §6 and sequenced in §7.

## 1. Scope relative to existing future work and planned fixes

Future work (`docs/design_plan.md` §7) comprises eleven sketches: the adversarial fixer, Level 3 records, localization/SNR, continuous triage, test adaptation, adversarial test synthesis, the adversarial applier, test-suite annotation, Maven, multi-JDK, and `.astub` overlays. The 41-row action plan (`docs/critique_report.md` §9) adds baselines and lift (`D-1`), a clean-`C-1` baseline (`D-22`), the AST gate/canary/non-triviality halt (`I-1`–`I-3`, `I-8`), bug-context arms (`D-24`), helper fixtures (`D-25`), body preservation (`D-27`), repetitions and intervals (`D-29`), a green-suite baseline (`D-30`), triage metadata (`D-31`), an injection vector (`D-32`), burden proxies (`P-3`, `X-1`), the pass-on-`C` check (`X-2`), blindness/SNR (`D-2`), interface contracts, remaining vectors, a test-adaptation toggle (`D-4`), an execution sandbox (`D-23`), and a forward prevention test (`X-9`).

We exclude implementation of already-specified guarantees (AST gate, canary, purity validation, Maven, multi-JDK, `.astub`) and bare `D-27`/`D-32` vectors, which S1/S2 extend with measured contrasts; the missing control arms (`fixer_no_types`, `fixer_builtin_only`) and zero-new-errors baseline, which we extend rather than restate; definitional repairs; infrastructure ports or toggles whose question is already stated; and "try a stronger model," "collect more bugs," or "add more guardrails," which are budget decisions without a mechanism. These are dependencies, not contributions; measurement awaits instrumentation and an MVP.

## 2. Method: candidate generation and screening

Four workstreams yielded 41 candidates: causal inference, benchmark validity, type systems, and security/reproducibility/human factors. Sources were the pipeline's mechanics, the critique's threats, and a literature scan. Candidates were screened on novelty against the eleven §7 sketches and 41 action-plan rows, pipeline-specific mechanism with failures, falsifiability, post-MVP feasibility, and distinctness under merging.

Screening reduced 41 to 29, then to 23, and subsequently expanded to 26 with additions for whole-program type inference (T4), context-decoupled invariant synthesis (S5), and automated anti-leak conformance metrics (S6). Merges: pre-registration, variance, and hierarchical estimands into **C5**; behavioral testing and placebos into **B2**; dose-response and minimality into **C4**. Drops: extra guardrail vectors (already `I-4`), Maven and multi-JDK (§7.9–§7.10), model shopping (no mechanism), and an LLM-judge primary outcome (construct validity is under test). The screen is auditable but not exhaustive.

## 3. Conventions and how to read the proposals

Each carries **theme**, **horizon**, **effort**, **dependencies**, and an **RQ** marker where applicable. *Horizon*: **Immediate** (before the first headline result), **Near-term** (course timeline after the MVP), **Long-term** (follow-on). *Effort*: S (days), M (1–2 weeks), L (3–4 weeks), XL (separate project). *Priority*: P0 (gates a credible headline), P1 (high value, after gates), P2 (deferrable).

Design facts cite project documents; predictions and sample sizes are hypotheses. "Lift" is the paired typed-versus-control difference; "SNR" the share of checker errors in the buggy scope; "admissibility" a lattice passing static checks (compiles, non-trivial, rejects canaries) without seeing the regression test. All experiments pair by bug/commit, cluster by repository, and follow C5's analysis.

## 4. Proposals

### Theme 1: Causal identification and statistical inference

#### C1. Diagnostic-channel decomposition: which part of the "type error" guides repair?

*Horizon: Immediate · Effort: M · Depends on: stable Step 5 logging · RQ.*

**Observed gap / motivation.** Step 5 gives the fixer annotated code plus the Checker log (`docs/design_plan.md` §3 Step 5), conflating semantic content, localization, and pacing; `§7.3`/`D-2` measure where errors land but not what the fixer sees.

**Proposed intervention.** Four within-commit arms: (A) full diagnostics; (B) yoked-location diagnostics with the same count and cadence but errors from a donor commit; (C) pace-only messages ("k errors remain; rebuild"); (D) plain `javac`; fixer, guards, turns, and codebase are identical.

**Why it should improve validity.** A−C estimates semantic and locational content, B−C a believable but wrong signal, and C−D iteration alone; it fails if the fixer ignores diagnostics or all arms hit floor/ceiling.

**Evaluation plan.** 12 commits × 4 arms × 3 seeds = 144 runs; primary regression pass; secondary first edit in the buggy scope, turns to zero errors, guard rejections; paired mixed logistic model with commit intercept.

**Falsifiable prediction.** A−C ≥15 points and B−C ≤10; C ≈ A means the benefit is recompile pacing, while B ≈ A means only localization matters.

**Risks, costs, and failure modes.** Yoked diagnostics may look incoherent; kill if >30% of B/C runs abort as fake.

**Delta vs. existing work.** `§7.3` and `D-2` propose a passive SNR metric; this randomizes channel content and cadence to identify the causal channel.

#### C2. Checker × helper factorial: static checking or a generated conversion API?

*Horizon: Near-term · Effort: M · Depends on: helper inventory · RQ.*

**Observed gap / motivation.** `custom-quals.jar` contains qualifiers and pure coercion/validator helpers (`docs/design_plan.md` §3 Step 3), and the fixer may call them (`§3 Step 5`); baseline arms toggle the whole stack, so a win cannot separate checker-found invariants from an API that hands over the fix idiom.

**Proposed intervention.** 2×2 within commit: Checker injected versus plain `javac`; helpers visible versus hidden; with Checker on, annotations remain in both cells, and with Checker off, helpers remain callable.

**Why it should improve accuracy.** Errors say what and where, helpers say how; a checker main effect supports the type-guided claim, while a helper effect without a checker effect means API synthesis; it cannot discriminate if Step 3 rarely emits helpers.

**Evaluation plan.** 12 commits × 4 cells × 3 seeds = 144 runs; primary regression pass; secondary whether the first edit calls a helper, tokens, static resolution; 2×2 mixed model; interaction exploratory.

**Falsifiable prediction.** Checker and helper main effects each ≥10 points, and the checker effect survives with helpers hidden; helpers-only ≈ full with checker-only ≈ no-types means conversion-API synthesis.

**Risks, costs, and failure modes.** Hiding helpers can make cells structurally impossible; report those separately; gate on a median of at least one helper per commit.

**Delta vs. existing work.** The P0 baselines bundle checker and helpers; `step3.freedom_level` is a synthesis-time knob, not a repair-time factorial separation.

#### C3. Donor-lattice semantic placebo

*Horizon: Near-term · Effort: L · Depends on: multiple synthesized lattices · RQ.*

**Observed gap / motivation.** A real lattice may help by adding constraints or error volume rather than semantics; `fixer_builtin_only` is not a placebo because built-ins may model the same invariant, and no arm holds error volume roughly constant while removing domain semantics.

**Proposed intervention.** Three arms per bug: (A) target lattice; (B) a donor lattice from a different bug, matched on qualifier count, total error count (±20%), and file spread; (C) no types; both lattices come from the same pipeline and applier.

**Why it should improve validity.** A−B isolates semantic specificity from generic perturbation; it fails if the donor models the same invariant or matching is impossible.

**Evaluation plan.** 20 bugs × 3 arms × 3 seeds = 180 runs; primary regression pass; secondary error-location overlap with the buggy hunk, fixer edits, static resolution; paired model; report matching quality.

**Falsifiable prediction.** Target−donor ≥15 points and donor ≈ no-types; donor ≈ target means a generic error signal, not domain typing.

**Risks, costs, and failure modes.** Donor contamination and matching difficulty; kill if fewer than 12 matched triples survive.

**Delta vs. existing work.** The P0 no-types/builtin arms do not preserve diagnostic volume; `§7.7` varies placement, not lattice semantics.

#### C4. Annotation dose-response and minimal sufficient sets

*Horizon: Near-term · Effort: M · Depends on: annotation provenance · RQ.*

**Observed gap / motivation.** The project measures burden via annotation counts, and the critique plans only proxies (`P-3`, `X-1`); counts do not show which annotations are causally necessary or whether success saturates.

**Proposed intervention.** Per bug, create 25/50/75/100% coverage levels stratified by family (parameter, return, field, local, validator contract); separately, greedily delete families while preserving compilation, one checker error in the buggy scope, and the canary, recording the minimal sufficient set.

**Why it should improve accuracy and burden.** The curve estimates marginal value per annotation and separates load-bearing from decorative placements; saturation would make the pipeline cheaper.

**Evaluation plan.** 10 bugs × 4 coverage levels × 2 seeds, plus deletion on 10 bugs; primary fix success; secondary annotations/KLOC, false positives on clean `C-1`, error count, time; mixed model with a pre-registered saturation contrast.

**Falsifiable prediction.** Success saturates by 50–75% coverage, and a minimal set ≤50% of full coverage loses ≤5 points; a linear curve or a minimal set equal to the full set refutes saturation.

**Risks, costs, and failure modes.** Deleting annotations can make the checker trivial; enforce non-triviality and kill a level if it yields zero errors in >30% of samples.

**Delta vs. existing work.** `P-3`/`X-1` propose burden proxies such as annotations per KLOC; this measures causal marginal value and derives a minimal set.

#### C5. Pre-registered analysis, hierarchical estimands, and sequential futility

*Horizon: Immediate · Effort: S · Depends on: none, but must precede headline runs.*

**Observed gap / motivation.** The design exposes twelve knobs, 27 metrics (`docs/design_plan.md` §5–§6), four verdicts, and prompts edited while outcomes are visible; one run per cell is ground truth, and `fixer_incomplete` censors outcomes. The critique asks for lift (`D-1`) but not for a pre-data commitment, unit of analysis, or multiplicity control.

**Proposed intervention.** Before the first headline run, file a versioned pre-registration: primary outcome = per-commit success over *k* seeds; primary contrast = typed minus `fixer_no_types`; N, seeds, and exclusion list; a closed specification set over guardrail mode, test adaptation, censoring, aggregation, and model; sequential looks every five commits with alpha spending; and a futility rule. Report intention-to-treat and competing-risk versions.

**Why it should improve validity.** It constrains analyst degrees of freedom and separates treatment from seed noise; it will not rescue an unstable benchmark, only make the analysis honest.

**Evaluation plan.** 20 commits × 2 arms × 5 seeds plus the specification curve; paired permutation and a hierarchical logistic model with commit and repository random effects; intervals lead, and multiplicity is handled by the pre-registered hierarchy and alpha spending.

**Falsifiable prediction.** The typed-arm sign is consistent in ≥80% of specification cells and median lift is ≥10 points; sign reversal under a common specification refutes the headline.

**Risks, costs, and failure modes.** If the primary contrast is null and median lift <5 points, report a negative result rather than re-analyzing.

**Delta vs. existing work.** `D-1` makes lift primary and `D-29` adds repetitions and intervals, but neither fixes a pre-data commitment, estimator, censoring rule, or stopping rule.

### Theme 2: Benchmark construction and outcome validity

#### B1. Preventability-stratified benchmark with selection-bias correction

*Horizon: Near-term · Effort: L · Depends on: triage labels · RQ.*

**Observed gap / motivation.** Step 2 selects "pure bugfix" commits with a test that fails on `C-1` (`docs/design_plan.md` §3 Step 2) but never classifies whether the bug is statically preventable; the proposal's `§C.1` promise was dropped (`X-2`), so the rate describes a selected sample, not the addressable population.

**Proposed intervention.** Define a five-class taxonomy (identity conflation, unit/scale, sanitization/provenance, bounds/range, typestate) plus other/unpreventable; two annotators label all verified samples and a sample of discarded commits; fit a selection model for verification using triage features and report inverse-probability-weighted estimates alongside raw rates.

**Why it should improve validity.** Stratification tests whether the method works outside easy ID-confusion bugs and weighting corrects the denominator; it fails if labels are unreliable or the selection model is misspecified.

**Evaluation plan.** Target 60 verified plus 60 discarded commits; report Cohen's κ, per-stratum success, weighted addressable yield, and bootstrap intervals, with sensitivity over two selection-model specifications.

**Falsifiable prediction.** Success varies by ≥20 points across strata, and addressable yield is ≥10 points below the raw rate; κ <0.5 or homogeneous strata refute the need for stratification.

**Risks, costs, and failure modes.** Labeling cost and model dependence; kill the weighting if κ <0.4 and report unweighted strata only.

**Delta vs. existing work.** `D-3` defines a commit-type taxonomy, `P-9` triage agreement, and `X-9` framing; none builds a preventability taxonomy, weights the sample, or estimates the addressable population.

#### B2. Behavioral equivalence and outcome-specificity placebos

*Horizon: Immediate (instrumentation), Near-term (study) · Effort: L · Depends on: PR-adjacent tests and patches · RQ.*

**Observed gap / motivation.** Step 6 runs one regression test plus the existing suite (`docs/design_plan.md` §3 Step 6); a pass can come from a narrow special case, an over-restrictive validator, test-order luck, or a behavior-changing patch. The critique proposes only the pass-on-`C` check (`X-2`), which tests the lattice, not the patch.

**Proposed intervention.** Per sample: all tests added or modified in `C`; differential boundary probes against the human fix applied to `C-1`; a cross-test placebo (another bug's test must fail); a sham-invariant test reachable on `C-1` that must pass; and three reruns for flake.

**Why it should improve validity.** It separates a passing test from a defensible repair; it fails when multiple repairs are legitimate or the human fix is non-canonical.

**Evaluation plan.** 50 fixes stratified across success/failure; primary divergence rate among `full_success`; secondary test-versus-trace disagreement, placebo failures, rerun flake; exact intervals and a disagreement matrix.

**Falsifiable prediction.** ≥20% of test-passing fixes diverge on a PR-adjacent oracle, while ≤5% validates the cheaper Step 6 verdict; if a semantics-preserving reformulation of the human fix disagrees with the original >40% of the time, the oracle is too noisy and the study is demoted to characterization.

**Risks, costs, and failure modes.** Instrumentation and oracle noise; kill the equivalence claim if self-divergence is high, but keep the placebos.

**Delta vs. existing work.** `X-2` checks the lattice, `P-19` rubric-judges equivalence to the human fix, and `D-27` guards body preservation; B2 adds executable differential probes and cross-test/sham placebos.

#### B4. Contamination audit with temporal and private holdouts

*Horizon: Long-term · Effort: XL · Depends on: cutoff metadata and buildable post-cutoff commits · RQ.*

**Observed gap / motivation.** Step 2 ingests PR text and the full diff of `C` (`docs/design_plan.md` §2, §3 Step 2), and Step 3 sees the bug/PR description; for public repositories the human fix may be in pretraining, so `full_success` may measure recall plus localization rather than type-guided reasoning.

**Proposed intervention.** Four probes: a recall oracle asking for the patch from `C-1` plus the commit message, scored on overlap with `C`; a pre/post model-cutoff split; semantics-preserving obfuscation that preserves domain identifiers but renames others and strips comments; and eight private reimplementations in a non-public fork.

**Why it should improve validity.** A memorization gap (original minus obfuscated) and a temporal gap (pre minus post) bound how much success is recall; it fails if post-cutoff commits do not build or obfuscation destroys legitimate cues, which the domain-name rule and a types-ablated arm address.

**Evaluation plan.** 40 pre-cutoff original/obfuscated pairs, 20 post-cutoff, 8 private; McNemar for paired gaps; mixed model with repository intercept; primary memorization and temporal gaps.

**Falsifiable prediction.** Recall near-match ≥25% pre-cutoff and ≤5% post; memorization gap ≥10 points and temporal gap ≥15 points; gaps near zero are a publishable validity result that licenses a stronger causal reading.

**Risks, costs, and failure modes.** Post-cutoff build yield is the main cost; below 20 usable post/private samples, report a bounded recall audit and withdraw generalization claims.

**Delta vs. existing work.** `X-9` adds a prevention test, `P-9` selection, and `D-28` a post-cutoff memorization probe; B4 adds patch-overlap recall, obfuscation, and private reimplementations.

### Theme 3: Type-system expressiveness, soundness, and burden

#### T1. Checker-family ablation with applicability strata

*Horizon: Near-term · Effort: M · Depends on: clean-`C-1` baseline · RQ.*

**Observed gap / motivation.** Custom qualifiers are the least validated, yet may always be used: `step3.allowed_checkers` (`docs/design_plan.md` §5) is unwired (`I-5`) and `implementation_plan.md` §8 hardcodes the SubtypingChecker; if built-ins match the repair rate, the contribution is placement, not synthesis.

**Proposed intervention.** Three arms—built-in only, custom only, both—stratified by built-in analogue (nullness, bounds, format/injection, custom-only ID/unit); both-applicable gives the causal contrast, custom-only the capability claim.

**Why it should improve validity.** Only enforcement varies; it fails if built-ins emit pre-existing errors (score zero new errors against a clean baseline) or bugs are unmappable.

**Evaluation plan.** 60 bugs (~30 both-applicable), paired McNemar, 80% power for 15 points with ~20 discordant pairs; secondary: turns/errors/guard-rejections/interaction.

**Falsifiable prediction.** Custom advantage ≥25 points pooled, ≤10 both-applicable; ≥20 both-applicable shows synthesis load-bearing, none custom-only refutes it.

**Risks, costs, and failure modes.** Scoping built-ins without hiding legacy errors is delicate; kill if ≥50% of both-applicable bugs cannot reach baseline-zero.

**Delta vs. existing work.** `allowed_checkers` is unused (`I-5`), `D-7`/`X-10` flag `custom_only` as undefined, and `§7.2`'s Level 3 is wrapper records, not checker substitution.

#### T2. Expressiveness ladder: product lattices and flow-sensitive contracts

*Horizon: Long-term · Effort: L · Depends on: two lattice implementations · RQ.*

**Observed gap / motivation.** One hierarchy plus built-ins (`docs/design_plan.md` §3 Step 3) cannot express multi-dimensional bugs needing unit-plus-provenance qualifiers or conditional refinement; too little yields false negatives, too much false positives, and no precision–burden frontier is measured.

**Proposed intervention.** Four levels—built-in, monadic, two-dimensional product, product plus flow-sensitive `@EnsuresQualifierIf`—with identical placement agent, bugs, and fixer.

**Why it should improve accuracy.** Estimates each increment's marginal value and false-positive cost; fails if the benchmark is single-dimensional or defaults mask differences.

**Evaluation plan.** 12 bugs × 4 levels × 2 seeds; primary fix success, secondary clean-`C-1` false positives/annotations/KLOC/errors/time; pre-register product-versus-monadic on multi-dimensional bugs.

**Falsifiable prediction.** Product beats monadic by ≥12 points on multi-dimensional bugs, <5 single-dimensional, ≤5 more false positives; no gain or >20 more refutes it.

**Risks, costs, and failure modes.** Implementation-heavy; kill if the product lattice fails to compile or pass the pilot repository's canary.

**Delta vs. existing work.** `step3.freedom_level` and `§7.2` are a feature enum and wrapper records; neither measures the precision–burden trade-off.

#### T3. Opaque and misleading qualifier names

*Horizon: Near-term · Effort: M · Depends on: deterministic renaming and a human sub-study · RQ.*

**Observed gap / motivation.** The fixer sees `@CustomerId` and `@OrderId` and may match names rather than subtyping, inflating type structure's apparent contribution; `D-2` covers helper names but does not manipulate qualifier and identifier names as a fixer/reviewer factor.

**Proposed intervention.** Four conditions, same hierarchy and audited error counts: semantic names; opaque (`Q1`, `Q2`); opaque qualifier and variable names; misleading swapped names; diagnostics transformed consistently.

**Why it should improve validity.** Semantic-minus-opaque estimates naming value; misleading names test harm; fails if diagnostics/call sites leak meaning or renaming changes checker behavior.

**Evaluation plan.** 30 bugs × 3 conditions × 2 seeds; 20 participants × 12 comprehension items; primary fix success/wrong-domain fixes, secondary comprehension/confidence/NASA-TLX; kill if error counts differ >10%.

**Falsifiable prediction.** Semantic names beat opaque by ≥15 points; misleading names raise wrong-domain fixes by ≥10; <5-point gap means structure, not names, dominates.

**Risks, costs, and failure modes.** Renaming can leak via variables and Javadoc; opaque-variable condition controls this; recruitment costs, but fixer runs alone.

**Delta vs. existing work.** `D-2` scrubs helper names and `§7.3` concerns diagnostic locations; this manipulates qualifier and identifier names as an experimental factor.

#### T4. Whole-program type inference and automated default propagation

*Horizon: Near-term · Effort: M · Depends on: Checker Framework Gradle init script with `-Ainfer` support · RQ.*

**Observed gap / motivation.** In standard Checker Framework workflows, manual annotation placement is minimized by combining `@DefaultQualifierInHierarchy` with Whole-Program Inference (WPI; `-Ainfer=stubs` or `-Ainfer=jaifs`). Currently, Step 4 relies on an OpenCode agent to place annotations file-by-file across the repository, incurring high token costs, turn attrition, and inconsistent placement across deep callgraphs.

**Proposed intervention.** Synthesize boundary seed annotations (source and sink exceptions) alongside `@DefaultQualifierInHierarchy` in Step 3, then run Checker Framework's Whole-Program Inference toolchain in Step 4 to propagate qualifiers automatically to fixed point, generating `.astub` files or annotated sources deterministically without multi-turn LLM editing.

**Why it should improve validity.** Replaces stochastic LLM annotation placement with deterministic type inference; ensures 100% flow consistency across method boundaries; slashes token cost and eliminates AST-gate edit rejections.

**Evaluation plan.** 15 bugs evaluated across 2 arms: (A) Agentic placement (OpenCode Step 4); (B) WPI-propagated placement. Measure annotation coverage, token cost, duration, and downstream Step 5 repair success rate.

**Falsifiable prediction.** WPI achieves equal or higher downstream repair rates with $\ge 80\%$ lower token usage and 0 AST-gate rejections.

**Risks, costs, and failure modes.** WPI in Checker Framework can be slow or brittle on complex Gradle multi-module builds; fallback to agentic placement if inference times out.

**Delta vs. existing work.** Distinct from T1/T2 (which vary lattice expressiveness); this automates placement and propagation using compiler inference instead of LLM agent edits.

### Theme 4: Agent architecture and inference-time strategy

#### A1. Lattice portfolios with a static admissibility oracle

*Horizon: Near-term · Effort: L · Depends on: K lattices and a canary suite · RQ.*

**Observed gap / motivation.** Step 3 draws one lattice per bug (`docs/design_plan.md` §3 Step 3); synthesis is stochastic and one bad hierarchy dooms the fixer, while adversarial games (`§7.1`, `§7.6`) use runtime or test signals, not a blind static rule.

**Proposed intervention.** Generate K=5 lattices at one-fifth of the synthesis budget, score static admissibility (canary accept/reject, non-triviality, untouched-code false positives, bug-API coverage, placement-guide completeness), select the best, run the fixer at matched synthesis compute.

**Why it should improve accuracy.** Selection helps if quality varies and the score predicts repair; fails if failures correlate or the oracle does not.

**Evaluation plan.** 25 bugs, single-shot versus portfolio, paired; primary fix success, secondary oracle-score/fix AUC, cost, error profile.

**Falsifiable prediction.** Portfolio beats single-shot by ≥10 points at equal budget, oracle AUC ≥0.7; AUC near 0.5 or no gain refutes selection.

**Risks, costs, and failure modes.** Five synthesis loops per bug; kill if single-shot failure exceeds 60% (budget too thin) and raise the floor.

**Delta vs. existing work.** `§7.1`, `§7.6`, and `§7.7` are adversarial games or placement stress tests; this is best-of-K synthesis with a static blind oracle.

### Theme 5: Adversarial robustness and safe execution

#### S1. Repository-content prompt injection

*Horizon: Immediate · Effort: M · Depends on: a context manifest · RQ.*

**Observed gap / motivation.** Javadoc, comments, test names, and strings enter synthesis, placement, and fixer contexts without provenance; the active defense matrix (`docs/design_plan.md` §4) is output-side and `D-23` isolates the host, so "do not validate this input" could yield a legal patch passing every guard.

**Proposed intervention.** A manifest labels spans trusted/untrusted. Six payloads—direct instruction, fake compiler diagnostic/guide section, test-name hint, Unicode confusable, spoofed tool output—test D0 none/D1 provenance/D2 minimization/D3 output guards/D4 combined, plus benign hints.

**Why it should improve validity.** Measures semantic attack success, not syntactic escape; fails if payloads are unreachable or ignored; benign controls separate caution from capability loss.

**Evaluation plan.** 12 commits × 6 payloads × 5 arms = 360 runs; primary attack success, secondary clean-success loss/canary leakage/refusal; mixed logistic model, commit intercept.

**Falsifiable prediction.** D0 attack success ≥20%; D3 cuts syntactic not semantic success; D4 <5% with ≤5 points clean loss; <5% at N=120 or >15 points clean loss kills the manipulation.

**Risks, costs, and failure modes.** Dual-use payloads and benchmark contamination; keep payloads private and publish signatures, not exploits.

**Delta vs. existing work.** `D-32` names the channel and adds a canary and prompt logging; S1 adds a span manifest, six payload classes, and a D0–D4 defense dose-response with benign controls.

#### S2. Guardrail displacement and audited escape hatches

*Horizon: Near-term · Effort: L · Depends on: a strong outcome oracle (B2) · RQ.*

**Observed gap / motivation.** Strict guards block `@SuppressWarnings`, casts, signature drift (`docs/design_plan.md` §4), yet casts, suppressions, and over-restrictive validators still pass; `fixer.guard_rejections` counts blocks, not what follows, so whether guards improve faithfulness or displace cheating is unclear.

**Proposed intervention.** Compare disabled/lenient/strict modes plus an audited escape hatch—a budgeted suppression requiring machine-checkable justification, reported separately. Classify outcomes (faithful, narrow, no-op, test-targeted, regression) with B2's oracle, logging the first edit after each rejection.

**Why it should improve validity.** Tests behavioral effect, not block counts; fails if the oracle cannot be built or the agent gives up under strict mode.

**Evaluation plan.** 30–40 commits × 3 guard modes, patches scored under weak/strong oracles; primary no-op-or-narrow share among apparent successes, difference-in-differences, multinomial mixed model.

**Falsifiable prediction.** Strict mode cuts syntactic attempts ≥50% but raises no-op-or-narrow share ≥10 points; the hatch reduces displacement without more successful hacks; no displacement refutes the concern.

**Risks, costs, and failure modes.** The hatch may legitimize cheating; cap it, kill if abuse exceeds 20%; the oracle is the main dependency.

**Delta vs. existing work.** `§4`/`§6` measure blocked forms, `D-27` guards body replacement, and `§7.1` uses an adversary; none measures post-block displacement or an escape-hatch policy.

#### S3. Property-based certificates for generated validators

*Horizon: Near-term · Effort: L · Depends on: validator contracts · RQ.*

**Observed gap / motivation.** Step 3 emits pure validators and coercions (`docs/design_plan.md` §3 Step 3); purity checks and one canary (`I-3`, `I-13`) miss over-permissive regexes, non-total parsers, inconsistent coercion, or disagreeing validators, so a green checker can enforce a false invariant.

**Proposed intervention.** Require a machine-readable validator contract: totality, determinism, round-trip, idempotence, disjointness, metamorphic behavior (case, whitespace, encoding), resource bounds, differential behavior against a specification. Generate QuickCheck-style tests, shrink counterexamples, gate Step 5 on a certificate.

**Why it should improve accuracy.** Validator bugs silently make the lattice unsound; certificates make assumptions testable; fails if specifications are ambiguous or generators weak.

**Evaluation plan.** 60 validators at four certification levels (existing, generic, domain-aware, full); defect rate/property, false rejection, downstream success, certificate AUROC.

**Falsifiable prediction.** ≥25% fail a property; domain-aware checks catch ≥80%; downstream success improves ≥10 points; AUROC ≥0.7; <10% defects or no downstream relation refutes certification.

**Risks, costs, and failure modes.** False rejection can block valid helpers; kill if >50% are uncertifiable or false rejection exceeds 10%.

**Delta vs. existing work.** `D-25` requires per-helper fixtures and a property test; S3 adds a contract suite (totality, round-trip, idempotence, disjointness), shrinking, and cross-validator consistency.

#### S4. Build-integrity attacks that corrupt the verdict

*Horizon: Long-term · Effort: L · Depends on: a sandbox and differential builds.*

**Observed gap / motivation.** `D-23` protects the host, but `build.gradle`, `settings.gradle`, or `buildSrc` can set `ignoreFailures`, replace the test task, exclude the buggy file, override the processor path, or detect the harness; Step 2 trusts the build, so a host-safe run can produce false success.

**Proposed intervention.** A 12-attack corpus plus 10 benign controls; compare S0 generic sandbox, S1 provenance/allowlist/offline, S2 differential verdict (repository/sanitized/harness-owned build), and S3 combined plus canaries.

**Why it should improve validity.** Ensures pass/fail reflects the code under test, not the build script; fails if legitimate customization is common, producing false quarantine.

**Evaluation plan.** 12 attacks × 4 arms + 10 benign × 4 arms = 88 builds; primary detection/false-success, secondary false quarantine, overhead, coverage; exact McNemar.

**Falsifiable prediction.** S0 detects ≤3/12, S2 ≥10/12, S3 12/12 with ≤1/10 false quarantine and ≤2× overhead; if S1 catches all, S2/S3 are unnecessary.

**Risks, costs, and failure modes.** Legitimate-build false positives; kill if false quarantine exceeds 20% or overhead exceeds 2×.

**Delta vs. existing work.** `D-23` specifies host isolation and `§4` guards agent edits to build arguments; neither protects the verdict from a malicious repository build.

#### S5. Context-decoupled abstract invariant synthesis

*Horizon: Near-term · Effort: M · Depends on: benchmark bug taxonomy and prompt decoupling · RQ.*

**Observed gap / motivation.** Step 3 receives the full repository source tree at $C-1$, the commit $C$ diff, and PR description. Even with prompt instructions forbidding concrete symbol names, the synthesizer may leak bug localization by tailoring type names or guide rules to the specific failing class or method.

**Proposed intervention.** Strip target repository code and commit diffs from Step 3 entirely. Provide Step 3 with only an abstract, repository-agnostic description of the failure mode / domain invariant (e.g., *"A method passed an unvalidated tenant identifier to a query expecting an authorized session tenant ID"*). Step 3 synthesizes `custom-quals.jar` and an abstract declarative `placement_guide.md`. Step 4 then receives the target repository and grounds the abstract policy onto concrete code.

**Why it should improve validity.** Architecturally guarantees zero bug-location or symbol leakage from Step 3; forces the type lattice to model a general domain discipline rather than an overfitted single-site repair; cleanly separates invariant architecture from code grounding.

**Evaluation plan.** 15 bugs evaluated across 2 arms: (A) Full-context Step 3 synthesis; (B) Decoupled abstract Step 3 synthesis. Measure guide abstraction, symbol leakage, and downstream repair success.

**Falsifiable prediction.** Decoupled synthesis achieves 100% abstract guides with zero repository symbol leakage, while retaining $\ge 75\%$ of downstream repair efficacy.

**Risks, costs, and failure modes.** Abstract failure summaries may be too vague to synthesize actionable qualifiers; requires a well-defined taxonomy of domain bug invariants.

**Delta vs. existing work.** Extends the P0 blindness controls (D-2) to the synthesis stage itself, establishing an information-theoretic barrier between bug diffs and placement guidance.

#### S6. Automated anti-leak AST scanner and policy conformance metrics

*Horizon: Near-term · Effort: M · Depends on: JavaParser AST symbol extractor · RQ.*

**Observed gap / motivation.** Metric P3 was descoped to `policy_rule_count` to keep the MVP simple. However, verifying that placement guides contain no subtle identifier leakage and verifying that Step 4 placed types faithfully to the policy currently requires manual inspection.

**Proposed intervention.** Implement an automated evaluation harness that: (1) extracts declared symbols from $C-1$ and diff hunks from $C$ to compute an automated `abstraction_score` and `symbol_leakage_penalty`; and (2) queries AST syntactic triggers (e.g. methods with `return null;` or null checks) to compute an $F_1$ `global_conformance` score for Step 4 placement.

**Why it should improve validity.** Provides quantitative, machine-checkable auditability for placement guide abstraction and application fidelity without requiring human review.

**Evaluation plan.** Benchmark 20 synthesis runs with seeded leaking guides; measure detector precision/recall and correlation between `global_conformance` and fixer success.

**Falsifiable prediction.** Leaking guides inflate repair rates by $\ge 20$ percentage points; `global_conformance` explains more variance in repair success than raw annotation count.

**Risks, costs, and failure modes.** Complex AST query heuristics may misclassify valid domain type uses; maintain an allowlist for ubiquitous standard library identifiers.

**Delta vs. existing work.** Elevates the descoped P3 metrics (`abstraction_score`, `global_conformance`) into a formal automated verification harness.

### Theme 6: Reproducibility, model drift, and cost

#### R1. Model-drift and provenance envelope

*Horizon: Long-term · Effort: L · Depends on: model fingerprinting and a frozen container.*

**Observed gap / motivation.** Config stores model aliases as strings (`implementation_plan.md` §9); snapshots record no prompt hash, checker version, or container digest, and `D-28`'s model revision is not a drift measurement, so provider updates confound arms over time.

**Proposed intervention.** Freeze container digest, Checker Framework version, prompt hashes, and a behavioral model fingerprint (provider revision if exposed, else a fixed probe suite); rerun a 15-commit benchmark at weeks 0, 4, and 12, with a stage-hold varying only the fixer.

**Why it should improve validity.** It separates pipeline gains from provider drift and attributes variance to stages; fails if revisions are unavailable and fingerprints unstable.

**Evaluation plan.** 15 commits × 3 time points × 2 arms; primary: verdict-flip rate and score drift; secondary: token/latency drift, fingerprint distance, stage attribution.

**Falsifiable prediction.** ≥10% verdict flips over three months, fixer-stage drift exceeding synthesis-stage drift; <2% flips with stable fingerprints means a robust benchmark.

**Risks, costs, and failure modes.** Repeated runs cost time and money; absent revisions, report the fingerprint, mark drift unexplained.

**Delta vs. existing work.** `D-28` adds model ID and revision to `config_snapshot`, but nothing measures drift, fingerprints behavior, or reruns longitudinally.

#### R2. Equal-budget cost Pareto

*Horizon: Near-term · Effort: M · Depends on: a per-turn budget ledger.*

**Observed gap / motivation.** The 8 synthesis-turn and 15 fixer-turn defaults are unjustified (`docs/design_plan.md` §5; `D-11`); token metrics (`§6`) are descriptive, so a "better" arm may spend more.

**Proposed intervention.** Fix a total per-bug budget in tokens, agent steps, and checker runs; compare default, synthesis-heavy, fixer-heavy, and adaptive marginal-return allocations, ledgering turns; free models priced by wall-clock.

**Why it should improve accuracy per cost.** Shows where extra compute buys repair, reveals dominated configurations; fails if budget slack or Gradle latency, not tokens, binds; match the binding currency.

**Evaluation plan.** 30 commits × 4 arms; primary: success at budget, cost per verified fix; secondary: marginal return per 10k tokens, overrun, Pareto frontier.

**Falsifiable prediction.** Fixer-heavy beats synthesis-heavy by ≥10 points at low budget; default dominated; adaptive controller on the frontier; a flat frontier refutes the question.

**Risks, costs, and failure modes.** Accounting errors invalidate the comparison; kill if realized spend differs by >15% across arms.

**Delta vs. existing work.** `§6` logs tokens, `D-11` would justify turn budgets, and `I-32` adds caps; none matches total budget, estimates a cost frontier, or tests adaptive allocation.

### Theme 7: External validity and generalization

#### E1. Leave-one-repository-out clustered generalization

*Horizon: Long-term · Effort: L · Depends on: at least five repositories with usable yield · RQ.*

**Observed gap / motivation.** Commits are sampled but pooled as independent; the README names one synthetic repository and `cmsx` (`README.md` §4), so one easy repository can carry the headline and narrow pooled intervals.

**Proposed intervention.** Freeze prompts, budgets, and checker family on one development repository; evaluate ≥5 repositories across three pre-registered domains (web/CRUD, data/ETL, tooling, scientific/library); model repository as a random effect, reporting leave-one-repository-out estimates and a new-repository prediction interval.

**Why it should improve validity.** It separates method efficacy from codebase fit; fails if repositories are homogeneous or held-out ones fail Step 2 for selection reasons.

**Evaluation plan.** About 50 samples over five clusters, 8–12 each; primary: per-repository success and ICC; secondary: cluster-bootstrap intervals, domain-stratified rates, yield.

**Falsifiable prediction.** ICC ≥0.15, best-to-worst ratio ≥2×, pooled intervals ≥1.5× too narrow versus cluster-robust; ICC <0.05 makes pooled estimates defensible.

**Risks, costs, and failure modes.** Yield; below four repositories with ≥5 verified samples, report descriptively per repository, restrict the claim.

**Delta vs. existing work.** `X-8` asks for three targets or a caveat, `P-9` covers selection, and `D-29` asks to stratify by repository; none supplies a clustered estimator, held-out protocol, or prediction interval.

#### E2. Cross-language nominal-brand replication

*Horizon: Long-term · Effort: XL · Depends on: TypeScript ports and guards against brand forgery · RQ.*

**Observed gap / motivation.** Load-bearing artifacts are Java-specific—qualifier jar, `@SubtypeOf`, Gradle injection, JavaParser, Checker diagnostics—so results read as Checker Framework engineering, not domain-type evidence; no item substitutes enforcement.

**Proposed intervention.** Re-implement 10 benchmark bugs in TypeScript: no types; nominal brands (`string & {readonly __brand: unique symbol}` plus constructors); brands plus boundary refinement (zod-style); block `as`-forging with lint/AST guards like Java's active defense vectors.

**Why it should improve validity.** Brand-only success gauges whether nominal distinction suffices; refinement-only whether flow-sensitive checking is load-bearing; fails if porting changes the bug or structural typing allows forgery.

**Evaluation plan.** 10 bugs × 3 arms, within-bug; primary: fix success; secondary: annotation lines, fixer turns, forged-brand incidents; exact intervals; per-bug Java concordance.

**Falsifiable prediction.** Brand-only success ≥50% of Java-successful bugs implies the pluggable checker is not load-bearing; ≤20% implies refinement is; <6 ports reproducing fail-on-buggy/pass-on-fixed abandon the comparison.

**Risks, costs, and failure modes.** Construct drift, porting cost; kill if <6 ports preserve the invariant.

**Delta vs. existing work.** `§7.2` adds Java wrapper records and `§7.9`/`§7.10` are within-Java infrastructure; this substitutes the enforcement technology, testing nominal versus refinement typing.

### Theme 8: Human factors

#### H1. Trust calibration with planted wrong fixes

*Horizon: Long-term · Effort: L · Depends on: stable patches and participant recruitment · RQ.*

**Observed gap / motivation.** Developer burden is the proposal's second goal (`proposal/Project Proposal.md` §B), but its proxy—annotations per KLOC (`P-3`, `X-1`)—misses appropriate reliance: a green verdict can make developers accept semantic no-ops or reject correct patches.

**Proposed intervention.** Present vignettes with buggy method, annotations, diagnostics, patch, and evidence panel; patches genuine, semantic no-op, over-restrictive, or incomplete; evidence verdict only, plus diagnostics, plus certificate, plus uncertainty; 4×4 within-participant.

**Why it should improve adoption.** It measures wrong-patch acceptance, correct rejection, calibration (Brier), discrimination; fails if patches are trivially detectable.

**Evaluation plan.** 32 participants × 16 items = 512 judgments; primary: wrong-patch acceptance and correct rejection; secondary: confidence gap, d′, NASA-TLX; mixed logistic regression with participant intercepts.

**Falsifiable prediction.** Verdict-only acceptance of wrong patches ≥60%; the uncertainty panel lowers it to ≤35% with ≤5 points more correct rejection; no evidence effect refutes inspectability.

**Risks, costs, and failure modes.** Recruitment and ecological validity; kill if <16 participants or baseline accuracy >90% or <30%.

**Delta vs. existing work.** `P-3`/`X-1` propose burden proxies or a descope; neither measures trust calibration or appropriate reliance on synthesized verdicts.

#### H2. Reviewability of placement rationales and maintenance churn

*Horizon: Near-term · Effort: M · Depends on: rationale artifacts and a review study.*

**Observed gap / motivation.** `placement_guide.md` is a prose handoff (`docs/design_plan.md` §3 Step 3); nothing asks whether humans can audit placement, though adoption turns on review cost and annotation survival.

**Proposed intervention.** Emit a machine-checkable rationale per placement: source cue (parameter name, Javadoc, call site), confidence, rejected alternatives; run a review study with rationale on/off and seeded placement errors; replay 10 commits for churn and false-positive maintenance.

**Why it should improve adoption.** Rationales shorten review, expose bad placements; churn measures long-term burden; fails if noisy or verbose, or churn negligible.

**Evaluation plan.** 24 participants × 12 patches; primary: review time and seeded-error detection; secondary: NASA-TLX, trust, churn per commit, false-positive rate.

**Falsifiable prediction.** Rationales cut review time ≥20% and raise seeded-error detection ≥15 points; churn ≤10% per commit; no time or detection effect or >30% churn refutes the benefit.

**Risks, costs, and failure modes.** Rationale generation may be unreliable and costly; kill if baseline detection <50% or churn >30%.

**Delta vs. existing work.** `P-3` proposes a small review-time study; H2 adds rationale on/off, seeded placement errors, and annotation-churn replay.

### Theme 9: Falsification and negative results

#### N1. Negative-result registry and prediction calibration

*Horizon: Immediate · Effort: S · Depends on: pre-registration (C5).*

**Observed gap / motivation.** Runs are stored, but analysis and publication select successes and register no negative predictions; the team often judges logic, UI, or concurrency bugs unpreventable by typing yet never tests that calibration, so a null result can be reframed as an implementation problem.

**Proposed intervention.** Before running, register ten "expected failure" bugs (unpreventable by domain typing) and ten "expected success" bugs with one-sentence rationales; publish a ledger of abandoned configurations and failed syntheses; an adversarial collaborator reviews claims pre-analysis.

**Why it should improve validity.** It tests preventability judgment and exposes file-drawer bias; fails if predictions are trivial or the ledger incomplete.

**Evaluation plan.** 20 predicted bugs; calibration slope and intercept, Brier score, success by predicted class; compare with the pipeline's claimed rate and B1's weighted estimate.

**Falsifiable prediction.** Expected-failure success ≤10%, expected-success ≥50%, calibration slope 0.5–1.5; similar success across classes means uninformative predictions; >30% on expected failures means poor judgment.

**Risks, costs, and failure modes.** Reputational discomfort and cherry-picked predictions; freeze predictions before runs and publish the ledger regardless; no kill criterion—miscalibration is informative.

**Delta vs. existing work.** C5 pre-registers analysis choices; no item pre-registers substantive failure predictions, publishes abandoned runs, or measures preventability calibration.

## 5. Consolidated table

| ID | Theme | Primary outcome | Evidence type | Effort | Horizon | Priority |
| :-- | :--- | :--- | :--- | :-- | :-- | :-- |
| C1 | Causal | Success by diagnostic channel | Randomized experiment | M | Immediate | P0 |
| C2 | Causal | Checker vs helper main effects | 2×2 experiment | M | Near | P1 |
| C3 | Causal | Target vs donor lattice | Matched placebo | L | Near | P1 |
| C4 | Causal | Dose-response and minimal set | Dose-response experiment | M | Near | P1 |
| C5 | Causal | Pre-registered lift and specification curve | Analysis governance | S | Immediate | P0 |
| B1 | Benchmark | Stratified weighted success | Sampling study | L | Near | P0 |
| B2 | Benchmark | Divergence of passing fixes | Differential testing | L | Immediate | P0 |
| B4 | Benchmark | Memorization and temporal gaps | Contamination audit | XL | Long | P1 |
| T1 | Type | Custom vs built-in checker effect | Stratified ablation | M | Near | P1 |
| T2 | Type | Precision–burden frontier | Expressiveness ladder | L | Long | P2 |
| T3 | Type | Name vs structure effect | Name ablation + human study | M | Near | P1 |
| T4 | Type | Whole-program inference vs agent | WPI ablation | M | Near | P1 |
| A1 | Agent | Portfolio vs single-shot | Best-of-K experiment | L | Near | P1 |
| S1 | Adversarial | Prompt-injection success rate | Red-team experiment | M | Immediate | P0 |
| S2 | Adversarial | No-op/narrow displacement | Guard-mode experiment | L | Near | P1 |
| S3 | Adversarial | Validator defect and downstream effect | Property-based audit | L | Near | P1 |
| S4 | Adversarial | Verdict corruption detection | Build-integrity audit | L | Long | P2 |
| S5 | Adversarial | Context-decoupled invariant synthesis | Information-barrier experiment | M | Near | P1 |
| S6 | Adversarial | Automated anti-leak & conformance | Static audit & trigger query | M | Near | P1 |
| R1 | Repro | Verdict flips under drift | Longitudinal replication | L | Long | P2 |
| R2 | Repro | Success per unit cost | Budget-matched experiment | M | Near | P1 |
| E1 | External | Between-repository variance | Clustered replication | L | Long | P2 |
| E2 | External | Nominal vs refinement effect | Cross-language replication | XL | Long | P2 |
| H1 | Human | Wrong-patch acceptance | Controlled human study | L | Long | P2 |
| H2 | Human | Review time and churn | Human study + replay | M | Near | P2 |
| N1 | Negative | Calibration of failure predictions | Registered prediction audit | S | Immediate | P1 |

## 6. Prioritization matrix

Scores (1–5): **S** scientific value, **V** validity gain, **C** cost favorability (5 = cheap), **D** dependency readiness (5 = runnable post-MVP). Composite = `0.35S + 0.35V + 0.15C + 0.15D`. P0 = the five gates (C5, C1, B1, B2, S1); otherwise P1 ≥3.5 and P2 <3.5.

| ID | S | V | C | D | Composite | Priority |
| :-- | :-: | :-: | :-: | :-: | --: | :-- |
| C1 | 5 | 5 | 3 | 3 | 4.40 | P0 |
| C2 | 5 | 4 | 3 | 4 | 4.20 | P1 |
| C3 | 5 | 4 | 3 | 3 | 4.05 | P1 |
| C4 | 4 | 3 | 4 | 4 | 3.65 | P1 |
| C5 | 5 | 5 | 4 | 5 | 4.85 | P0 |
| B1 | 5 | 5 | 3 | 3 | 4.40 | P0 |
| B2 | 4 | 5 | 3 | 4 | 4.20 | P0 |
| B4 | 5 | 4 | 2 | 2 | 3.75 | P1 |
| T1 | 4 | 3 | 3 | 4 | 3.50 | P1 |
| T2 | 4 | 3 | 2 | 2 | 3.05 | P2 |
| T3 | 4 | 4 | 4 | 4 | 4.00 | P1 |
| T4 | 4 | 4 | 3 | 4 | 3.85 | P1 |
| A1 | 4 | 3 | 3 | 4 | 3.50 | P1 |
| S1 | 4 | 4 | 4 | 4 | 4.00 | P0 |
| S2 | 4 | 4 | 3 | 3 | 3.70 | P1 |
| S3 | 3 | 4 | 3 | 4 | 3.50 | P1 |
| S4 | 4 | 4 | 2 | 2 | 3.40 | P2 |
| S5 | 5 | 5 | 4 | 4 | 4.70 | P1 |
| S6 | 4 | 4 | 3 | 4 | 3.85 | P1 |
| R1 | 4 | 3 | 2 | 2 | 3.05 | P2 |
| R2 | 3 | 4 | 4 | 4 | 3.65 | P1 |
| E1 | 4 | 4 | 2 | 2 | 3.40 | P2 |
| E2 | 4 | 3 | 1 | 2 | 2.90 | P2 |
| H1 | 4 | 2 | 2 | 2 | 2.70 | P2 |
| H2 | 3 | 3 | 3 | 3 | 3.00 | P2 |
| N1 | 3 | 3 | 5 | 5 | 3.60 | P1 |

C2/C3 score highly yet are P1: they refine rather than gate the causal claim. B4 is P1 despite a modest composite because contamination invalidates every number; R1 is P2 because drift threatens replication, not the result.

## 7. Recommended sequence

**Immediate (before the first headline run).** C5 fixes the estimand and freezes the analysis; C1 and B2 instrumentation follow, determining whether the outcome means anything. S1 runs before untrusted repositories are built; N1's predictions freeze simultaneously. Cheap beside a full benchmark, they avert a well-executed study of the wrong quantity.

**Near-term (course timeline, after the MVP).** C2/C3 separate checker semantics, helper APIs, and perturbation; B1 fixes the success rate's population; T1/T3/C4 sharpen the type claim; A1 reuses the harness; S2/S3 guard against semantic cheating and validator bugs; R2 yields the cost frontier; H2 runs once rationales exist. If short, C2, C3, B1, T1, and R2 are the minimum.

**Long-term (follow-on).** B4 and E1 are expensive and should follow a stable pipeline. T2 tests whether expressive lattices justify their burden. S4 protects the verdict once external repositories enter. R1 and E2 are separate-paper projects. H1 should use B2/S2-validated artifacts.

Dependencies are directional: C5 → all analyses; B2 → S2; B1 → E1; C1/C2/C3 → the causal claim; T1 → T2; S1/S4 → external-repository work. Running E1 before B1, or H1 before B2, estimates the wrong construct precisely.

## 8. What not to do

**Do not add more guardrail vectors before measuring displacement.** The active defense matrix is broad: eleven vectors are unimplemented (`I-4`), and an additional vector ranks below S2, which asks whether checks change behavior or redirect it.

**Do not build Level 3 wrapper records or cross-language ports to rescue a weak causal result.** Both are costly and change the construct: absent a semantic effect in C1–C3, they yield a second, confounded system.

**Do not use an LLM judge as the primary outcome.** Runtime behavior is the claim; a judge adds correlated error, sharing the cues under study. Use B2's oracles; human adjudication is secondary.

**Do not run a large multi-repository benchmark before B1.** More bugs do not fix a selected sample: 60 bugs with a 20-point effect still leave a wide interval, and a biased sample is precisely wrong.

**Do not tune prompts or placement heuristics on the evaluation repositories.** The README targets `synthetic-id-swap` and `cmsx`; iterating turns the headline into training performance. Freeze prompts before B4/E1, or hold out a repository.

**Do not treat "0 Checker errors" or a single regression-test pass as success.** Both are intermediate: zero new errors are necessary but insufficient, since a checker-clean, test-passing patch can still be a semantic no-op; hence B2 and S2.

**Do not perform per-bug significance tests or unregistered subgroup analyses.** With 20–60 bugs, per-bug tests are underpowered and invite cherry-picking: estimate paired effects, report intervals, and pre-register subgroups in C5.

**Do not ban all escape hatches without an audited alternative.** A total ban leaves only a narrower or dead-code patch; S2's audited hatch is a measurement device, not a concession.

## 9. Limitations of this document

This is a design-stage agenda, not a systematic review: the 41-candidate pool spans four workstreams. Novelty claims hold for the current documents. Effort estimates are planning targets, not measurements. Several depend on nonexistent capabilities (stable harness, multi-repository yield, model fingerprints, human subjects); dependencies are stated per proposal. The ranking is a judgment; §6 makes it auditable.

## References

1. M. M. Papi, M. Ali, T. L. Correa Jr., J. H. Perkins, and M. D. Ernst. "Practical Pluggable Types for Java." *ISSTA*, 2008. DOI: [10.1145/1390630.1390656](https://doi.org/10.1145/1390630.1390656).
2. W. Dietl, S. Dietzel, M. D. Ernst, K. Muşlu, and T. Schiller. "Building and Using Pluggable Type-Checkers." *ICSE*, 2011. DOI: [10.1145/1985793.1985889](https://doi.org/10.1145/1985793.1985889).
3. Checker Framework Project. *Checker Framework Manual*. https://checkerframework.org/manual/ (accessed 19 October 2026).
4. J. S. Foster, M. Fähndrich, and A. Aiken. "A Theory of Type Qualifiers." *PLDI*, 1999. DOI: [10.1145/301618.301665](https://doi.org/10.1145/301618.301665).
5. R. Just, D. Jalali, and M. D. Ernst. "Defects4J: A Database of Existing Faults to Enable Controlled Testing Studies for Java Programs." *ISSTA*, 2014. DOI: [10.1145/2610384.2628055](https://doi.org/10.1145/2610384.2628055).
6. E. K. Smith, E. T. Barr, C. Le Goues, and Y. Brun. "Is the Cure Worse than the Disease? Overfitting in Automated Program Repair." *FSE*, 2015. DOI: [10.1145/2786805.2786825](https://doi.org/10.1145/2786805.2786825).
7. L. Gazzola, D. Micucci, and L. Mariani. "A Critical Review of Automated Program Repair Techniques." *IEEE TSE* 45(11), 2019. DOI: [10.1109/TSE.2018.2868723](https://doi.org/10.1109/TSE.2018.2868723).
8. R. Just, D. Jalali, L. Inozemtseva, M. D. Ernst, R. Holmes, and G. Fraser. "Are Mutants a Valid Substitute for Real Faults in Software Testing?" *FSE*, 2014. DOI: [10.1145/2635868.2635929](https://doi.org/10.1145/2635868.2635929).
9. E. T. Barr, M. Harman, P. McMinn, M. Shahbaz, and S. Yoo. "The Oracle Problem in Software Testing: A Survey." *IEEE TSE* 41(5), 2015. DOI: [10.1109/TSE.2014.2372785](https://doi.org/10.1109/TSE.2014.2372785).
10. B. A. Nosek, C. R. Ebersole, A. C. DeHaven, and D. T. Mellor. "The Preregistration Revolution." *PNAS* 115(11), 2018. DOI: [10.1073/pnas.1708274114](https://doi.org/10.1073/pnas.1708274114).
11. R. Silberzahn et al. "Many Analysts, One Data Set: Making Transparent How Variations in Analytic Choices Affect Results." *Advances in Methods and Practices in Psychological Science* 1(3), 2018. DOI: [10.1177/2515245917747646](https://doi.org/10.1177/2515245917747646).
12. S. Steegen, F. Tuerlinckx, A. Gelman, and W. Vanpaemel. "Increasing Transparency Through a Multiverse Analysis." *Perspectives on Psychological Science* 11(5), 2016. DOI: [10.1177/1745691616658637](https://doi.org/10.1177/1745691616658637).
13. A. Arcuri and L. Briand. "A Practical Guide for Using Statistical Tests to Assess Randomized Algorithms in Software Engineering." *ICSE*, 2011. DOI: [10.1145/1985793.1985795](https://doi.org/10.1145/1985793.1985795).
14. K. Greshake, S. Abdelnabi, S. Mishra, C. Endres, T. Holz, and M. Fritz. "Not What You've Signed Up For: Compromising Real-World LLM-Integrated Applications with Indirect Prompt Injection." arXiv:2302.12173, 2023. https://arxiv.org/abs/2302.12173.
15. F. Perez and I. Ribeiro. "Ignore Previous Prompt: Attack Techniques for Language Models." arXiv:2211.09527, 2022. https://arxiv.org/abs/2211.09527.
16. M. Ohm, H. Plate, A. Sykosch, and M. Meier. "Backstabber's Knife Collection: A Review of Open Source Software Supply Chain Attacks." *DIMVA*, 2020. arXiv:2005.09535. https://arxiv.org/abs/2005.09535.
17. S. Biderman et al. "Lessons from the Trenches on Reproducible Evaluation of Language Models." arXiv:2405.14782, 2024. https://arxiv.org/abs/2405.14782.
18. N. Jain et al. "LiveCodeBench: Holistic and Contamination-Free Evaluation of Large Language Models for Code." *ICLR*, 2025. arXiv:2403.07974. https://arxiv.org/abs/2403.07974.
19. S. Golchin and M. Surdeanu. "Time Travel in LLMs: Tracing Data Contamination in Large Language Models." *ICLR*, 2024. arXiv:2308.08493. https://arxiv.org/abs/2308.08493.
20. J. D. Lee and K. A. See. "Trust in Automation: Designing for Appropriate Reliance." *Human Factors* 46(1), 2004. DOI: [10.1518/hfes.46.1.50_30392](https://doi.org/10.1518/hfes.46.1.50_30392).
