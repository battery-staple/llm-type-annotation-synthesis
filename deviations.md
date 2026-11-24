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

34. **RESOLVED — Repair's four-turn cap is now configurable.** The former
    hard-coded `MAX_AGENT_TURNS` MVP choice is now `repair.retry_turns` (default
    `4`), independent of `agent.steps.repair` (a per-invocation step budget).
    The effective bound remains
    `min(repair.retry_turns, agent.max_checker_invocations)`.
35. **`guardrails.mode: "disabled"` is accepted but inert.** Every stage runs
    the AST/filesystem guards regardless, so a disabled-vs-enabled ablation
    currently measures nothing.
36. **`verify-benchmark` delegates to `run` rather than offering a dedicated
    triage-only check.** Triage-only execution is available through
    `run --stop-after triage`, which finalizes the run as
    `stopped_after_triage` (an `evaluation_runs.status` value outside the
    plan's `running`/`success`/`failed`/`interrupted` set), records no verdict,
    and leaves the triage bundle in the CAS for reuse. `verify-benchmark`
    remains a thin alias for `run`.
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
50. **Deterministic stages do not include sample identity in cache keys.**
    `CacheKeys.compute` previously mixed `sample_seed`/`sample_temperature`
    into every stage's key, so ingestion, triage, and verification produced a
    separate bundle per seed even though their typed outputs do not depend on
    it. (`isDeterministic` means seed-independent, not bit-for-bit
    reproducible: these stages still run Git, Gradle, and tests, so a bundle
    may contain run-scoped metadata such as JUnit timings.)
    `PipelineStage.isDeterministic` now controls this: ingestion, manifest
    triage, and verification omit sample identity, so their bundles are shared
    across seeds (ingestion still re-materializes the per-run worktree from the
    cached commit hashes). Synthesis, application, repair, and the deferred
    automated triage keep it, because their LLM output is sample-dependent.
    Deterministic keys hash the whole `BuildSection` (tool, wrapper,
    subproject, and all task names) instead of only the task a stage appears
    to use; verification also hashes the custom-quals JAR and the project
    checker settings consumed by the test init script. Previously a bundle
    could be reused under a different build or checker configuration.
    Deterministic bundles also record `sample_index=0` /
    `sample_temperature=0.0` in their manifest, since a shared bundle is not
    owned by any one sample; per-run provenance remains in the `runs` and
    `steps` tables. This changes deterministic-stage cache keys, so existing
     deterministic bundles become misses once (LLM-stage keys are unchanged).
51. **The dedicated checker post-pass includes the project's compiled output and depends on `compileJava`.** The `checkerPass` task recompiles `main.allJava` with only the Checker Framework processor on its annotation-processor path. Sources that call classes generated by the project's own processors therefore failed to compile before the checker ever ran. CMSX is the motivating case: seven AutoValue classes reference their generated `AutoValue_*` subclasses, so the checker pass reported `package AutoValue_… does not exist` and the stage discarded a valid annotated tree. The init script now adds `sourceSets.main.output` to the task classpath and declares `dependsOn compileJava`, so generated classes (AutoValue, static JPA weaving) resolve while the checker still analyzes the source ASTs. The same change is mirrored in the repair agent's generated `run-checker.sh` init script. Processors whose generated *members* only exist in bytecode (Lombok, MapStruct) remain rejected; `main.output` fixes generated types, not generated members.
52. **Verification distinguishes missing evidence from failed tests.** A
     nonzero Gradle test task with a parseable report of assertion failures is
     substantive runtime/suite evidence; compile failures, timeouts, skipped-
     only reports, and unexplained nonzero exits remain `verification_missing`.
     Full-suite failures use `suite_failure` because the MVP does not compare
     against a clean pre-repair suite baseline. Existing databases migrate the
     test-run evidence columns and rebuild `v_evaluation_verdicts` on open; the
     old `regression_introduced` stored label remains readable as
     `suite_failure`.

## Crawler lifecycle and operational telemetry

53. **Crawler scheduling and the downstream limit are separate controls.** The
    crawler lists the configured commit depth (default 20), ingests/triages all
    non-root commits, and uses a bounded `jobs` scheduler (default 1) for whole
    per-commit runners. `limit` controls how many verified samples proceed past
    the hard downstream gate; it does not cap scanning or triage. Thus a sample
    rejected by that gate finishes with lifecycle status `triage_only`, records
    no verdict, and retains its triage CAS bundle. In
    `v_evaluation_verdicts`, `outcome_kind='triage_only'` distinguishes this
    from evaluation failure; its verdict is NULL. The viewer/offline metrics
    report expose the outcome as not evaluated, not as a failed evaluation.
54. **Crawler, agent, and build concurrency have separate controls.**
     `triage.jobs` / `--jobs` (default 1) bounds concurrent whole per-commit
     crawler runners. Nested, independent semaphores bound agent/LLM calls via
     `concurrency.agents` / `--agent-jobs` (default 8) and Gradle compile,
     test, and Checker invocations via `concurrency.builds` / `--build-jobs`
     (default 2). The nested limits do not replace the whole-pipeline bound.
     This scheduler is not a guarantee of deterministic behavior or production
     readiness for 1,000-commit crawls; output/transcript caps, daemon policy,
     and resource piloting remain operational limits.
55. **Run worktree/scratch retention follows lifecycle outcome.** Successful
    and `triage_only` runs remove their run worktrees and scratch data. Failures,
    discards, and `--stop-after` runs are retained for debugging. Per-run
    appenders are capped at 64 active run IDs and use a finite child timeout;
    excess concurrent run IDs may lack a per-run log, so the rolling JSON log
    (including `run_id`) is authoritative. Startup housekeeping prunes run logs
    after 14 days or above 2,000 files, orphaned CAS staging directories,
    stale OpenCode seed databases/sidecars, and stale per-invocation temporary
    databases.
56. **Database, retry, and subprocess telemetry have dedicated tables and
     summaries.**
     `db_operation_metrics` records database operation duration, success,
     affected rows, and errors; `v_db_operation_summary` groups calls, failures,
     and latency by operation. `retry_events` captures attempts, retry decision,
     delay, and error details; `v_retry_summary` groups attempts/retries by
     stage and error class. `subprocess_metrics` records subprocess stage, kind,
     duration, exit code, timeout status, and success; `v_subprocess_summary`
     summarizes these outcomes and timings. The viewer exposes subprocess
     summaries, and `analysis/evaluate_metrics.py` includes an offline
     subprocess section; all table/view summaries are also queryable in SQLite.
57. **Checker selection is an explicit synthesis decision, not a silent
    Nullness fallback.** The agent must write `synthesis_decision.json`; the
    decision is validated against `synthesis.allowed_checkers`, and invalid
    decisions or qualifier hierarchies are retried up to
    `synthesis.retry_sessions` attempts before the stage fails.
    `synthesis.builtin_checker` configures the selected built-in
    processor and is included in the synthesis cache key. Synthesis metadata
    records the accepted decision and confirms `fallback_occurred: false`.
    `synthesis_runs` adds nullable `decision_source`, `decision_rationale`, and
    `fallback_occurred` telemetry columns, with a migration for legacy databases.

## Build integration for third-party repositories (Dockerization plan, Phase 0)

58. **The qualifier jar is compiled with `--release 8`.** `JavacRunner` runs in
    the pipeline's JVM and previously wrote that JVM's class-file version (65 on
    JDK 21), which a JDK 17 toolchain rejects ("bad class file ... wrong version
    65.0", JSqlParser). Version 52 is readable by every JDK in the envelope and
    does not depend on a configured Java level, which may be a default. The
    synthesis prompt requires Java 8 qualifier sources and names the same
    `javac --release 8 -proc:none` command.
59. **Module projects keep `module-info.java`.** Excluding the descriptor from
    the main source set did not avoid module mode (javac found it through
    `-sourcepath`) and stripped it from the project's own compile. Every
    `JavaCompile` task except `checkerPass` whose sources contain a descriptor
    now gets `--add-reads <module>=ALL-UNNAMED[,<automatic harness modules>]`,
    `--add-modules` for harness jars with an `Automatic-Module-Name`
    (checker-qual), and `-Xlint:-exports`. The last one is needed because a
    qualifier on an exported API makes javac warn that the qualifier's module is
    not exported, which `-Xlint:all -Werror` builds fail on; the warning exists
    only because of the harness jars. `checkerPass` alone compiles in classpath
    mode, without the descriptor and with an empty source path. A harness jar
    that is an explicit module is rejected. Synthesis rejects qualifier packages
    that the project declares at C-1 (a split package stays unreadable).
60. **checker-qual is on the main compile classpath.** Without it, javac warns
    "Cannot find annotation method 'value()' in type 'SubtypeOf'" (fatal under
    `-Werror`, Agrona), and a `package-info.java` importing checker-qual
    defaulting annotations does not compile.
61. **`checkerPass` gets the checker's compiler configuration, not the
    project's.** It is a `JavaCompile` task, so `withType(JavaCompile)` blocks
    configured it with the project's `-Werror`, `-Xlint`, Error Prone plugin,
    and similar flags. A `doFirst` action now keeps only an allowlist of flags
    that decide whether sources compile (`--release`, `--add-exports`,
    `--add-modules`, `--limit-modules`, `--enable-preview`, `-encoding`, boot
    class path options, `-parameters`), drops every other flag together with its
    value, removes argument providers other than the harness's own (Error Prone
    adds `-Xplugin:ErrorProne` through one), disables Error Prone when its
    extension is present, and restores the processor path and fork options.
    This deviates from the plan's "set `compilerArgs` to exactly the checker
    flags": Agrona passes `--release` through `compilerArgs`, and projects using
    JDK-internal APIs need their `--add-exports`. Unknown flags are dropped,
    because a missing flag fails visibly while an unknown kept flag could change
    the diagnostics silently. The checker's own flags come from a
    `CommandLineArgumentProvider`, because projects may assign an immutable list
    to `compilerArgs` (RoaringBitmap assigns a Kotlin `listOf`), on which the
    old `addAll` threw while the task was created.
62. **The application agent gets a compile-only helper.** `run-compile.sh` runs
    the compile and `compileTestJava` tasks through the harness init script with
    the harness environment, so qualifier imports resolve as in the harness
    compile. It never runs the checker: seeing checker errors would let the
    agent steer placements toward or away from errors, which is what
    `prompt.tell_non_triviality_error` measures. The prompt no longer asks the
    agent to set up a classpath, and it states the non-triviality requirement
    only when that flag is true (previously it always did, so the flag had no
    effect).
63. **Agent workspaces are one-commit Git repositories without project history,
    and they never contain agent instruction files.** After the sanitized
    export, application and repair workspaces get a synthetic one-commit `.git`,
    so build scripts that call `git` during configuration (JSqlParser's
    `git describe`) work in agent helpers; this was planned for Phase 2 but is
    needed on the host. `AGENTS.md`, `CLAUDE.md`, `opencode.json(c)`,
    `.opencode/`, and `.claude/` are excluded from every export at any depth;
    RoaringBitmap, caffeine, and wiremock ship such files, and OpenCode reads
    them as instructions.
64. **Build-stage cache keys hash `checker.init.gradle`.** The template is a
    resource, so `StageCodeFingerprint` did not see template-only changes.
    Application, repair, and verification keys now include its hash; synthesis
    and triage, which do not use it, are unaffected.

## Build-integration preflight (Dockerization plan, Phase 0.5)

65. **Preflight is an optional pipeline stage between ingestion and triage.**
    It is off by default, so a run has the original six stages and step
    numbers; `pipeline run --preflight` or `preflight.enabled: true` adds it
    (the plan has it always on). `pipeline preflight` always runs it. It runs
    checks P1-P7 at clean C-1 in its own worktree, with no LLM call, and stops
    at the first failure. An unsupported repository is a successful,
    cacheable preflight whose report says `unsupported`; the run ends with
    lifecycle status `unsupported`, the outcome is stored in
    `preflight_results`, and `v_evaluation_verdicts` gives it outcome kind
    `unsupported` and no verdict, so it is excluded from evaluation metrics.
    With preflight, the step numbers after ingestion shift by one (triage is
    step 3); without it they are unchanged.
66. **P7 runs the full test suite**, which the plan did not have. Application
    and verification both run the full suite under the build timeout, so a
    suite that cannot finish (`suite_timeout`) or already fails at C-1
    (`suite_fails_at_baseline`) means no run on that repository can reach a
    verdict. `preflight.suite_check: false` or `pipeline preflight
    --skip-suite` skips it.
67. **The source level comes from the build.** P1 reads the compile task's
    `release`, then a `--release` in its `compilerArgs` (Agrona), then its
    `sourceCompatibility`; P2 then reads the class-file version of the
    compiled classes, which also covers builds where a plugin sets the level
    at execution time (RoaringBitmap). Preflight's own checker runs use that
    level. A `benchmarks.yaml` value that disagrees is reported in the report,
    not used; the later stages still use the configured value until the
    section 7.5 change feeds the probe value into `checkerPass`.
68. **Preflight never downloads a toolchain and never uses the build cache.**
    Every preflight Gradle call passes
    `-Porg.gradle.java.installations.auto-download=false`, so a missing JDK is
    `jdk_unavailable` on the host as in Docker, and `--no-build-cache`.
    `cleanTest` removes a test task's outputs but not Gradle's build cache, so
    in a project that enables the cache (JSqlParser, resilience4j) the test
    task was restored `FROM-CACHE`: JSqlParser's 7,550-test suite "ran" in 1 s,
    which makes P7's timeout check meaningless and ties the result to this
    machine's cache. The later stages pass neither flag and keep the project's
    cache setting: repaired code has new inputs, so verification misses the
    cache anyway, and up-to-date checks already skip repeated unchanged
    builds within one worktree.
69. **Cache key: the build configuration, not the sources.** The key hashes
    every committed file except Java sources under the production and test
    roots (Java files in `buildSrc` and in included builds, found by their own
    settings file, count as build logic), plus the P6 test class source, the
    init script, the probe templates, and the configuration that affects
    builds. Outcomes that depend on the sources, such as a checker crash on new
    syntax or a newly failing test, are rechecked by the later stages.
70. **Reason codes added to the plan's list**: `gradle_runtime_unsupported`,
    `init_script_failure`, `checker_jdk_unsupported`, `checker_misreport`,
    `suite_timeout`, and `suite_fails_at_baseline`. The codes were fixed, and
    `expected_preflight` predictions recorded, in a commit before the first
    preflight run.
71. **CAS directory artifacts validate by digest.** `CasEngine.validateArtifacts`
    treated only `custom_quals_src` as a directory, so any other registered
    directory (preflight's logs) made its bundle fail validation and never hit.
72. **CAS staging directories carry their owner's PID.** Startup cleanup
    deleted every `.staging-*` directory, so starting a second pipeline process
    destroyed a bundle another process was still writing (it wiped an
    in-progress preflight). Only directories of dead owners are removed now;
    directories with the old naming are removed after six hours.
73. **The checker flag sanitizer runs directly before the compile action.** A
    project `doFirst` runs at execution time and can append `-proc:none`,
    which silently disabled the checker because the sanitizer ran first. After
    the task graph is ready, the init script inserts the sanitizer behind every
    `doFirst`, before the first action that is not a `doFirst`/`doLast`
    closure; if none exists, it runs first as before.
74. **Project argument providers pass through the allowlist** instead of being
    dropped whole. Error Prone's `-Xplugin:ErrorProne` is still dropped, while
    an `--add-exports` a project supplies through a provider is kept, so
    sources that compile normally also compile in `checkerPass`.
75. **The init script registers its per-project setup with
    `gradle.beforeProject`, not `allprojects`.** An init-script `allprojects`
    block, even an empty one, breaks `buildSrc` convention plugins that use
    type-safe version-catalog accessors ("Extension of type 'LibrariesForLibs'
    does not exist"); wiremock failed P3 on it, and the pre-Phase-0 template
    had the same problem.
76. **Gradle fixture tests.** `src/fixtureTest` runs the real init script with
    Gradle TestKit on generated offline projects, one per build condition that
    broke the harness (module descriptors, `-Werror`, inherited and late
    compiler flags, argument providers, immutable `compilerArgs`, the agent's
    compile helper). Every checker assertion requires planted errors to be
    reported, so a checker that silently did not run fails the test. The suite
    runs under `./gradlew check`, not `./gradlew test`.

## Builds in a container (Dockerization plan, Phase 1)

77. **Build containers mount each harness file at its own path instead of a
    copied `.harness/` directory.** The plan copies the init script, qualifier
    jar, and `checker-qual.jar` into a per-run directory before each build. The
    harness files already live at fixed host paths outside the worktree (the
    init script next to it, the qualifier jar in its CAS bundle, the Checker
    Framework under `vendor/`), so each is bind-mounted read-only at that path:
    the same isolation, with no copying and no path rewriting in the
    environment. The mounts are derived from the invocation itself (every
    `--init-script` argument, `CHECKERFRAMEWORK_HOME`, `CUSTOM_QUAL_JAR_PATH`,
    `CF_EXTRA_CLASSPATH`), plus the worktree (writable) and the Git
    directories its `.git` file points to and their alternates (read-only).
    Both backends therefore use the vendored Checker Framework; the image gets
    its own copy in Phase 2, when agents need `/harness`.
78. **Preflight's P1 probe gets one writable directory.** The probe init
    script writes its report from inside Gradle, to `.preflight-harness/`
    next to the worktree, which the plan did not cover. `GradleTaskRunner.runTasks`
    takes `writableDirs`, and only that call passes one. It runs only the
    repository's own C-1 build scripts.
79. **Docker-mode cache-key fields are added in Phase 1, not Phase 3.**
    Without them a Docker run would be served the bundles of local runs, and
    the parity comparison would compare a run with itself. `execution_backend`,
    `image_id`, and `runtime_jdk` enter the shared `runtime_environment` map only
    when the backend is `docker`, so every local key, and every existing bundle,
    is unchanged. As the plan says, this covers every stage, synthesis
    included, although Phase 1 synthesis still runs on the host.
80. **`runtime_jdk` is a `build:` field, and only Docker mode reads it.**
    `build:` is shared by `benchmarks.yaml` and `repos.yaml` entries and already
    reaches the build runner. The default is `"21"`. The local backend keeps
    using `environment.java_home` or the inherited `JAVA_HOME`, because the host
    has no JDK registry to select from. jobrunr sets `"25"` (its Micronaut
    build plugin needs Gradle itself on JDK 25), so its preflight result is
    expected to differ between backends. A `runtime_jdk` the image lacks fails
    when the runners are created, before any run starts.
81. **Every Docker-mode Gradle invocation uses only the image's JDKs.** It
    passes `-Porg.gradle.java.installations.auto-detect=false`,
    `auto-download=false`, and `paths=` listing the image's JDKs. On the host only
    preflight disables downloads; the other stages are unchanged. The image's
    JDK list, JDK root, Gradle home, and user home come from its labels, which
    startup reads, so the code does not hard-code the image layout.
82. **`ExecutionHandle` exists and builds use it; agents keep their own
    process handling until Phase 2.** A build timeout now runs `docker rm -f`
    on the container before killing the `docker` client. `AgentRunner` moves
    onto the handle in Phase 2, when the agent runs in a container and the
    handle matters.
83. **Per-container limits, the owner label, and shutdown cleanup arrive
    early.** Every build container gets `--memory` (`execution.build_memory`)
    and `--cpus`, the label `lltas.owner_pid`, and a shutdown hook removes the
    process's containers. Startup refuses a `build_memory` larger than the
    Docker VM and warns when `concurrency.builds` times it exceeds the VM. The
    dead-owner reconciliation, agent limits, and Linux `--user` remain Phase 3.
84. **The image has JDKs 8, 11, 17, 21, and 25, not 26.** The Phase 0.5
    preflight rows need 8 (reactor-core tests), 17 (JSqlParser), 21, and 25
    (reactor-core compile, jobrunr's Gradle runtime); 11 matches a JDK the host
    already had. caffeine, the only repository that needs 26, is disabled.
    Base images are pinned by multi-arch digest.
85. **The container's clock and locale are fixed**: UTC and `C.UTF-8`.
    Host runs use the machine's time zone. Tests that depend on the zone (the
    cmsx DTSTAMP test that already fails at C-1 on some commits) may behave
    differently, and parity runs list such differences rather than copying the
    host's zone into the container.
86. **`--backend local|docker`** on `run`, `preflight`, and `init` overrides
    `execution.backend` for one command, for parity runs. The resolved image ID
    is written to `execution.image_id` in the run's configuration, so the
    `evaluation_runs` config snapshot names the exact image; a configured
    `image_id` that does not match the image refuses to start.
87. **The image carries the tools build scripts shell out to**: git, curl,
    and the common archive formats (xz, bzip2, zstd, zip). Agrona's build
    bootstraps a Zig toolchain from a `.tar.xz` on Linux; without `xz` its P2
    failed in the container while passing on the host, which has `xz` from
    Homebrew. The list is about what any build may assume of a developer
    machine, not about one repository.
88. **A container that never started is an error, not a result.** `docker
    run` exits 125 for daemon and client errors (image removed, mount source
    missing, daemon gone). The Docker backend throws
    `ExecutionInfrastructureException` for those, so the run fails instead of
    being recorded as a failed build. In the first Phase 1 preflight batch,
    rebuilding the image tag mid-batch let Docker Desktop delete the image the
    batch was pinned to, and six repositories were recorded as `unknown`
    before this check existed (those `preflight_results` rows carry image ID
    `sha256:b950885a…`).
89. **A TLS connection cut mid-handshake is a network failure.** Preflight
    retries network failures, but its patterns did not include
    `SSLHandshakeException` ("Remote host terminated the handshake"), so a
    reset while the Gradle wrapper downloaded its distribution was recorded as
    P1 `unknown` (jobrunr, first Docker run on the new image). Those messages
    and "Connection reset" are now transient.
90. **A task that fails before `checkerPass` runs is
    `checker_prerequisite_failed`**, a new reason code, now in the plan's §7.3 list. P5
    used to report any build where `checkerPass` never ran as
    `checker_silent`, which hid jobrunr's real failure (`:core:npmInstall`,
    reached through `main.output`). The message names the failed task.
91. **Local parity runs used Corretto 21.0.13.1, not the image's Temurin
    21.0.13.** The Phase 1 exit criterion pins the host `JAVA_HOME` to the
    image's JDK 21. The host has no Temurin 21, and the two builds are the same
    OpenJDK release, so the runs used the host's Corretto. No parity difference
    traced to the JDK vendor.
