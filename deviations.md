# Implementation Deviations

This file records places where the implementation intentionally differs from
`docs/implementation_plan.md`, plus internal API additions that were required to
make the specified behavior work. Design-level choices (for example, deferring
the crawler) are not listed unless the code had to interpret the plan.

## Toolchain and environment

1. **Kotlin 2.0.21 instead of 1.9.22; JDK 21 toolchain instead of a JDK 17
   toolchain.** The execution container only provides JDK 21. Target repositories
   are still compiled at their own Java level (`sourceCompatibility` /
   `targetCompatibility`, 17 for the synthetic benchmark). The README's "Kotlin
   1.9+ / 2.0" allowance is respected.
2. **Checker Framework is installed at `vendor/checker-framework-3.42.0`.**
   `/opt` is not writable in the container. `project_config.yaml` points
   `environment.checker_framework_home` at this checkout; version 3.42.0 is the
   version pinned by the plan.
3. **The installed CLI binary is named `pipeline`, not `llm-type-annotation-synthesis`.**
   The Gradle `application` block sets `applicationName = "pipeline"` to match the
   plan's command examples.

## Required fixes to plan snippets

4. **`CasKey` no longer has a secondary `String` constructor.** Because `CasKey`
   wraps `Sha256` (whose underlying JVM type is `String`), the plan's secondary
   constructor clashes with the primary constructor at the JVM signature level.
   `CasKey.fromHex(hex)` replaces it.
5. **SQLite DDL is split and executed statement-by-statement.** SQLite JDBC's
   `Statement.execute` runs only the first statement in a multi-statement string,
   so the plan's single `execute(Schema.DDL)` call silently created only the first
   table.
6. **Unified diffs preserve stdout exactly.** The Git helper originally trimmed
   command output, which removed the final newline and made `git apply` report
   `corrupt patch`. Patch-producing Git calls now use a raw-stdout helper.
7. **Verification passes the custom qualifier JAR to regression/suite test runs.**
   The plan says the annotated tree must compile with the qualifier JAR available;
   verification therefore supplies `lattice.customQualsJar` to both test
   invocations, not only to the checker pass.
8. **`checker.init.gradle` adds the custom qualifier JAR and checker-qual JAR to
   normal compile/test classpaths.** The plan explicitly requires normal
   compilation and tests to resolve qualifier imports while keeping the processor
   off `compileJava`/`test`. The init script mutates `compileClasspath`,
   `test.compileClasspath`, `test.runtimeClasspath`, and `Test.classpath` after
   evaluation; only the dedicated `checkerPass` task attaches the processor.

## Added internal APIs (behavior-preserving)

9. **Per-attempt step context.** `RunContext` has `stepId`, `stepNumber`,
   `attemptNumber`, and `stageName`. Child telemetry rows have foreign keys to
   `step_trajectories`, so the orchestrator must create the step row and give the
   stage its `step_id` before the stage writes checker/test/agent rows.
10. **CAS bundle staging handles and restore hooks.** `StageResult.Success` and
    `StageResult.Discard` carry an optional `bundleDir`, and `PipelineStage` has
    `restoreCached(bundleDir, manifest)`. The plan's `cas.restore<O>()` signature
    cannot reconstruct typed outputs without per-stage deserialization logic.
11. **`DatabasePort` additions.** `finishRun`, `readVerdict`, and `updateCommit`
    are needed to finalize `evaluation_runs`, read the canonical verdict view, and
    write ingestion/triage commit status. Stages still never open SQLite directly.
12. **`StageHaltException`.** Used for deterministic non-retryable halts that are
    not `Discard`s (for example, a vacuous 0-error application or a patch that
    does not apply to its declared baseline). The sealed `StageResult` type is
    unchanged.
13. **Deterministic triage discards are published to CAS with `success=false`.**
    The plan requires deterministic discards to be permanently cached. The
    orchestrator treats such a bundle as a cache hit and returns a discard without
    re-running triage.

## Agent isolation and process control

14. **Agent workspaces live outside the project repository.**
    OpenCode resolves its tool working directory to the enclosing Git root. A
    workspace under `.scratch/` inside the repository therefore allowed agents to
    write into the project root, breaking blindness and per-stage validation.
    The default scratch root is `${java.io.tmpdir}/pipeline-scratch`, overridable
    with `PIPELINE_SCRATCH_DIR`; each stage still gets an isolated sanitized
    export. This is a deliberate departure from the plan's `.scratch/<stage>_<run>`
    location and is required for correctness.
15. **OpenCode is invoked with `--standalone` and child stdin is closed.** The
    shared background OpenCode service serialized/hung pipeline agents and leaked
    the main session's working directory. `--standalone` gives each pipeline
    invocation a private server; closing stdin prevents non-interactive runs from
    waiting for EOF. The runner also uses a per-run OpenCode DB seeded from the
    global DB; `--standalone` alone does not isolate provider credentials.
16. **Seed/temperature are passed through environment variables.** The installed
    OpenCode CLI has no seed/temperature flags. They are still part of every CAS
    key. The CLI does not read them, so generation is **not actually seeded**;
    runs must be treated as non-deterministic despite deterministic cache keys.
    Step caps are enforced by counting `step_start` events and terminating the
    whole process tree.
17. **Network isolation is best-effort, not enforced.** `unshare -n` is not
    permitted in this container, so `repair.internet_access=false` sets
    `PIPELINE_AGENT_NO_NETWORK=1` in **synthesis only**; application and repair
    do not set it, and the CLI has no known consumer. It is not a sandbox
    boundary. The blindness canary test asserts filesystem isolation and
    documents this limitation.

## Synthesis / repair specifics

18. **Synthesis requires exactly one bottom qualifier.** The Checker Framework
    aborts with "Multiple bottoms found" when two or more sibling qualifiers have
    no unique bottom. The plan's Level-0 sketch mentions an `@IdBottom` in the
    design document, but the implementation plan's prompt did not require it; the
    synthesis prompt now requires a dedicated bottom marker for multi-child
    hierarchies.
19. **Repair checker-budget accounting.** The stage itself enforces
    `agent.max_checker_invocations` on harness-run checker passes. The
    agent-facing `run-checker.sh` also maintains a counter, but an agent could
    bypass it; the stage's own counter is authoritative.
20. **Strict mode is exactly lenient plus the four compiler flags**, as specified;
    no extended AST vectors or dynamic canary compilation are enabled in the MVP.

## Runtime configuration

21. **Agent step budgets are raised to 100/200/300 (synthesis/application/repair)
    in the live `project_config.yaml`.** The plan's defaults (25/20/50) were
    consumed by exploration before edits on this harness; the budgets remain
    configuration values and the plan's defaults are unchanged in code.
22. **Default model is `opencode-go/deepseek-v4.1-flash` in
    `project_config.yaml`.** An earlier apparent outage (`provider.no-route`)
    was caused by the runner's blank isolated OpenCode DB: the OpenCode Go
    account and credential rows live in the global DB, so a blank DB had no
    provider route. The runner now seeds its per-run DB from the global schema
    and auth rows; the model is a configuration value and every stage can
    override it independently.
23. **The synthetic benchmark uses the provided submodule.** The live
    `benchmarks.yaml` from the repository is authoritative
    (`C = 4d49e20…`, `C-1 = 56aa665…`). Triage derives `test_patch.diff` from
    `git diff C-1..C` limited to the benchmark's configured test roots
    (`:(glob)**/src/test/**` for the synthetic entry) because the manifest does
    not pin a patch file.
24. **CMSX has a curated benchmark entry, `cmsx-assignment-extension`.**
    It pins the extension-acceptance fix (`C = be6f19da…`, `C-1 = 7ee122ff…`)
    with regression test
    `cms.controller.persistence.AssignmentControllerImplTest#getSubmissionStatus_assignmentGraded_pastDue_hasExtension_accept`,
    `production_roots: ["src"]`, `test_roots: ["test", "regression/src"]`, and
    Java compatibility 21. The submodule checkout initially carried a
    host-relative `gitdir`; the container path was corrected to the mounted
    host gitdir (`.../worktrees/happy-meadow/modules/eval-repos/cmsx`).

## Bug fixes to the implementation itself

25. **Patch capture diffs against HEAD, not `--cached`.** `Git.stagedDiff`
    and `Git.changedPaths` ran `git add -N -A` followed by `git diff --cached`,
    which is blind to unstaged modifications of tracked files — exactly what
    agent edits mirrored into a worktree look like. The repair stage therefore
    saw "no file changes" every turn and published a 0-byte `repair.patch`,
    halting verification with `No valid patches in input` even though the fixer
    agent had correctly repaired the bug (final checker: 0 errors). Both
    helpers now diff against `HEAD` after the intent-to-add staging, capturing
    staged changes, unstaged tracked modifications, and new files alike.
    `PatchManagerTest` covers all four cases. Verification additionally skips
    (rather than halts on) legitimately empty patches.
26. **Added the missing `sanitizeCheckerLogPaths(worktree, text)` helper.**
    `RepairStage` called it, but no definition existed, so `compileKotlin`
    failed (the second error, a `.take` overload ambiguity, was a cascade of
    the unresolved reference). The helper redacts the worktree's absolute path
    from checker logs shown to the blind repair agent and is applied to both
    the initial and intermediate diagnostics.
27. **Verification now uses its own fresh worktree.** `VerificationStage`
    previously ignored the `verification/` worktree materialized by the
    orchestrator and reset `ctx.worktreeDir` (the ingestion worktree) instead.
    `VerificationInput` now carries an explicit `worktreeDir`; the stage resets
    and cleans that tree and fails closed if either command fails.
28. **Repair cache keys include the annotation patch.** The key was computed
    from `HEAD^{tree}` before `commitAnnotatedBaseline`, so it always described
    clean C-1 and could reuse a stale `repair.patch` across different
    annotation sets. `RepairInput` now carries `annotationsPatch`, whose bytes
    are hashed into the key.
29. **Agent startup failures halt instead of being retried nine times.** A
    nonzero CLI exit with zero completed steps (bad model ID, auth failure,
    missing executable) is now a `StageHaltException` carrying CLI stderr,
    rather than a `TransientFailure` that retried the identical input. CLI
    stderr is also included in synthesis/application/repair failure messages.
30. **Annotated-baseline commit failures are fatal.** `commitAnnotatedBaseline`
    used to warn and continue with clean C-1 as `HEAD`, which made every
    annotation look like an attacker edit to the repair guards.
31. **Telemetry foreign-key order fixed.** `checker_runs` are projected before
    `compilation_events`, whose `checker_run_id` references them.
32. **Run finalization survives cancellation.** `finishRun` now runs under
    `NonCancellable`, so a cancelled coroutine does not leave the run marked
    `running` forever.
33. **Agent subprocesses get `PWD` set to their stage workspace.** Java's
    `ProcessBuilder` changes the OS working directory but leaves the inherited
    `PWD` environment variable pointing at the parent pipeline checkout. The
    OpenCode CLI resolves its workspace from `PWD`, so agents wrote
    `placement_guide.md`, `custom_quals_src/`, and edits into the pipeline
    repository instead of the isolated stage export. This produced the
    recurring "placement_guide.md is missing" and "No file changes were
    detected" failures. Both the agent runner and the generic process runner
    now set `PWD` explicitly. A regression test asserts it.

## Known gaps not yet fixed

34. **Repair is capped at four agent turns** (`MAX_AGENT_TURNS`), independent of
    `agent.steps.repair` (a per-invocation step budget) and the checker-call
    budget. This is an undocumented MVP choice.
35. **`guardrails.mode: "disabled"` is accepted but inert.** Every stage runs
    the AST/filesystem guards regardless, so a disabled-vs-enabled ablation
    currently measures nothing.
36. **`verify-benchmark` delegates to `run` rather than offering a triage-only
    check.**
37. **Benchmark contamination is detected, not sandboxed.** Agents are not
    OS-isolated and can reach the benchmark checkout by absolute path; a live
    synthesis run did modify the synthetic submodule. `run` now refuses to
    start on a dirty benchmark tree, and each agent stage rolls back and halts
    if it detects a post-invocation change. Prompts also forbid subagents and
    writes outside the stage workspace. A real filesystem sandbox remains
    future work.
38. **The isolated OpenCode DB is seeded from the global DB.** A blank
    `OPENCODE_DB` has no `account`, `account_state`, or `credential` rows, so
    `opencode-go/*` fails with `provider.no-route`. `OpenCodeDb` creates a
    per-run DB from the global schema and copies only auth, migration, and
    catalog rows (never chat history). If the global DB is unavailable, the
    runner falls back to the shared DB and logs a warning.
39. **Regression-test telemetry is identified explicitly.** JUnit XML reports
    display names, not Java method names, so `is_regression_test` was zero for
    every verification row and `v_evaluation_verdicts` reported
    `verification_missing` even when the stage returned `FULL_SUCCESS`. The
    filtered regression run is now marked explicitly, and the suite filter
    excludes the display name observed in that run.

## Configuration portability

40. **The `project:` section is removed; benchmark identity comes from the
    resolved `benchmarks.yaml` entry.** This ensures the dirty-tree check and
    agent-contamination guard watch the selected benchmark repository rather
    than a single hardcoded repository, fixing incorrect checks when running a
    different benchmark. Relative `repo_url` values are now resolved against
    the manifest's directory instead of the process working directory.
41. **`build:` and Java source/target compatibility moved into each benchmark
    entry**, with code defaults identical to the previous values.
42. **Machine-specific paths are environment-driven.** Blank
    `environment.java_home` and `gradle_user_home` inherit `JAVA_HOME` and
    `GRADLE_USER_HOME`; blank `checker_framework_home` uses `CHECKERFRAMEWORK`
    or defaults to `vendor/checker-framework-3.42.0`.
43. **Configuration paths and provenance follow the selected config file.**
    Relative paths resolve against the `--config` file's directory, and
    `config_yaml`/`config_snapshot` read that file. The unused `logging:`
    section and `sample.seed` key were removed, and `paths.database` replaces
    the hardcoded `data/pipeline.db`.
44. **`.env` is loaded automatically before configuration parsing.** A file
    next to `--config` (or the file named by `PIPELINE_ENV_FILE`) supplies
    values only for keys absent from the process environment, and those values
    are forwarded to child Gradle/OpenCode processes. The file is parsed
    literally, not sourced, so shell expansion does not apply.

## CMSX benchmark support

45. **Benchmark entries declare `production_roots` / `test_roots`** (defaulting
    to the legacy `src/main` / `src/test`). `RunContext` carries the resolved
    roots, and triage patch discovery/auditing, the synthesis
    production-diff filter, sanitized workspace export and test read-only
    handling, prompt wording, and the application/repair filesystem and AST
    guards all follow them. Single-segment roots are repository-root anchored
    (`^src/`, `^test/`), so a bare `src` does not classify `regression/src` or
    `cmsx-ui/src` as production and a bare `test` does not match CMSX
    production helpers under `src/.../api/test/`. Multi-segment roots still
    match as trailing segments at any depth, preserving legacy multi-module
    `module/src/test` layouts. Test roots win on overlap (the fail-closed
    direction), and the legacy `*Test.java` filename signal is retained
    unchanged as a fallback. CMSX lists `regression/src` as a test root so the
    `:regression` subproject's test utilities are also read-only.
46. **JavaParser is 3.28.2 and the guards parse at `JAVA_21`.** The plan pins
    3.26.1 / Java 17, which cannot parse record patterns
    (`instanceof Some(var x)`) used across CMSX. 3.28.2 parses them at
    `JAVA_21`; the curated CMSX files parse, and all existing guard tests
    pass unchanged on the new version.
47. **Gradle tasks are project-qualified (`:compileJava`, `:cleanTest`,
    `:test`).** An empty `subproject` previously emitted an unqualified task
    name, which Gradle expands across every subproject. On CMSX a filtered
    `test --tests ...` therefore also ran `:regression:test`, found no matching
    tests, and exited non-zero even though the root regression test passed.
    Root project tasks are now explicit; `TestRunResult.success` also requires
    a zero exit code rather than merely the presence of JUnit XML. Test-patch
    discovery uses the same anchoring as path classification instead of a
    broad `**/<root>/**` glob.
48. **The unused `build.single_test_cmd` manifest key is removed.** It was
    never executed (only hashed into the triage cache key) and described a
    command the pipeline did not run. The README now documents the actual
    regression invocation.
49. **The synthesis production-diff filter fails closed.** A diff block whose
    paths cannot be parsed is dropped instead of retained, and test detection
    uses the full `isTestPath` signal (including the `*Test.java` fallback)
    rather than directory roots alone. Git's C-quoted paths in `diff --git`
    headers are parsed without splitting on embedded spaces.
