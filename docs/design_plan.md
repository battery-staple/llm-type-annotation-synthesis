# Pipeline Design Plan: LLM Type Annotation Synthesis & Blind Verification

## 1. Executive Summary & Core Hypothesis

### Core Hypothesis
Can domain-specific static type qualifiers (via the Java Checker Framework) be automatically synthesized by an LLM to prevent real-world bugs, and can those types guide a downstream "fixer" agent—which has never seen the bug or pull request description—to fix the latent bug purely by resolving static type errors?

### High-Level Workflow
1. **Triage & Reproducibility**: Given a Git repository $R$ and commit depth $d$, identify bugfix commits $C$, extract their regression tests, and deterministically verify that the tests compile and fail at runtime on commit $C-1$.
2. **Type Lattice & Validator Synthesis**: Given commit $C-1$ and the bug context, an LLM agent synthesizes a custom Checker Framework qualifier hierarchy (or selects a built-in checker) along with pure validation/coercion methods, packaging them into `custom-quals.jar` alongside a placement guide (`placement_guide.md`).
3. **Semantic Type Placement**: An agent applies the synthesized type annotations across the $C-1$ codebase following semantic cues (parameter names, callgraphs, variable roles) without modifying executable logic, creating active static type errors on the unpatched code.
4. **Blind Repair**: A blind fixer agent—strictly isolated from commit $C$, PR text, and regression tests—is tasked with resolving all Checker Framework errors on $C-1$ without modifying method signatures or adding suppressions.
5. **Ground-Truth Evaluation**: The unpatched regression test from commit $C$ is executed against the fixer's modified codebase to evaluate whether resolving the static type errors cured the actual runtime bug.

---

## 2. Pipeline Execution Paradigms & Stage Contracts

| Step | Component | Paradigm | Harness / Tooling | Formal Inputs | Formal Outputs (Artifacts & State) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Step 1** | Workspace & Cache Ingestion | **Deterministic** | Git Worktrees, Shared Caches | Target repo URL/path $R$, Commit $C$, Depth $d$ | Clean ephemeral worktree at commit $C-1$, pre-warmed Gradle cache |
| **Step 2** | Commit Triage & Test Extraction | **Simple LLM** (1 call / commit) + **Deterministic Verification** | Direct LLM API + Gradle Runner | Commit $C$ Git diff, commit message, PR metadata, clean $C-1$ worktree | Triage classification (`bugfix` vs `non_bugfix`), regression test name $T_{\text{reg}}$, test unified patch (`test_patch.diff`), verification verdict on $C-1$ |
| **Step 3** | Type Lattice & Guard Synthesis | **Agent** (Iterative `javac` loop) | OpenCode (`opencode run`) + `javac` | Unpatched $C-1$ code, bug context (diff of $C$, commit msg, PR body), stage config | `custom-quals.jar` (qualifiers & contracts), `placement_guide.md`, qualifier source files (`src/`), synthesis agent transcript |
| **Step 4** | Semantic Type Application | **Agent** (Multi-turn edit loop) + **Deterministic AST Validator** | OpenCode (`opencode run`) + JavaParser (`AstGuard`) | Clean $C-1$ code, `custom-quals.jar`, `placement_guide.md`, AST guardrails | Unified Git patch (`annotations.patch` relative to $C-1$), AST audit log, initial Checker compiler log (`initial_checker_errors.log`), non-triviality error count $\ge 1$ |
| **Step 5** | Blind Bug Fixing | **Agent** (Iterative compile loop) | OpenCode (`opencode run`) + Gradle | Annotated $C-1$ code ($C-1$ base + `annotations.patch`), initial Checker log, `custom-quals.jar`, anti-cheat config. *(Blind to $C$, PR text, and test)* | Repaired codebase unified Git patch (`repair.patch` relative to $C-1$), anti-cheat validation log, final Checker log (verifying 0 errors), repair agent transcript |
| **Step 6** | Ground-Truth Verification | **Deterministic** | Gradle Test Runner | Repaired $C-1$ code ($C-1$ base + `repair.patch`), extracted regression test patch (`test_patch.diff` from Step 2) | Test execution report, original unit test suite report, final evaluation verdict (`full_success`, `type_success_runtime_fail`, `regression_introduced`, `fixer_incomplete`), resource metrics |

```mermaid
flowchart TD
    subgraph S1["Phase 1: Ingestion & Triage"]
        R["Target Repo R"] --> C1["Extract Commits [C - d ... C]"]
        C1 --> LLM_Triage["Step 2: Simple LLM Commit Classifier"]
        LLM_Triage -->|Filter Bugfixes| BF["Bugfix Commits"]
        BF --> T_Extract["Extract Regression Test from C"]
        T_Extract --> T_Verify{"Verify Test on C-1: Fails?"}
        T_Verify -->|Passes or Flaky| Discard["Discard Commit"]
        T_Verify -->|Fails to Compile| NonCompile["Discard (Track in Metrics)"]
        T_Verify -->|Fails at Runtime| Valid["Benchmark Sample (C, C-1, Test)"]
    end

    subgraph S2["Phase 2: Type Lattice Synthesis"]
        Valid --> S3_Agent["Step 3: OpenCode Synthesis Agent (Iterative javac loop)"]
        S3_Agent --> Lattice["Pre-compiled custom-quals.jar + placement_guide.md"]
    end

    subgraph S3["Phase 3: Type Application"]
        Lattice --> S4_Agent["Step 4: OpenCode Type Applier Agent (Semantic placement)"]
        S4_Agent --> AST_Gate{"JavaParser AST Gate: Annotations Only?"}
        AST_Gate -->|Executable changes| Reject_S4["Reject / Prompt Error Feedback"]
        AST_Gate -->|Valid| CF_Check{"Checker Framework Diagnostic Run"}
        CF_Check -->|0 Errors: Trivial Lattice| Fail_Trivial["Fail: Record Ineffective Types"]
        CF_Check -->|> 0 Errors| Annotated_Repo["Annotated Repo with Active Type Errors"]
    end

    subgraph S4["Phase 4: Blind Bug Fixing"]
        Annotated_Repo --> S5_Agent["Step 5: Blind OpenCode Fixer Agent (No PR Context)"]
        S5_Agent --> Fixer_Guards{"Configurable Anti-Cheat Guards"}
        Fixer_Guards --> CF_Pass{"Checker Framework: 0 Errors?"}
        CF_Pass --> Reg_Test{"Existing C-1 Unit Tests Pass?"}
    end

    subgraph S5["Phase 5: Ground-Truth Verification"]
        Reg_Test --> S6_Eval["Step 6: Run Regression Test from C"]
        S6_Eval --> Eval_Result{"Regression Test Result"}
        Eval_Result -->|Pass| Success["VERIFIED BUGFIX VIA TYPES"]
        Eval_Result -->|Fail| Fail_Fix["Failed to Fix Runtime Bug"]
    end
```

### Cross-Cutting Execution Architecture: Stage Caching & Lineage (Merkle CAS + SQLite)
Rather than executing the pipeline as a rigid, monolithic script, the orchestrator treats each of the 6 steps as an isolated, content-addressable node in a **Merkle Directed Acyclic Graph (DAG)** backed by **Content-Addressable Storage (CAS)** on disk and a **Relational Index** in SQLite (`data/pipeline.db`):

1. **Content-Addressable Stage Invalidation & Seamless Reverts**:
   - Cache keys are derived deterministically:
     $$K_{\text{stage}} = \text{SHA-256}(\text{stage\_name} + H(\text{upstream\_artifacts}) + H(\text{config\_slice}) + H(\text{prompt\_template}) + H(\text{stage\_code}) + \text{sample\_index})$$
   - Modifying a single stage (e.g., editing Step 5 Fixer prompts or code) generates a new key $K_5'$, leaving cached outputs for Steps 1–4 intact.
   - Reverting edits to Step 5 restores the exact previous key $K_5$, yielding an instant cache hit from before the edit without losing intervening experiment branches.
2. **Workspace State Transfer via Unified Git Patches**:
   - Stages that modify target code (Step 4 Application, Step 5 Repair) serialize their modifications as unified Git diffs (`.patch`) relative to immutable base commit $C-1$.
   - Downstream stages reconstruct the exact workspace state by applying the patch onto a freshly checked-out $C-1$ worktree (`git apply`), eliminating duplicated multi-hundred-megabyte directory snapshots.
3. **Multi-Sample Stochastic Retries**:
   - To study nondeterministic LLM behavior under identical configurations, the pipeline supports an integer `sample_index` parameter (`sample_index: 0, 1, 2, ...`). Running `--sample 1` records an independent sample while preserving all previous sample history in CAS.
4. **Two-Tier Failure Policy**:
   - **Deterministic Outcomes Cached**: Step 2 triage discards (non-bugfix, non-compiling tests) and Step 6 test verdicts are cached permanently in CAS and SQLite to prevent redundant re-runs.
   - **Transient Agent Failures Retried**: Agent timeouts, network glitches, or API crashes are logged to SQLite for telemetry, but leave CAS empty so subsequent invocations automatically re-attempt the failed stage.
5. **Configurable Append-Only Storage**:
   - Artifact bundles reside in a configurable directory (`project_config.yaml`: `cache.stages_dir`, defaulting to `.cache/stages/`), gitignored, and retained append-only to support long experiment histories.

---

## 3. Step-by-Step Specification

### Step 1: Workspace Ingestion & Build Cache Pre-Warming
- **Execution Paradigm**: Deterministic Runner.
- **Formal Inputs**:
  - `repo_url` / `local_path`: Target repository location.
  - `commit_hash` ($C$): Target historical bugfix commit.
  - `commit_depth` ($d$): Depth scan parameter.
- **Formal Outputs (Artifacts & State)**:
  - Ephemeral Git worktree checked out at clean parent commit $C-1$.
  - Pre-warmed Gradle cache (`~/.gradle/caches` containing project dependencies and wrapper distributions).
  - Recorded ingestion metadata in SQLite.
- **Isolation via Git Worktrees**:
  Each commit evaluation runs in an ephemeral `git worktree` branched off a local mirror clone. This provides instantaneous workspace setup and teardown without network cloning or disk bloat.
- **Cache Sharing**:
  A centralized Gradle user home (`~/.gradle/caches` and `~/.gradle/wrapper`) is mounted read-write or with shared lock semantics across runs. When a repository is first registered, a pre-warm pass (`./gradlew build -x test`) downloads all project dependencies and Gradle wrapper distributions once.
- **Build Incompatibilities**:
  If a historical commit fails to compile due to an incompatible JDK version, outdated plugins, or broken dependencies, the commit is skipped and recorded in the database metrics.

### Step 2: Commit Triage & Ground-Truth Test Verification
- **Execution Paradigm**: Simple LLM (single-shot call per commit) + Deterministic Verification.
- **Formal Inputs**:
  - Git diff of commit $C$ (`git diff C~1..C`).
  - Commit metadata: Commit message of $C$, PR title, PR description.
  - Target repository worktree at clean commit $C-1$.
  - Stage configuration: `model.triage`, prompt template (`triage.md`).
- **Formal Outputs (Artifacts & State)**:
  - `triage_status`: Classification result (`verified`, `non_bugfix`, `discarded_no_test`, `discarded_non_compiling`, `discarded_passes_unpatched`, `discarded_broken_base`).
  - `regression_test_name`: Fully qualified test method and class identifier ($T_{\text{reg}}$).
  - `test_patch.diff`: Extracted test patch containing the regression test from $C$.
  - `triage_metadata`: Token usage, LLM classification score, rationale, execution log on $C-1$.
- **Deterministic Verification Invariant ($T_{\text{reg}}$ must fail on $C-1$)**:
  1. Extract the test patch from $C$ (`src/test/**`).
  2. Apply the test patch onto clean commit $C-1$.
  3. Run the regression test via Gradle:
     - **Compilation Failure**: Discard commit and record `discard_reason: test_compilation_failure` in metrics.
     - **Passes on $C-1$**: Discard commit and record `discard_reason: test_passes_on_unpatched`.
     - **Compiles & Fails at Runtime**: Verified ground-truth benchmark sample. Proceed to Step 3.

### Step 3: Type Lattice Synthesis & Implementer Freedom
- **Execution Paradigm**: Agent (Multi-turn iterative loop using OpenCode).
- **Formal Inputs**:
  - Target repository source tree at clean commit $C-1$.
  - Bug context: Diff of commit $C$, commit message, PR title and description.
  - Stage configuration: `step3.freedom_level`, `step3.allowed_checkers`, `model.synthesis`, turn budget, prompt template (`synthesis.md`).
  - Stochastic sample seed: `sample_index`.
- **Formal Outputs (Artifacts & State)**:
  - `custom-quals.jar`: Compiled JAR packaging synthesized qualifier annotations and contract interfaces.
  - `placement_guide.md`: Detailed markdown specifications describing where and how annotations should be applied.
  - `custom_quals_src/`: Raw Java source files for qualifiers and contracts.
  - `synthesis_transcript.json`: Full OpenCode agent multi-turn interaction log.
  - `synthesis_metrics.json`: Compiler iteration count, turn count, duration, token usage.
- **Levels of Freedom for Implementer**:
  - *Level 0 (Monomorphic Marker Lattice)*: Declarative subtyping hierarchies (`@SubtypeOf`) via built-in `SubtypingChecker`, built-in checkers (Nullness, Index, Regex, FormatString), and local variable defaulting via `@DefaultQualifier`. Zero executable helper code in `custom-quals.jar`.
  - *Level 1 (Flow-Sensitive Contracts on Existing Code)*: Level 0 plus method preconditions (`@RequiresQualifier`) and conditional/unconditional postconditions (`@EnsuresQualifierIf`, `@EnsuresQualifier`) targeting existing methods, parameters, and fields in the target codebase. Zero synthesized helper code.
  - *Level 2 (Dependent & Value-Parameterized Qualifiers)*: Level 1 plus value/expression-dependent qualifiers (`@Range(from, to)`, `@Pattern(regex)`, `@MinLen(n)`, `@KeyFor(map)`). Zero synthesized helper code.
  - **Selected Policy**: **Level 2 (Default)** with support for Level 0 and Level 1 ablations. For all core levels (0–2), **zero executable helper code is synthesized** (`synthesis.helpers_count == 0`), ensuring `custom-quals.jar` contains strictly type qualifier annotations. This prevents fix leakage by ensuring the synthesizer cannot inject bug-specific repair logic into helper methods. Higher tiers involving helper code synthesis (Levels 4–5) and nominal record refactoring (Level 6) are deferred to Section 7 (Future Developments).

### Step 4: Semantic Type Application
- **Execution Paradigm**: **Agentic Type Applier (OpenCode Agent)** with Deterministic AST Validation.
- **Formal Inputs**:
  - Clean target codebase at commit $C-1$.
  - Upstream Step 3 artifacts: `custom-quals.jar` and `placement_guide.md`.
  - Stage configuration: `model.application`, turn budget, AST guardrails, prompt template (`application.md`).
  - Stochastic sample seed: `sample_index`.
- **Formal Outputs (Artifacts & State)**:
  - `annotations.patch`: Unified Git diff containing all applied type annotations relative to clean commit $C-1$.
  - `ast_audit.json`: JavaParser AST verification report confirming zero executable statements/signatures modified.
  - `initial_checker_errors.log`: Diagnostic compiler output under the Checker Framework on $C-1$.
  - `checker_error_count`: Integer count of active type errors ($\ge 1$ required; 0 halts as trivial).
  - `application_transcript.json`: OpenCode agent interaction log and token consumption.
- **Enforcing Non-Executable Changes (Deterministic JavaParser AST Gate)**:
  Although an agent writes the changes, its patch is deterministically validated before acceptance using JavaParser (`AstGuard`):
  $$\text{strip\_annotations}(AST_{\text{original}}) == \text{strip\_annotations}(AST_{\text{modified}})$$
  JavaParser's `ModifierVisitor` strips type annotations from both ASTs, normalizes whitespace/formatting, and asserts structural AST equivalence. If the agent modified even a single executable statement, branch, literal, or method/class signature, the edit is rejected and feedback is provided to retry.
- **Non-Triviality Check**:
  - The codebase must compile under standard `javac` and pass existing $C-1$ unit tests.
  - The codebase must trigger $\ge 1$ Checker Framework error. If 0 errors are emitted, the types are ineffective (halt and record failure).

### Step 5: Blind Bug Fixing
- **Execution Paradigm**: Agent (Multi-turn repair loop using OpenCode).
- **Formal Inputs**:
  - Annotated codebase at $C-1$ (reconstructed by applying `annotations.patch` onto clean $C-1$).
  - Upstream Step 4 diagnostics: `initial_checker_errors.log`.
  - Upstream Step 3 artifact: `custom-quals.jar`.
  - Stage configuration: `guardrails.mode`, `model.fixer`, turn budget, checker invocation limit, prompt template (`fixer.md`).
  - Stochastic sample seed: `sample_index`.
  - **Strict Information Isolation**: Fixer NEVER receives commit $C$, PR text, bug descriptions, or the regression test code.
- **Formal Outputs (Artifacts & State)**:
  - `repair.patch`: Unified Git diff containing repaired method bodies relative to clean commit $C-1$.
  - `final_checker_errors.log`: Diagnostic compiler output confirming 0 Checker Framework errors remain.
  - `anti_cheat_audit.json`: JavaParser report verifying no suppressions, signature alterations, or backdoors.
  - `fixer_transcript.json`: OpenCode agent interaction log.
  - `fixer_metrics.json`: Turn count, compiler invocations, repair latency, token usage.
- **Permitted Actions & Anti-Cheat**:
  - The fixer may edit statements, expressions, and control flow inside method bodies to satisfy type invariants.
  - Method signatures remain strictly locked.
  - Test files (`src/test/**`) are immutable and read-only.
  - Suppressions (`@SuppressWarnings`, `@AssumeAssertion`, utility casts) are blocked by JavaParser AST guardrails.

### Step 6: Ground-Truth Verification
- **Execution Paradigm**: Deterministic Runner.
- **Formal Inputs**:
  - Repaired codebase at $C-1$ (reconstructed by applying `repair.patch` onto clean $C-1$).
  - Extracted regression test patch from Step 2: `test_patch.diff`.
  - Original target test suite at $C-1$.
- **Formal Outputs (Artifacts & State)**:
  - `test_results.xml` / log: JUnit test report for regression test $T_{\text{reg}}$.
  - `suite_results.xml` / log: JUnit test report for pre-existing unit test suite on $C-1$.
  - `eval_verdict`: Primary outcome classification:
    1. `full_success`: Regression test passes AND all original unit tests pass AND Checker Framework reports 0 errors.
    2. `type_success_runtime_fail`: Checker Framework reports 0 errors, but regression test fails (types did not model the true runtime invariant).
    3. `regression_introduced`: Checker Framework passed, but existing unit tests broke.
    4. `fixer_incomplete`: Fixer failed to eliminate all type errors within turn/invocation budget.
  - `verification_summary.json`: End-to-end evaluation report, token totals, and wall-clock timings.

---

## 4. Threat Model & Configurable Anti-Cheat Guardrails

### Configurable Guardrail Modes
To support empirical evaluation of whether strict guardrails are necessary versus prompt steering alone, the pipeline exposes `guardrails.mode`:

1. **Mode A: `"disabled"` (Cheating Allowed / Prompt Steering Only)**:
   No AST guardrails or compiler restriction flags are enforced. The agent is only asked in the prompt not to cheat (no suppressions, no casting bypasses). This allows measuring how frequently LLMs naturally attempt to cheat when not constrained.
2. **Mode B: `"lenient"` (Low Impact on Legacy Code)**:
   Enforces checks that prevent explicit suppression without breaking legacy patterns:
   - Blocks `@SuppressWarnings` and `@AssumeAssertion`.
   - Blocks `NullnessUtil.castNonNull` and known escape hatches.
   - Enforces method signature locks.
   - Does *not* enforce strict raw-type or array covariance bans, avoiding false-positive build failures on legacy Java projects.
3. **Mode C: `"strict"` (Full 19-Vector Hardened Defense)**:
   Enforces all AST checks and strict compiler flags.

### The 19-Vector Defense Matrix

| Attack Vector | Mechanism | Detection Layer | Action in "Strict" Mode | Action in "Lenient" Mode |
| :--- | :--- | :--- | :--- | :--- |
| **`@SuppressWarnings`** | Slapping `@SuppressWarnings("subtyping")` on code. | JavaParser AST | Rejection | Rejection |
| **`@AssumeAssertion`** | Writing `assert expr : "@AssumeAssertion(key)"`. | JavaParser AST | Rejection | Rejection |
| **Utility Casts** | Calling `NullnessUtil.castNonNull(...)`. | JavaParser AST | Rejection | Rejection |
| **Raw Types** | Omitting type arguments (`List raw = ...`). | Compiler Flag + AST | `-AignoreRawTypeArguments=false` | Ignored |
| **Double / Unsafe Casts** | Writing `(T) (Object) val` or `(@Qual T) val`. | Compiler Flag + AST | Ban nested casts; `-Alint=cast:unsafe` | Prompt warning only |
| **Covariant Array Aliasing** | Aliasing qualified arrays into `Object[]`. | Compiler Flag | Pass `-AinvariantArrays` | Ignored |
| **Unverified Purity** | Slapping `@Pure` on mutating methods. | Compiler Flag + AST | Pass `-AcheckPurityAnnotations` | Signature guard blocks |
| **`@Covariant` Annotation** | Declaring `@Covariant` on type parameters. | JavaParser AST | Rejection | Rejection |
| **Initialization Escapes** | Slapping `@Initialized` on local variables. | JavaParser AST | Ban initialization qualifiers | Ban initialization qualifiers |
| **Contract Injections** | Adding `@RequiresQualifier` to method headers. | JavaParser AST | Signature guard blocks | Signature guard blocks |
| **Scoped Defaulting** | Using `@DefaultQualifier(Bottom.class)`. | JavaParser AST | Rejection | Rejection |
| **Explicit `@Bottom`** | Casting or typing local variables as `@Bottom`. | JavaParser AST | Rejection | Rejection |
| **Stub File Injection** | Adding `.astub` files in Step 5 diff. | Filesystem Guard | Rejection | Rejection |
| **Command-Line Bypasses** | Adding `-AskipDefs` to build args. | Build Guard | Read-only build files | Read-only build files |
| **The `-Awarns` Mask** | Enabling `-Awarns` so errors don't fail build. | Pipeline Harness | Errors parsed strictly | Errors parsed strictly |
| **Trivial Custom Checkers** | Step 3 writing a dummy `isSubtype` returning `true`. | Canary Test | Canary test required | Canary test required |
| **Dead-Code Pruning** | Inserting `if (true) return;` before buggy code. | Dynamic Tests + AST | AST detects dead-code exits | Dynamic tests catch |
| **Reflection / Unsafe** | Using `Method.invoke` or `Field.set`. | JavaParser AST | Rejection | Rejection |
| **Signature Drift** | Changing method parameter or return types. | JavaParser AST | Signature guard blocks | Signature guard blocks |

---

## 5. Configurable Pipeline Parameters (Experimentation Matrix)

| Parameter Key | Possible Values | Default | Research Question / Experimental Purpose |
| :--- | :--- | :--- | :--- |
| `guardrails.mode` | `"disabled"`, `"lenient"`, `"strict"` | `"lenient"` | Do strict guardrails improve fix quality or can prompt steering suffice? |
| `model.default` | `string` | `"nvidia/nemotron-3.5-super-vl-free"` | Default LLM model across all stages. |
| `model.triage` | `string` | `model.default` | Model used for Step 2 commit classification. |
| `model.synthesis` | `string` | `model.default` | Model used for Step 3 type lattice synthesis. |
| `model.application` | `string` | `model.default` | Model used for Step 4 semantic type application. |
| `model.fixer` | `string` | `model.default` | Model used for Step 5 blind bug fixing. |
| `agent.steps.synthesis` | `int` | `25` | Turn/step budget for Step 3 synthesis loop (increased from 8 to allow multi-turn javac convergence). |
| `agent.steps.application` | `int` | `20` | Turn/step budget for Step 4 semantic type application loop. |
| `agent.steps.fixer` | `int` | `50` | Turn/step budget for Step 5 repair loop (increased from 15 to allow multi-file diagnostic resolution). |
| `agent.timeout_ms` | `int` | `1800000` | Per-stage wall-clock timeout (30 minutes) preventing runaway agent hangs. |
| `agent.max_checker_invocations` | `int` | `20` | Maximum Checker Framework compiler invocations allowed within Step 5 repair loop. |
| `prompt.tell_non_triviality_error`| `bool` | `false` | Does informing the LLM that it must trigger an error bias it toward bad types? |
| `step5.local_variable_annotations`| `"frozen"`, `"allowed"` | `"frozen"` | Does allowing local variable annotation modifications help or permit evasion? |
| `step5.internet_access` | `bool` | `false` | Does internet access enable information leakage vs helping with docs? |
| `step3.allowed_checkers` | `"all"`, `"custom_only"`, `"builtin_only"` | `"all"` | Proportion of time built-in checkers suffice vs custom qualifiers. |
| `step3.freedom_level` | `"level0"`, `"level1"`, `"level2"` | `"level2"` | Type system expressiveness level for Step 3 (pure lattice, contracts, and dependent types; zero helper code). |

---

## 6. Comprehensive Metrics Registry

All metrics are stored in a relational SQLite database with structured JSON payload columns:

### Granular Triage & Benchmark Metrics (Step 2)
- `triage.total_commits_evaluated`: Total commits scanned in depth $d$.
- `triage.classification_counts`: Breakdown of classifications (`bugfix` vs `non_bugfix`).
- `triage.regression_tests_found_count`: Commits where the LLM identified $\ge 1$ regression test.
- `triage.test_methods_extracted_count`: Total individual test methods extracted across all commits.
- `triage.discard_reasons`: Detailed breakdown of discarded commits:
  - `discard.no_test_found`: PR had no new tests.
  - `discard.test_compilation_failure`: Regression test failed to compile on $C-1$ (with compiler error log).
  - `discard.test_passes_unpatched`: Regression test passes on clean $C-1$ (flaky or non-discriminative).
  - `discard.build_broken_unpatched`: Commit $C-1$ failed baseline compilation (broken dependencies / JDK).
- `triage.yield_rate`: $\frac{\text{Verified Benchmarks}}{\text{Total Commits Scanned}}$.
- `triage.token_usage`: Prompt, completion, and total tokens used during triage.
- `triage.avg_latency_ms`: Mean time taken per triage call.
- `triage.commit_metadata`: JSON array containing hashes, author dates, PR numbers, titles, and URLs.

### Synthesis Metrics (Step 3)
- `synthesis.turns_taken`: Agent turns taken to produce valid qualifiers.
- `synthesis.compiler_feedback_rounds`: Number of `javac` compilation errors corrected.
- `synthesis.checker_category`: `custom_qualifier_hierarchy` vs `builtin_checker`.
- `synthesis.qualifiers_count`: Number of qualifiers defined in the lattice.
- `synthesis.helpers_count`: Number of pure validator/coercion functions generated.

### Annotation Application Metrics (Step 4)
- `application.turns_taken`: Agent turns taken to place annotations.
- `application.ast_guard_rejections`: Number of times the agent attempted executable edits and was rejected.
- `application.annotations_added_count`: Total annotations placed across the codebase.
- `application.total_checker_errors`: Raw count of Checker Framework diagnostics triggered on $C-1$.
- `application.is_trivial`: Boolean flag (true if error count is 0).

### Fixer Agent Metrics (Step 5)
- `fixer.turns_taken`: Total agent turns taken to resolve errors.
- `fixer.guard_rejections`: Breakdown of blocked attempts (`suppressions`, `signatures`, `casts`, `backdoors`).
- `fixer.remaining_type_errors`: Final Checker Framework error count (0 = static resolution).
- `fixer.existing_unit_tests_passed`: Boolean flag indicating no regressions on $C-1$.

### Overall Evaluation Metrics (Phase 5)
- `eval.regression_test_passed`: Boolean: Did the ground-truth regression test pass?
- `eval.verdict`: `full_success`, `type_success_runtime_fail`, `regression_introduced`, `fixer_incomplete`.
- `eval.token_usage`: Prompt, completion, and total tokens per stage and model.

---

## 7. Future Developments (Actionable Sketches)

### 1. Adversarial Bug-Preserving Fixer (The Soundness Falsification Game)
- *Concept*: Replace or augment Step 5 with an adversarial agent tasked with: **"Resolve all static type errors, but ensure that the original buggy behavior is PRESERVED."**
- *Research Purpose*: A truly robust, sound type lattice for a bug should make the buggy state *untypeable*. If the adversarial agent succeeds in making the code compile with 0 type errors while the regression test still fails at runtime, then the synthesized type lattice is proved to be incomplete or leaky!
- *Implementation Sketch*:
  - Dispatch an agent with instructions to satisfy Checker Framework while intentionally preserving the bug.
  - Requires all strict anti-cheat guards enabled.
  - Creates a formal **Type Lattice Verification Game** ($\text{Synthesizer} \leftrightarrow \text{Adversarial Fixer}$).

### 2. Step 3 Extended Expressiveness & Code-Gen Tiers (Levels 3–6)
- *Concept*: Extend Step 3 beyond the core zero-helper specification (Levels 0–2) by systematically exploring higher-order qualifier polymorphism, external library boundary specifications, constrained guard templates, general pure helpers, and nominal refactoring.
- *Levels Specification*:
  - **Level 3 (Qualifier Polymorphism & Library Boundary Stubs)**:
    - *Mechanism*: Adds inter-procedural `@PolyQualifier` across method signatures and generic types, accompanied by external `.astub` stub files for third-party library and JDK boundaries.
    - *Research Purpose*: Evaluates whether modeling external library boundaries and generic collections eliminates false positives on unannotated code without generating runtime helpers.
  - **Level 4 (Certified Guard Templates)**:
    - *Mechanism*: Step 3 may synthesize runtime validation methods, but *strictly* by instantiating pre-certified declarative templates (Regex matchers, Range bounds, Enum membership checks).
    - *Research Purpose*: Allows branch-refining runtime guards while eliminating arbitrary algorithmic code leakage. Formally verified via `PurityChecker` (`@Pure`, `@SideEffectFree`).
  - **Level 5 (Algorithmic Helpers & Coercions)**:
    - *Mechanism*: Step 3 may generate arbitrary pure static methods and coercions (e.g., `CustomerId.of(rawString)`).
    - *Research Purpose & Verification*: Measures the marginal benefit of generated helper APIs versus pure static type guidance. Requires `PurityChecker` verification and automated non-vacuity property test fixtures (positive and negative test cases to prove non-vacuous rejection).
  - **Level 6 (Domain Nominal Refactoring & Records)**:
    - *Mechanism*: Step 3 generates distinct Java records/classes (`public record CustomerId(String value)`).
    - *Research Purpose*: Eliminates primitive obsession via standard Java type safety. Uses a specialized refactoring agent (or OpenRewrite recipe) to migrate method signatures across the codebase.

### 3. Error Localization to Buggy Scope & Signal-to-Noise Ratio (SNR)
- *Motivation*: If synthesized types trigger errors in irrelevant classes, the fixer may waste time fixing non-buggy code.
- *Implementation Sketch*:
  - Map git diff hunks of commit $C$ to AST enclosing method scopes.
  - Implement Tiered Localization: Tier 1 (diff hunk $\pm 3$ lines), Tier 2 (enclosing method AST scope), Tier 3 (modified class scope), Tier 4 (unrelated file noise).
  - Compute $\text{SNR} = \frac{\text{Errors in Buggy Method}}{\text{Total Errors Emitted}}$.

### 4. Continuous Commit Triage Scoring (0.0 to 1.0)
- *Motivation*: Binary "bugfix vs non-bugfix" loses nuance on commits that fix bugs while refactoring slightly.
- *Implementation Sketch*:
  - Prompt the model for a continuous score from `0.0` (pure bugfix) to `1.0` (pure feature addition).
  - Expose a configurable threshold parameter `triage.bugfix_threshold` (e.g., accept commits with score $\le 0.25$).
  - *Future Metrics Tracked*: `triage.continuous_scores` (array of confidence scores for all commits) and `triage.score_distribution`.

### 5. LLM-Assisted Regression Test Adaptation
- *Motivation*: In Step 2, regression tests from $C$ often call new helper methods or constructor overloads introduced in $C$, causing compilation failures on $C-1$.
- *Implementation Sketch*:
  - An adaptation LLM receives the test code from $C$, compiler errors on $C-1$, and the diff of $C$.
  - It rewrites the test using existing $C-1$ APIs or inlines helper logic while preserving the exact input-output assertions.
  - Verification: Adapted test must compile on $C-1$, fail at runtime on $C-1$, and pass on $C$.

### 6. Adversarial Test Synthesis & Falsification Loop
- *Motivation*: Real-world bugfix PRs may lack thorough regression tests.
- *Implementation Sketch*:
  - An adversarial LLM tries to generate a unit test that satisfies the synthesized types but triggers a runtime failure on $C-1$.
  - If it succeeds, the Step 3 agent must refine the type lattice to reject the buggy state. Loop runs for up to $K$ rounds.

### 7. Adversarial Type Applier (Worst-Case Stress Testing)
- *Motivation*: A cooperative type applier might pick annotations that make the fixer's task artificially trivial.
- *Implementation Options*:
  - *Option A (Prompt-based)*: Instruct the agent to assign the most restrictive valid qualifiers.
  - *Option B (AST-based)*: A deterministic pass replaces unannotated parameters with the most specific subtype in the hierarchy.
  - *Option C (Mutation Testing)*: Randomly perturb qualifier placements to identify brittle type specifications.

### 8. Test Suite Annotation vs Stripping in Evaluation
- *Motivation*: If regression tests interact with annotated methods, standard compilation might flag type mismatches in the test itself.
- *Implementation Sketch*:
  - Run a specialized test-annotator agent on the regression test, ensuring the test compiles cleanly under Checker Framework before executing.

### 9. Non-Intrusive Maven Build Support
- *Motivation*: Expand beyond Gradle to support Maven repositories.
- *Implementation Sketch*:
  - Implement compiler wrapper script (`-Dmaven.compiler.fork=true -Dmaven.compiler.executable=<script>`) to intercept `javac` and inject Checker Framework processors across multi-module Maven POMs without editing `pom.xml`.

### 10. Dynamic Multi-JDK Container Management
- *Motivation*: Open-source repos change JDK baselines over time (e.g., JDK 8 to 11 to 17).
- *Implementation Sketch*:
  - Pre-install JDKs 8, 11, 17, and 21 in the execution container.
  - Auto-detect project bytecode target and dynamically configure `JAVA_HOME` per commit.

### 11. Checker Framework Stub File (.astub) Overlay & Injection
- *Motivation*: While `placement_guide.md` provides flexible natural guidance for an agent, Checker Framework stub files (`.astub`) natively encode method signatures and qualifiers in Java syntax.
- *Implementation Sketch*:
  - Step 3 generates `.astub` files representing target method signatures.
  - *Overlay Mode*: Pass `-Astubs=placement.astub` directly to Gradle without altering repository source files.
  - *Injection Mode*: A deterministic JavaParser utility merges `.astub` declarations directly into source code, bypassing agent non-determinism during application.
