# Dockerization Plan: Containerized Builds and Agent Sessions

Status: proposed (2026-11-23), revised the same day after a review against the
code. Phase 0 is implemented on branch `phase0-harness-fixes` (see "Phase 0
status" in §5); the later phases are not started. The Phase 0 review changed
four parts of the design: the `init.d` script turned out to be written by a
pipeline agent (§1), bug 4 uses an allowlist rather than clearing every
inherited flag (§7.5), module projects also need `-Xlint:-exports` (§7.6), and
the synthetic `.git` moved from Phase 2 to Phase 0 (§3.8).

This plan runs the pipeline's builds and agent sessions inside controlled Docker
containers so each target project builds in a known, pinned environment and
agents cannot reach the host. It follows from the JSqlParser and Agrona runs,
which exposed harness bugs, host-state dependencies, and an agent sandbox that
does not contain the agent. The Background section describes those runs.

---

## Background: The JSqlParser and Agrona Runs

Until these runs, only `synthetic-id-swap` and `cmsx` had been through the
pipeline. Both are small, compile on JDK 17 or 21 without unusual compiler
settings, and have no `module-info.java`. JSqlParser and Agrona were the first
real third-party repositories, and the application stage failed on every
attempt for both.

Each harness bug below was reproduced with a probe file outside the pipeline,
so the failures do not depend on agent behavior. An earlier fix (commit
`ea7745f`) had already addressed qualifiers missing from compile and test
builds; it was found on JSqlParser, whose JavaCC plugin configures every
`JavaCompile` task even when only `compileJava` runs.

### Harness bugs

1. **Qualifier jar built for the wrong Java version (JSqlParser).** `JavacRunner`
   compiles the synthesized qualifiers inside the pipeline's own JVM (JDK 21)
   and passes no `--release` flag, so the jar contains Java 21 class files
   (version 65). JSqlParser compiles through a Gradle toolchain on JDK 17,
   which cannot read them. The error appears as `bad class file … wrong
   version 65.0`, which then surfaces as "package does not exist" for the
   qualifier imports. What fails is the JDK, not the project's `--release`
   level: JDK 17 javac rejects a version-65 jar at `--release 11` and at
   `--release 17`, while JDK 21 javac at `--release 11` reads it. Every JVM
   that loads the jar must be able to read it: the javac that compiles the
   project, the javac running `checkerPass` (which loads the qualifier
   classes for `-Aquals`), and any test JVM that reflects on the annotations
   (JDK 17 `java` throws `UnsupportedClassVersionError`). Related risk: the
   jar's version follows whatever JVM runs the pipeline. Launched without
   `JAVA_HOME`, that is the default `java` on the development machine's
   `PATH`, JDK 27, and the jar would break every repository, `cmsx` included.

2. **Module compilation (JSqlParser; also RoaringBitmap, caffeine).** Projects
   with a `module-info.java` in their main sources are compiled in module mode,
   and a named module cannot read classpath jars (the unnamed module). Result:
   `package … is not visible` for the qualifier packages. The init script's
   `main.java.exclude('**/module-info.java')` does not prevent this: Gradle
   passes `-sourcepath src/main/java`, and javac finds `module-info.java`
   there and compiles every class as part of the module anyway. The exclusion
   is also too broad: it applies to the whole main source set, so it would
   strip the descriptor from the project's own `compileJava` and jar. The fix
   keeps `module-info.java` (§7.6).

3. **`checker-qual.jar` missing from the main compile classpath (Agrona).** The
   init script adds it to test compilation only. Agrona compiles with
   `-Werror`, so the warning `Cannot find annotation method 'value()' in type
   'SubtypeOf'` fails the build. A `package-info.java` that imports
   checker-qual (for example `@DefaultQualifier`) fails outright with
   `package org.checkerframework.framework.qual does not exist`; both were
   reproduced in a minimal module project. In module projects, adding the jar
   to the classpath is not enough (§7.6).

4. **`checkerPass` inherits the project's compiler flags (Agrona).** Agrona
   configures every `JavaCompile` task through `withType(JavaCompile)`, so the
   checker task picks up `-Werror` and `-Xlint`. The warning "No processor
   claimed…" becomes an error, and the checker fails without reporting any
   file-level errors. That outcome risks being scored as zero type errors, or
   as a checker that never ran.

### Agent tooling and containment

- **`run-compile.sh` fails on JSqlParser.** The agent workspace is a sanitized
  export with no `.git`, and JSqlParser's build calls `git` during
  configuration. Where the helper does run, it compiles without the qualifier
  jar, so it cannot check annotated code.
- **The application prompt contradicts itself.** It tells the agent to put the
  qualifier jar on the classpath, which requires editing build files the agent
  is forbidden to touch, and says the checker must report errors without
  giving the agent any way to run it. On JSqlParser the agent spent an entire
  15-minute session trying to run the checker by hand.
- **The sandbox does not contain the agent.** During these runs the agent found
  the vendored Checker Framework through a search of the whole filesystem,
  wrote to `/tmp`, made web requests (GitHub, the Checker Framework docs), and
  read files under the user's home directory. The guards only audit the diff
  the agent produces; nothing restricts what it can read or reach.

### Environment and scale

- **Checker cost.** The checker took about 4m45s on unannotated JSqlParser.
  Annotated code is slower, the Gradle timeout is 900 s, and repair allows up
  to 20 checker runs.
- **Unavailable JDKs.** caffeine pins JDK 26 for both compiling and running
  Gradle. It is not installed, so Gradle would try to download it, and
  Checker Framework 3.42 (December 2023) may not run on it.
- **Host state.** Builds depend on whatever the host provides: the JDK on
  `PATH`, `JAVA_HOME`, user-global Gradle init scripts, JDKs Gradle has already
  downloaded, and the local Maven repository (§1, "Hidden host state").

### Not addressed by this plan

These were also observed but are not environment problems:

- **Lattice defaults versus repair rules (cmsx).** Unannotated literals and
  enum constants default to the lattice's top qualifier, so errors such as
  `setHidden(false)` cannot be fixed without casts or annotated locals, both of
  which repair forbids. Expect `fixer_incomplete` verdicts wherever a lattice
  behaves like this.
- **Triage near the threshold is not reproducible.** JSqlParser commit
  `32d00421` passed triage once and failed the next time (0.72 against 0.75).
- **Laptop sleep and the shared checkout.** See §1.

### Requirement: Fixes Must Be General

JSqlParser and Agrona are where these bugs surfaced, not what they are about.
The toolchain mismatch affects any project compiled by an older JDK; module
mode affects any project with a `module-info.java`; the `-Werror` interactions
affect any project with strict compiler flags. Every fix in this plan must
therefore work for any Gradle-based Java repository, not only the one that
exposed the problem:

- **No per-repository special cases** in harness code, the init script, or the
  runtime image: no checks on benchmark IDs, repository names, or paths.
  Per-benchmark settings in `benchmarks.yaml` describe facts about a
  repository (source roots, JDK, subproject), never workarounds.
- **State each fix as a condition** ("any project whose main compile uses
  `-Werror`") and fix the mechanism behind it.
- **Verify on more than one repository.** A fix is done when it works on every
  eval repo that has the triggering condition, not just the one where it was
  found. For example, the module-mode fix must also pass on RoaringBitmap,
  caffeine, and picocli.
- **Verify with preflight** (§7.3) across all eval repos, so a fix for one
  repository cannot quietly break another.
- **If a general fix is not possible,** add the case to the out-of-scope list
  in §7.2 with a reason code. Do not add a special case.

The target is any Gradle-based Java repository inside the supported envelope
(§7.2). The envelope's exclusions are explicit and meant to shrink over time,
and the corpus measurement (§7.4) shows how much of the ecosystem it covers.

---

## 1. What Docker Fixes and What It Does Not

Docker fixes the environment problems. It does not fix the four known harness
bugs: they are javac and Gradle configuration mistakes that fail the same way
inside a container. They are fixed first, on the host (Phase 0), so that a
failure seen inside Docker can only come from Docker.

| Issue | Fixed by Docker? | Actual fix |
|---|---|---|
| 1. Qualifier jar compiled for the pipeline's Java 21 (`bad class file … wrong version 65.0`) | No | `JavacRunner` runs inside the pipeline's JVM; pass a fixed `--release 8` (§7.5). Docker does remove the related risk of an unset `JAVA_HOME` selecting JDK 27. |
| 2. Module projects cannot read the classpath jars; javac finds `module-info.java` through `-sourcepath` despite the exclusion | No | Keep `module-info.java` and pass `--add-modules`/`--add-reads` from `checker.init.gradle` (§7.6). |
| 3. `checker-qual.jar` missing from the main compile classpath (breaks `-Werror` projects) | No | Add it to the main compile classpath in `checker.init.gradle`; module projects also need §7.6. |
| 4. `checkerPass` inherits project compiler flags (`-Werror`, `-Xlint`) | No | Clear inherited `compilerArgs` in `checker.init.gradle`. |
| `run-compile.sh` fails because the agent workspace has no `.git` | Partly | Synthetic one-commit `.git` in the workspace (§3.8, done in Phase 0); helper uses the init script. |
| Application prompt contradicts its tooling | No | Compile-only `run-compile.sh` through the init script; the agent never runs the checker, and the prompt states the non-triviality requirement only when `prompt.tell_non_triviality_error` is true (done in Phase 0). |
| Agent not contained (filesystem search, `/tmp` writes, web requests, reads under `$HOME`) | **Yes** | Workspace-only mounts and an egress allowlist for agents; narrow mounts for build containers too, because they run agent-written code (§3.2, §3.6, §3.7). |
| Caffeine pins JDK 26; Gradle would auto-download it | **Yes** | Pinned JDKs in the image, toolchain auto-download disabled, clear discard when a JDK is missing. |
| Target repositories ship agent instruction files (`AGENTS.md` in RoaringBitmap, caffeine, wiremock; `CLAUDE.md` in caffeine, wiremock) that OpenCode reads | No | Exclude agent instruction and config files from every workspace export (§3.6). |
| Laptop sleep stalls runs | No | The Docker VM pauses on sleep too. Keep using `caffeinate -i`. |
| Shared checkout, `pipeline.log`, and database | No | Unchanged; filter by run ID. |

### Hidden host state

`~/.gradle/init.d/zz-cf-compile-init.gradle` existed on the development machine.
Gradle applied it to every build on the host: it added `CUSTOM_QUAL_JAR_PATH`
to `compileJava`/`compileTestJava` and converted `JavaCompile` arguments to
plain strings.

**A pipeline agent wrote it.** The repair agent of cmsx run `0113bc38`
created it with a shell heredoc at 2026-11-22T21:48Z (transcript
`logs/transcripts/0113bc38-…/cbd078b2-….jsonl`, turns 49 and 52) to make its
own checker invocations work, then rewrote it to get past a `GStringImpl`
cast error. It is not harness configuration: it is an agent escape that changed
the environment of every later host build, including the harness builds that
score agents. All host runs after that time ran with it: the cmsx batch from
22:19Z (including the `cmsx-be6f19da` success), and every JSqlParser,
RoaringBitmap, Agrona, and caffeine run.

Its two effects are already covered by the template (the qualifier jar on both
compile classpaths; harness arguments are built as `String`s), so it was not
migrated. Phase 0 moved it to `~/.gradle/init.d-quarantine/` (reversible).
`synthetic-id-swap` and `cmsx` should be re-run on the host to confirm that
their verdicts do not depend on it. Only one host write turned up in the
transcripts; other agents only read under `$HOME` (`~/.gradle/caches`,
`~/.m2`) while searching for jars. Until agents run in containers (Phase 2),
the same escape remains possible on the host.

Other host state that containers will not have:

- **Downloaded JDKs.** `~/.gradle/jdks` holds JDKs that Gradle provisioned on
  its own (Adoptium 11, Zulu 25), so host builds already rely on toolchain
  auto-download.
- **The local Maven repository.** agrona, jobrunr, reactor-core, and wiremock
  declare `mavenLocal()`, so host builds can resolve artifacts from `~/.m2`
  that a clean container cannot.

Neither gets a special case. Phase 1 parity runs list them as known
differences; a repository that only builds with host artifacts fails P2
(`baseline_compile_failed`, §7.3).

---

## 2. Architecture

The orchestrator stays on the host. Every untrusted or environment-sensitive
subprocess runs in a container.

```
HOST (trusted): PipelineOrchestrator, Git mirrors/worktrees, guards (AST/FS/diff),
                CAS, SQLite, JavacRunner (with --release), logs/viewer
   │ docker run --rm (one container per invocation, labelled lltas.run_id=…)
   ├── BUILD container : harness Gradle compile / test / checkerPass
   │                     mounts: worktree (rw), Git mirror (ro), run harness dir (ro),
   │                             Gradle home volume (rw); same paths as the host
   │                     network: bridge (Maven Central, Gradle plugin portal)
   └── AGENT container : opencode run … (one per agent session)
                         mounts: /workspace (rw), /harness (ro), Gradle cache (ro),
                                 session state dir (rw)
                         network: LLM provider only (proxy), or none
```

Rationale:

- The guards, database, and CAS decide whether an agent cheated, so they stay
  outside anything the agent can touch. That includes build containers:
  verification runs the repaired production code, and the application agent
  can edit tests, so harness builds execute agent-written code. No container
  mounts the project root, `data/`, `.cache/`, `logs/`, or `.env`.
- Git operations are already correct on the host.
- The viewer, `.env` handling, and logging need no changes.

Running the whole pipeline in one container was considered and rejected: it
puts the agent and the guards in the same trust domain and still needs the
agent isolated inside it.

---

## 3. Design Decisions

### 3.1 New port: `ExecutionBackend`

Add to `src/main/kotlin/pipeline/ports/Ports.kt`:

- `run(cmd, cwd, env, timeout, mounts, network): ProcResult` for builds.
- `start(...): ExecutionHandle` for the agent's JSON event stream. The handle
  exposes stdout/stderr, `isAlive`, `waitFor`, and `kill()`. A plain `Process`
  is not enough: in Docker mode it is the `docker` CLI, `destroyForcibly()`
  kills the client and leaves the container running, and `isAlive` turns
  false while the container still runs. `DockerBackend.kill()` is
  `docker rm -f <name>`. `AgentRunner`'s timeout and step-cap paths, which
  call `terminateTree` today, use the handle instead.

Implementations:

- `LocalBackend` wraps today's `Processes.run` and `ProcessBuilder` paths.
  Behavior is unchanged.
- `DockerBackend` invokes the `docker` CLI directly. No docker-java dependency.

Configuration `execution.backend: local | docker` keeps the local path available
for comparison runs, consistent with the project's ablation principle.

### 3.2 Mounts, paths, and environment

**Build containers use same-path mounts, but only of what the build needs.**
Worktrees under `.worktrees/` contain `.git` files pointing to absolute paths
in `eval-repos/<name>.git`; `CUSTOM_QUAL_JAR_PATH`, the init-script path, and
file paths in checker diagnostics are also absolute. Mounting at identical paths
means nothing needs translating. Each build container mounts:

- the worktree, read-write;
- the Git mirror, read-only, so build scripts that call `git` (JSqlParser) work;
- one per-run harness directory, read-only, at
  `.worktrees/run_<id>/.harness/`. It holds the init script, the qualifier jar,
  `checker-qual.jar`, and any `CF_EXTRA_CLASSPATH` entries, copied in before
  the build. The Checker Framework is either copied there or mounted read-only
  at its own path.

Never the project root (§2). The image sets `git config --system
safe.directory '*'`, because git refuses to work in a repository owned by a
different user, and the container user may not match the host owner.

**Agent containers use fixed container paths.** The workspace is `/workspace`
(read-write) and the harness files are `/harness` (read-only). Same-path mounts
would show the agent `/Users/<name>/…`, the host layout that
`sanitizeCheckerLogPaths` exists to hide. Every agent-side path must therefore
be a container path:

- `run-checker.sh` currently reaches outside the workspace:
  `$SCRIPT_DIR/../.pipeline-init/checker.init.gradle` and
  `$SCRIPT_DIR/../.checker-invocations` (`RepairStage.kt`), plus a host
  `CHECKERFRAMEWORK_HOME`. With a workspace-only mount, every checker call
  would fail, and because the script has no `set -e`, the budget counter write
  would fail silently. In Docker mode the init script and Checker Framework
  come from `/harness`, and the counter lives in a separate writable session
  state mount outside `/workspace`. The stage's own budget check stays
  authoritative, since the agent can write anything its user can write.
- Synthesis passes `CHECKER_QUAL_JAR` as an absolute host path
  (`SynthesisStage.kt`); it becomes `/harness/checker-qual.jar`, and the image
  provides the `javac` the synthesis agent uses.

**Environment.** On the host, child processes inherit the whole process
environment plus `.env` (`Env.applyTo`). Containers get an explicit allowlist
instead: the harness `CF_*` variables, `CHECKERFRAMEWORK_HOME`,
`CUSTOM_QUAL_JAR_PATH`, `GUARDRAILS_MODE`, `GRADLE_USER_HOME`, `JAVA_HOME`, and
the agent's `OPENCODE_DB` and proxy settings. In Docker mode the host paths in
`environment.java_home` and `environment.gradle_user_home` are ignored:
`JAVA_HOME` comes from `runtime_jdk` (§3.5), in agent containers too, since
`run-checker.sh` does not set it.

In Docker mode, set `PIPELINE_SCRATCH_DIR` to a known path instead of relying
on `java.io.tmpdir` (`/var/folders/…` on macOS).

### 3.3 One container per invocation

Use `docker run --rm --name <unique>` per Gradle invocation and per agent
session, not `docker exec` into a long-lived container. Killing a `docker exec`
client does not kill the process inside the container. With one container per
invocation, a timeout is `docker rm -f <name>`, which replaces `terminateTree`
and stops leaked Gradle daemons.

Every `docker run` passes `--init`. Without an init process, Gradle or OpenCode
runs as PID 1, which ignores SIGTERM by default and does not reap the worker
processes Gradle forks.

Cost: a cold Gradle JVM each time, roughly 5–15 s, small next to a checker run
of about 5 minutes on JSqlParser. Daemon reuse is a later optimization.

### 3.4 Gradle home in a named volume

Harness builds use a named volume `lltas-gradle-home` as `GRADLE_USER_HOME`,
not a bind mount of `~/.gradle`. This keeps the host `init.d` out, avoids slow
file locking over VirtioFS, and lets concurrent containers share the cache
safely (all containers run in the same VM).

Agent containers get the cache read-only through `GRADLE_RO_DEP_CACHE`, a
private writable `GRADLE_USER_HOME`, and `--offline`. Wrapper distributions are
not covered by the read-only cache, so `wrapper/dists` is copied into the
private home; a read-only mount would not work, because the wrapper writes lock
files in the distribution directory. Dependencies are warmed by harness builds
(ingestion and triage) before any agent runs.

Anything set in the shared volume's `gradle.properties` is missing from an
agent's private home, so settings every build needs (the toolchain properties
in §3.5) are passed on the Gradle command line or written into every Gradle
home the harness creates.

### 3.5 JDK selection

One runtime image contains Temurin 8, 11, 17, and 21 (plus 26 if caffeine is in
scope; caffeine's build uses at least JDK 26 to compile and run), with
`org.gradle.java.installations.paths=…` and
`org.gradle.java.installations.auto-download=false`. They are passed to every
Gradle invocation, harness and agent, as described in §3.4. A missing toolchain
fails fast and is recorded as a discard rather than triggering a download.

New benchmark field `runtime_jdk` in `benchmarks.yaml`: the JDK that runs
Gradle, set as `JAVA_HOME` in both build and agent containers. Old Gradle
wrappers cannot run on JDK 21. A later improvement selects it automatically from
the Gradle version in `gradle/wrapper/gradle-wrapper.properties`.

### 3.6 OpenCode inside the agent container

OpenCode's tools (bash, read, edit) execute in OpenCode's own process, so
containing them requires running OpenCode in the container. The host still
reads its stdout and parses events as today; only the command built in
`src/main/kotlin/pipeline/runners/AgentRunner.kt` changes. The `PWD` workaround
becomes `-w /workspace`.

Every OpenCode session goes through this path, including the triage
classifier: `OpenCodeSingleShotLlmClient` runs a full `--auto` agent with tools
in a host temp directory and would otherwise stay uncontained.

The per-session `OPENCODE_DB` is placed in a host directory mounted into the
container, so reasoning reconciliation can read it after the session exits.

Known limitation: that DB contains the provider credential rows, and the agent
can read it. This is no worse than today.

The DB's schema and migration rows are copied from the host's global OpenCode
database, so the OpenCode in the image must be the same version as the host's
(both 2.3.2 today). Docker mode checks this at startup and refuses to run on a
mismatch.

**Project-level agent files.** `FileOps.exportSanitized` copies everything
except VCS and build directories, so agent instruction and config files in the
target repository reach the workspace: `AGENTS.md`, `CLAUDE.md`,
`opencode.json`/`opencode.jsonc`, and `.opencode/`. RoaringBitmap, caffeine,
and wiremock ship `AGENTS.md`; caffeine and wiremock also ship `CLAUDE.md`.
OpenCode reads them as instructions, and a project-level config may take
precedence over the container's permission config (§3.7). The export excludes
these files for every repository; it is a rule about file names, not a
per-repository case. Confirm OpenCode's config precedence, so a file that
appears in the workspace mid-session cannot re-enable tools.

**Runtime downloads.** Under an LLM-only egress policy, anything OpenCode
fetches at runtime fails: lazily installed provider SDK packages, the model
catalog, and update checks. The image installs these in advance, and the
container config disables auto-update (§6).

### 3.7 Agent network policy

Agent containers attach to an internal Docker network with no external route,
plus a small proxy container (tinyproxy or squid) that allows only the LLM
provider's hosts. The agent container sets `HTTPS_PROXY`. An OpenCode config in
the container also denies `webfetch`/`websearch` through `permission`, giving
two layers. The second layer holds only if no project-level config overrides
it, which is why the workspace export drops OpenCode config files (§3.6).

Today `repair.internet_access: false` only sets `PIPELINE_AGENT_NO_NETWORK=1`,
which nothing reads. This makes the setting effective.

### 3.8 Synthetic `.git` in agent workspaces

Implemented in Phase 0 (`Git.initSnapshot`), because agent helpers need it on
the host too.

After the sanitized export, run `git init && git add -A && git commit` in the
workspace, with the identity passed inline (`-c user.name=… -c user.email=…`).
Build scripts that call `git` during configuration then work, and there is
still no history for the agent to inspect.

`exportSanitized` deletes and rebuilds the workspace on every
`prepareWorkspace` and `prepareRepairWorkspace` call (application calls it on
each retry, repair on each refresh), so the synthetic `.git` is recreated
inside those functions, not once per stage. `FileOps.mirrorTreeInto` already
skips `.git`, so it never reaches the worktree, and neither `MIRROR_EXCLUDES`
nor the guards need changes.

### 3.9 Provenance and caching

Record the backend, the image identity, and `runtime_jdk` in `evaluation_runs`
(config snapshot). A locally built image has no registry digest, so its
identity is the image ID (`docker image inspect --format '{{.Id}}'`); a pushed
image is pinned by digest.

Include the same values in the CAS cache key, so host and container results are
never mixed. `CacheKeys.compute` hashes one shared `runtime_environment` map
for every stage, so adding fields there unconditionally would invalidate every
existing bundle, including the expensive LLM stages. Add them only when the
backend is `docker`, the same way `RETIRED_CHECKER_QUALS_KEY` kept old keys
valid. The existing `java_version` field describes the orchestrator's JVM, not
the JDK that runs the build; `runtime_jdk` covers that.

### 3.10 Resources

The Docker Desktop VM currently has 7.7 GiB. The checker fork requests a 4 GB
heap, and the Gradle daemon and OpenCode need more, so `concurrency.builds: 2`
will exhaust memory. Either raise Docker Desktop memory to about 11–12 GB (of
16 GB) or use `builds: 1` in Docker mode.

Agents also run Gradle: `run-checker.sh` runs `checkerPass` inside the agent
container with the same 4 GB fork heap, and `concurrency.builds` does not
govern it. With `concurrency.agents: 8`, agent-side checker runs alone could
request about 32 GB. Limits are therefore set separately for each container
kind (`execution.build_memory`, `execution.agent_memory`, §4), and agent-side
checker runs share a VM-wide limit, either a lower `concurrency.agents` in
Docker mode or a checker semaphore the helper waits on. Every container gets
`--memory` and `--cpus`, so an out-of-memory kill takes down one container, not
the VM. No single limit may exceed the VM's memory.

### 3.11 Lifecycle and cleanup

**Shared infrastructure.** The internal agent network, the egress proxy
container, and the `lltas-gradle-home` volume are created on demand by
`DockerBackend` at pipeline startup and reused across runs and processes.
Startup checks that the Docker daemon is reachable, the image exists and
matches the configured identity, and the OpenCode version matches (§3.6).
If any check fails, the pipeline stops before creating a run, so a missing
image cannot turn into a batch of discarded runs.

**Orphans.** Label every container `lltas.run_id=<id>` and `lltas.owner_pid=<pid>`
(standalone preflight has no run ID). Extend the dead-owner run reconciliation
(commit 327455e) to `docker rm -f` containers whose owner process is dead, and
the Ctrl+C handler (commit 71d9988) to stop the current process's containers.

**Users and ownership.** On Linux hosts, run containers with
`--user $(id -u):$(id -g)` so host-side `git clean -fdx` can remove files the
container created. That uid has no passwd entry in the image, so `HOME` falls
back to `/`; set `HOME` explicitly. Named volumes are created owned by root,
so the image pre-creates the Gradle home directory, and startup sets its
ownership to the user that will write to it. Docker Desktop on macOS maps
ownership automatically.

---

## 4. Files Affected

New:

- `docker/runtime.Dockerfile`: base image, JDKs, git, bash, ripgrep, OpenCode
  pinned to 2.3.2, Checker Framework 3.42.0 at `/opt/checker-framework`,
  non-root user. Built for arm64 and amd64.
- `docker/egress-proxy/`: proxy image and allowlist.
- `src/main/kotlin/pipeline/runners/ExecutionBackend.kt`: `LocalBackend`,
  `DockerBackend`.
- `ExecutionSection` in `src/main/kotlin/pipeline/config/ProjectConfig.kt`:

  ```yaml
  execution:
    backend: "local"            # local | docker
    image: "lltas-runtime:0.1"  # image ID locally; pin by digest once published
    agent_network: "llm_only"   # llm_only | none | full
    build_memory: "6g"          # per build container; must fit the VM (§3.10)
    agent_memory: "6g"          # per agent container, including its checker runs
    cpus: 4
  ```

Modified:

- `src/main/kotlin/pipeline/runners/BuildRunner.kt`: `runGradle` goes through
  the backend; container environment allowlist; harness directory staging.
- `src/main/kotlin/pipeline/runners/AgentRunner.kt`: command construction,
  DB mount, termination through `ExecutionHandle`.
- `src/main/kotlin/pipeline/runners/SingleShotLlmClient.kt`: the triage
  classifier runs through the agent backend.
- `src/main/kotlin/pipeline/runners/JavacRunner.kt`: `--release 8` (Phase 0).
- `src/main/resources/templates/checker.init.gradle`: bugs 2–4 and the
  `init.d` behavior (Phase 0).
- `src/main/kotlin/pipeline/Main.kt`: backend wiring where `GradleBuildRunner`
  and `CoroutineOpenCodeRunner` are constructed; Docker startup checks.
- `src/main/kotlin/pipeline/config/BenchmarkManifest.kt`: `runtime_jdk`.
- `src/main/kotlin/pipeline/stages/ApplicationStage.kt` (`prepareWorkspace`),
  `src/main/kotlin/pipeline/stages/RepairStage.kt` (`prepareRepairWorkspace`,
  `writeRunCheckerScript`), and `src/main/kotlin/pipeline/stages/SynthesisStage.kt`
  (`CHECKER_QUAL_JAR`): container paths with no `../` references (both
  `run-compile.sh` and `run-checker.sh` still reach `../.pipeline-init`), budget
  counter in the session state mount. The synthetic `.git` and the
  compile-only application helper were done in Phase 0.
- `src/main/kotlin/pipeline/stages/SynthesisStage.kt`: reject qualifier
  packages that are also packages of the project (§7.6).
- `src/main/kotlin/pipeline/util/Support.kt` (`exportSanitized`): exclude
  agent instruction and config files (§3.6).
- `src/main/kotlin/pipeline/cas/CasEngine.kt` (`CacheKeys`): backend, image
  identity, and `runtime_jdk` added only in Docker mode (§3.9).
- `src/main/resources/templates/prompts/application.md`: remove the
  contradictory classpath and verification instructions.
- `src/main/resources/templates/prompts/synthesis.md`: the agent's own
  qualifier compile check uses `javac --release 8 -proc:none`, matching
  `JavacRunner`.
- Run reconciliation and interrupt handling: container cleanup by owner PID.

Preflight (§7), new:

- `src/main/kotlin/pipeline/stages/PreflightStage.kt`: checks P1–P6 and
  reason-code classification.
- `src/main/resources/templates/preflight/`: the fixed probe lattice sources
  (`ProbeTop`, `ProbeBottom`) and the probe snippets.
- `src/main/resources/templates/preflight/`: also the Gradle probe that reports
  each subproject's source level, release, and toolchain (P1).
- `preflight_results` table in `src/main/kotlin/pipeline/db/Schema.kt`.
- `pipeline preflight` subcommand in `src/main/kotlin/pipeline/Main.kt`.

Preflight (§7), modified:

- `src/main/kotlin/pipeline/stages/Domain.kt`: `StageName.PREFLIGHT`.
- `src/main/kotlin/pipeline/PipelineOrchestrator.kt`: run preflight between
  ingestion and triage; the hard-coded step numbers passed to `stageOrDiscard`
  and `requireStage` shift by one.
- `src/main/kotlin/pipeline/Main.kt`: `--stop-after preflight`, and an
  `unsupported` outcome in `lifecycleStatus`.
- `analysis/viewer.py`: the preflight stage and the `unsupported` status.
- `benchmarks.yaml`: `expected_preflight` per entry (§7.4). `expected_fit`
  predicts synthesis fit, not whether the build integration works.

Corpus measurement (§7.4), new:

- A corpus tool that applies the fixed selection rule, clones the sample into
  mirrors, and generates manifest entries (source roots, subproject, the test
  class P6 runs) for `pipeline preflight`.

---

## 5. Phases

| Phase | Work | Exit criteria | Estimate |
|---|---|---|---|
| **0. Harness fixes (no Docker)** | Bug 1 as a fixed `--release 8` (§7.5); bug 2 as the module-project design (§7.6); bug 3; bug 4 as the clean `checkerPass` configuration (§7.5); qualifier-package check in synthesis; quarantine the agent-written `init.d` script; compile-only application helper and prompt fix; synthetic `.git` (moved from Phase 2); agent instruction files excluded from the export; init script hashed into build-stage cache keys | The JSqlParser and Agrona probe files pass on the host, and so does every other eval repo with the same triggering condition (see "Requirement: Fixes Must Be General"): the module fix on JSqlParser, RoaringBitmap, caffeine, and picocli (including its `src/main/java9` descriptor and JPMS test subprojects). `synthetic-id-swap` and `cmsx` verdicts unchanged after the `init.d` script is removed | 2–3 days |
| **0.5 Preflight (no Docker)** | Preflight stage, reason codes, `preflight_results` table, `pipeline preflight` CLI (§7.3); run on the 12 eval repos | Every eval repo ends in `pass` or a specific reason code, never `unknown`; results match the known issues in §1 | 2–3 days |
| **1. Builds in a container** | Runtime image, `ExecutionBackend` with `ExecutionHandle`, `--init`, Gradle volume, narrow same-path mounts and harness directory, environment allowlist, `runtime_jdk`, image identity recorded, Docker startup checks | synthetic-id-swap and cmsx give the same verdicts on `local` and `docker`, with the host `JAVA_HOME` pinned to the image's JDK 21 for the local runs; preflight results on the 12 eval repos are identical on both backends except for the listed host-state differences (§1); JSqlParser checker time measured on both | 2–3 days |
| **2. Agents in a container** | OpenCode in the container (all agent stages and the triage classifier), `/workspace` and `/harness` mounts, helper scripts on container paths, offline Gradle for agents, egress proxy, OpenCode permission config, OpenCode version check, runtime downloads baked into the image | Containment canary test passes (below) | 3–4 days |
| **3. Hardening** | Per-kind resource limits, labels and orphan cleanup, Ctrl+C handling, Docker-mode-only cache-key fields, Linux user and volume ownership, Docker-optional tests, README | A run killed with Ctrl+C leaves no containers; `./gradlew test` passes on a machine without Docker; existing local CAS bundles still hit after the cache-key change | 1–2 days |
| **4. Corpus measurement** | Corpus tool (§4), sample selection, preflight on the sample, report (§7.4) | Selection rule fixed before running; report with pass rates per stratum and the reason-code distribution | 2–3 days |

Phase 0.5 can run in parallel with Phase 0. Its probe checks reproduce bugs 1–4
automatically, so they also serve as the regression tests for the Phase 0 fixes.

### Phase 0 status (2026-11-23)

Implemented on branch `phase0-harness-fixes`; the decisions behind each change
are recorded in `deviations.md` items 57–63. Verified on the host with JDK 21
running Gradle, using scripted versions of preflight checks P3–P5 (fixed
`ProbeTop`/`ProbeBottom` lattice compiled with `--release 8`; a probe-annotated
file in one main and one test package; `checkerPass` on that tree, then with
one planted ill-typed return):

| Repository (parent commit) | Trigger | P3 compile | P4 probe | P5 checker clean / planted |
|---|---|---|---|---|
| synthetic-id-swap `56aa665a` | none | pass | pass | 0 errors / exactly 1 |
| cmsx `7ee122ff` | AutoValue, custom roots | pass | pass | 0 / exactly 1 |
| JSqlParser `84f780cd` | JDK 17 toolchain, `module-info`, JavaCC | pass | pass | 0 / exactly 1 |
| Agrona `21abef93` | `-Xlint:all -Werror`, `--release` in `compilerArgs` | pass | pass | 0 / exactly 1 |
| RoaringBitmap `2863e96d` | `module-info` (moduleplugin), immutable `compilerArgs` | pass | pass | 0 / exactly 1 |
| picocli `10509c0a` | Error Prone, `java9` descriptor | pass | pass | 0 / exactly 1 |
| picocli JPMS test build | named modules | compiles; `module-info.class` kept | — | — |
| caffeine | JDK 26 toolchain | not run: no JDK 26 (Phase 1) | | |

With the old template, the same probes fail as described in the Background
(bugs 2 and 3 on a minimal module project, bug 1 with a version-65 jar), and
RoaringBitmap fails while creating `checkerPass`. That last failure was not in
the original list.

Not yet done for the Phase 0 exit: re-running `synthetic-id-swap` and `cmsx`
through the full pipeline on the host to compare verdicts now that the
agent-written `init.d` script is gone (this runs LLM agents), and a full
pipeline run of JSqlParser and Agrona.

### Containment canary test

Modeled on `BlindnessCanaryTest`. A scripted stand-in for the agent runs in an
agent container and attempts to:

- list `/Users` and read files under the host home directory,
- `curl https://github.com`,
- write to the host `/tmp`,
- read the Git mirror under `eval-repos/`,
- read `.env`, `data/pipeline.db`, or the CAS under `.cache/`,
- reach a Docker socket,
- write to the read-only Gradle cache or `/harness`.

Every attempt must fail while a request to the LLM provider succeeds.

A second canary runs the same filesystem attempts from test code inside a
build container, because verification executes agent-written code there. Its
attempts on `.env`, `data/`, `.cache/`, and paths outside the mounted worktree
must fail too.

### Testing approach

- Unit tests for `DockerBackend` command construction (a pure function from
  invocation to `docker` arguments).
- Integration tests tagged and skipped when `docker` is unavailable.
- Parity runs (Phase 1 exit) compare verdicts, checker error counts, and test
  outcomes between backends.

---

## 6. Risks to Investigate First

These are Phase 1 spikes; each can change the design.

- **Bind-mount performance on macOS.** Gradle writes heavily to `build/` and
  `.gradle/`, and VirtioFS can be markedly slower. Measure JSqlParser checker
  time first (about 4m45s on the host). If much worse, place build output
  directories on container-local volumes.
- **OpenCode and `HTTPS_PROXY`.** Network containment depends on OpenCode
  honoring the proxy and on knowing which hosts `opencode-go/*` calls. Fallback:
  `--network none` for agent containers, with LLM traffic through a host-side
  relay.
- **OpenCode runtime downloads and config precedence.** Find out what OpenCode
  fetches after startup (provider SDK packages, the model catalog, update
  checks) so the image can include it, and whether a project-level
  `opencode.json` overrides the container's permission config (§3.6).
- **Ownership on bind mounts.** Check which uid the container sees for
  bind-mounted files on Docker Desktop and on Linux, and confirm that git in the
  container works on worktrees and the mirror with `safe.directory` set.
- **arm64.** The development machine is arm64; the OpenCode binary and JDK
  builds in the image must be arm64 (and amd64 for Linux servers).

---

## 7. Generality and Preflight

Docker makes the environment the same on every run. It does not make the
pipeline work on every Java repository. Whether a repository works is decided by
the build integration: `checker.init.gradle`, the `checkerPass` task, the
qualifier jar, and the classpaths. Bugs 1–4 all came from that layer.

Generality is therefore handled in three parts:

1. An explicit supported envelope (§7.2).
2. A preflight stage that enforces it cheaply, before any LLM call (§7.3).
3. A corpus measurement that shows how much of the Java ecosystem the envelope
   covers (§7.4).

### 7.1 Variation Already Present in the Eval Repos

Survey of the 12 repositories under `eval-repos/` (2026-11-23):

| Variation | Repositories | Covered by this plan? |
|---|---|---|
| Gradle 8.5 to 9.9.1 | all | Partly. The init script uses Gradle APIs that need roughly 6.4 or later. |
| Java toolchains 17, 21, 25, 26 | JSqlParser, cmsx, caffeine (26), reactor-core | Partly. Checker Framework 3.42 runs on JDKs up to 21, so Java 25+ sources cannot be checked. The toolchain JDK and the source level differ: JSqlParser compiles source level 11 on a JDK 17 toolchain, caffeine source level 11 on JDK 26. |
| `module-info.java` | JSqlParser, RoaringBitmap, caffeine (4 files), picocli (8, one in `src/main/java9`) | Bug 2, fixed in Phase 0 (§7.6). |
| `-Werror` | agrona | Bugs 3 and 4, fixed in Phase 0. |
| Error Prone compiler plugin | caffeine, jobrunr, picocli, reactor-core, wiremock | Not yet. Error Prone configures every `JavaCompile` task, so `checkerPass` probably inherits `-Xplugin:ErrorProne` the same way bug 4 inherits `-Werror`. Untested. |
| Kotlin plugin | caffeine, jobrunr, picocli, resilience4j | Unknown. Java-to-Kotlin references should resolve through `main.output`; untested. |
| Gradle configuration cache | caffeine | Unknown. The init script reads environment variables during configuration. |
| Testcontainers | jobrunr | In conflict. These tests need the Docker socket, which defeats container isolation. |

These are 12 hand-picked repositories. Even after the Phase 0 fixes, 6 of them
(caffeine, jobrunr, picocli, reactor-core, resilience4j, wiremock) still have at
least one feature that is unhandled or untested. A random sample will be more
varied.

### 7.2 Supported Envelope (Proposed)

In scope:

- Gradle 7.0 or later, with a wrapper and the `java` or `java-library` plugin.
  The 7.0 floor is a conservative choice; the corpus measurement (§7.4) can
  lower it.
- Java source level 8 through 21.
- Annotation processors other than Lombok and MapStruct.
- Projects that also contain Kotlin, as long as the Java sources compile on
  their own in `checkerPass`. To be confirmed by preflight.
- Tests that run under Gradle's `Test` task, write JUnit XML, and need no Docker,
  external services, or display.

Out of scope:

- Maven-only, Ant, Bazel, and sbt builds. `BuildRunner` is a port, so a Maven
  adapter is a possible later extension.
- Android projects (`com.android.*` plugins).
- Java 22+ sources, until Checker Framework is upgraded.
- Lombok and MapStruct (rejected by the init script by design).
- Tests that depend on Testcontainers or a Docker socket.
- Native dependencies without a Linux arm64 build, when running on arm64 hosts.
- Builds that need artifacts available only in the host's local Maven
  repository (`mavenLocal()`), which a clean environment cannot produce.

A repository outside the envelope is not a pipeline failure. Preflight records
it as unsupported with a reason, and it is excluded from evaluation metrics.

### 7.3 Preflight Stage

Preflight automates the probe files used to diagnose bugs 1–4. It runs after
ingestion and before triage, so an unsupported repository costs a few build
minutes and no LLM tokens. It runs on either execution backend.

Checks, in order. The first failure ends preflight:

| Check | What it does | Typical reason codes |
|---|---|---|
| P1 Environment | Static checks: wrapper present, Gradle version parsed, no Lombok/MapStruct, no `com.android.*`. Then a short Gradle probe (an init script that only reports, no compile) gives each subproject's source level, release, and toolchain; check that the runtime and toolchain JDKs are available and the source level is ≤ 21 | `build_tool_unsupported`, `gradle_too_old`, `jdk_unavailable`, `source_level_unsupported`, `processor_unsupported` |
| P2 Baseline compile | `compileJava` and `compileTestJava` at C-1 with no init script | `baseline_compile_failed` |
| P3 Integration compile | The same tasks with the init script and a fixed probe lattice (`ProbeTop` / `ProbeBottom`, jar built with `--release 8`, as in production) | `qualifier_jar_version`, `module_mode`, `werror_interaction`, `foreign_compiler_plugin` |
| P4 Probe annotation | In a scratch copy, add one `@ProbeTop` to a field or parameter in one main file and one test file; compile main and test | `qualifier_not_on_classpath` |
| P5 Checker run | Run `checkerPass` twice: on the probe-annotated tree (must finish without a crash, with the checker invoked), then with one deliberately ill-typed assignment (must report exactly one error at the expected location) | `checker_crash`, `checker_silent`, `diagnostics_unparsed` |
| P6 Test harness | Run one test class with the probe jar on the classpath; JUnit XML must appear | `test_xml_missing`, `test_requires_docker` |

Any check can also end in `timeout`. A failure that matches no known pattern is
`unknown`, and the raw log tail is kept.

The source level comes from the build, never from configuration defaults.
Entries without `source_compatibility` fall back to the global `"17"` in
`project_config.yaml` (`BenchmarkManifest.kt`; the crawler manifest does the
same), and corpus repositories have no entries at all. The P1 probe's values
feed `checkerPass` (§7.5); a `benchmarks.yaml` value that disagrees with the
probe is reported rather than silently used.

The second half of P5 matters most. It confirms the checker actually reports
errors, which protects against the bug 4 failure mode where the checker fails
with no file-level errors and the run is scored as zero errors.

Outputs:

- A `preflight_results` row: status, failed check, reason code, Gradle version,
  runtime and toolchain JDKs, source level from the P1 probe, backend and image
  identity, duration, log path.
- Runs that fail preflight end with lifecycle status `unsupported`, and the
  reason code is stored with them. This is a new lifecycle status: `Main.kt`,
  the orchestrator, and the viewer need it (§4).

Caching: the preflight result depends on the build configuration, not on every
source change. Cache it on a hash of every file outside the production and test
source roots, plus the source file of the test class P6 runs, the init script,
the probe lattice, and the image identity. A fixed list of build-file names
misses build logic in included builds: caffeine's
`includeBuild("gradle/plugins")` holds Kotlin sources under
`gradle/plugins/src/main/kotlin`. In crawler mode, adjacent commits usually
share a result.

Standalone use: `pipeline preflight --manifest repos.yaml` runs preflight
without the rest of the pipeline. The corpus measurement uses this, with
manifest entries generated by the corpus tool (§4).

### 7.4 Corpus Measurement

The generality claim in the project write-up is a measured pass rate, not an
argument.

1. **Eval repos.** Run preflight on the 12 eval repos. Record the expected
   result for each in advance in a new `expected_preflight` field in
   `benchmarks.yaml`, recorded the same way as `expected_fit`, then compare.
   `expected_fit` itself predicts synthesis fit, not build integration.
2. **Sample corpus.** 50–100 Gradle Java repositories from GitHub, stratified
   by Gradle major version (7/8/9), Java level (8/11/17/21), and features
   (`module-info`, Error Prone, Kotlin, `-Werror`). Fix the selection rule
   (search criteria and random seed) before running, so the sample is not
   cherry-picked.
3. **Report.** Overall pass rate, pass rate per stratum, and the distribution
   of reason codes. The largest reason codes are the next engineering targets.
4. **Regression gate.** Re-run preflight on the eval repos whenever
   `checker.init.gradle`, `JavacRunner`, or the runtime image changes. A change
   that makes a previously passing repository fail is not merged.

### 7.5 Design Changes That Widen the Envelope

- **Run the checker on its own pinned JDK.** Configure `checkerPass` with a
  Gradle toolchain for JDK 21 and `options.release = <project source level>`,
  taken from the P1 probe (§7.3), not from `CF_SOURCE_COMPATIBILITY`, which
  today carries the configuration default.
  The checker is then independent of the JDK the project builds with. This
  fixes JDK 8 projects (whose JDK rejects the init script's `--add-exports`
  flags) and projects that build on JDK 25 while their sources are 21 or lower.
- **Give `checkerPass` a clean compiler configuration.** Rather than inheriting
  the project's `JavaCompile` settings and undoing them one at a time (`-Werror`,
  `-Xlint`, Error Prone), a `doFirst` action keeps an allowlist of flags that
  decide whether the sources compile (`--release`, `--add-exports`,
  `--add-modules`, `--limit-modules`, `--enable-preview`, `-encoding`, boot
  class path options, `-parameters`) and drops everything else with its value.
  It keeps only the harness's own argument providers and disables Error Prone
  when its extension is present. The plan first said "exactly the checker
  flags", but Agrona passes `--release` through `compilerArgs` and projects
  that use JDK-internal APIs need their `--add-exports`. Unknown flags are
  dropped, because a missing flag fails visibly while an unknown kept flag could
  change the diagnostics silently. The checker's own flags come from a
  `CommandLineArgumentProvider`, because projects may assign an immutable list
  to `compilerArgs` (RoaringBitmap), on which `addAll` throws while the task is
  created. This is the bug 4 fix; picocli exercises it with Error Prone.
- **Compile the qualifier jar with a fixed `--release 8`.** This is the bug 1
  fix; P3 verifies it on every repository. `--release N` sets the accepted
  syntax, the class-file version, and the standard library compiled against,
  and it applies only to the qualifier sources `JavacRunner` compiles, never to
  the project. Newer JDKs read older class files, so a Java 8 jar (class-file
  version 52) is readable by every JDK in the envelope (8–21): a Java 21 project
  using records, sealed types, and pattern `switch` compiles against it at
  `--release 21`. `--release <target_compatibility>` would also work, but only
  while the configured value is correct; a Java 11 project with no
  `benchmarks.yaml` entry would get the default `--release 17` and fail like
  JSqlParser. The one constraint is that qualifier sources use only Java 8
  features. That is no real restriction, since `@Target(TYPE_USE)` arrived in
  Java 8, and a violation is an ordinary compile error that reaches the
  synthesis retry feedback. JDK 21 and JDK 27 javac both still accept
  `--release 8` but warn that it will be removed, so the pipeline's JDK stays
  pinned.

### 7.6 Module Projects (Bug 2)

The fix keeps `module-info.java` in the project's own compile. The harness
gives the module read access to the harness jars with javac flags, much as
`requires static` would, without editing any project file; caffeine's own
`module-info.java` uses `requires static` for its annotation modules. Only
`checkerPass` compiles without the descriptor.

1. **Keep the descriptor.** Remove `main.java.exclude('**/module-info.java')`
   from the main source set.
2. **Read the harness jars.** For every `JavaCompile` task except `checkerPass`
   whose sources include `module-info.java`, read the module name from that
   file and pass:
   - `--add-reads <module>=ALL-UNNAMED`, which covers harness jars on the
     classpath, such as the qualifier jar;
   - for harness jars that declare a module name, `--add-modules <name>` and
     the name in the same `--add-reads` list. Gradle puts such a jar on the
     `--module-path`, where `ALL-UNNAMED` does not reach it. checker-qual
     declares `Automatic-Module-Name: org.checkerframework.checker.qual`, so
     without this step bug 3 returns in module projects;
   - `-Xlint:-exports`. A qualifier on an exported API makes javac warn
     "class ProbeTop in module  is not exported", which `-Xlint:all -Werror`
     builds fail on. The warning exists only because of the harness jars. This
     case was missing from the first fixture, which had no annotated public
     API.

   The flags come from a `CommandLineArgumentProvider` evaluated when the task
   runs, like the existing `CheckerQualsArgumentProvider`, so plugins that
   configure every `JavaCompile` task early (JavaCC) cannot trigger it at
   configuration time. A task without a descriptor gets no flags. The rule
   depends only on each task's own sources, so picocli's `src/main/java9`
   descriptor and its JPMS test subprojects need no special case. A harness jar
   that is an explicit module (contains `module-info.class`) is rejected with a
   clear error.
3. **`checkerPass` alone compiles in classpath mode.** Exclude
   `module-info.java` from that task's sources only, clear its `-sourcepath`
   so javac cannot rediscover the descriptor, and keep module-path inference
   off. Module boundaries do not affect type checking, and the task's output is
   discarded.
4. **Qualifier packages stay outside the project's packages.** A qualifier
   placed in a package the module owns stays invisible even with
   `--add-reads`, because a package belongs to one module only. Synthesis
   rejects qualifier packages that are also packages of the project, found by
   scanning the project's `package` declarations, and the rejection goes back
   to the agent as retry feedback.

Checked on a minimal module project with Gradle 9.8 and JDK 21, using the real
init script and a version with these changes:

| Case | Current init script | Proposed |
|---|---|---|
| Module project, qualifiers on fields | `package lltas.probe is not visible` | builds; `module-info.class` kept |
| Plus `package-info.java` importing checker-qual, under `-Xlint:all -Werror` | bug 3 warning, `not visible` errors | builds |
| `module-info` already has `requires static org.checkerframework.checker.qual` | not run | builds |
| Same, plus the project's own copy of checker-qual | not run | builds |
| `checkerPass` with one planted ill-typed assignment | fails at `compileJava` | exactly one `[assignment]` error at the planted line |
| Same project without `module-info.java` | not run | builds; no flags added |

Phase 0 verified this on JSqlParser, RoaringBitmap, and picocli (including
its JPMS test build); see "Phase 0 status" in §5. Still not verified:

- **Test runtime in module mode.** RoaringBitmap's tests run on the classpath
  and pass with the annotated tree; picocli's JPMS test build compiles, but its
  `test` task finds no tests at baseline (no `useJUnitPlatform()`). Tests that
  run on the module path and reflect on the annotations would need the same
  flags as JVM arguments on `Test` tasks.
- **caffeine.** Its runtime toolchain is `max(javaVersion, 26)` unless it builds
  on GraalVM, and no JDK 26 is installed; it waits for the Phase 1 image.
- **Version mismatch.** A project that ships a different checker-qual version
  than the harness; the test used the same jar twice.

---

## 8. Open Decisions

1. **Agent network.** Recommended: LLM provider only. Alternative: no network
   at all when `repair.internet_access: false`.
2. **Ordering.** Recommended: complete Phase 0 on the host before any Docker
   work, so container failures are unambiguous.
3. **Default backend.** Recommended: make `docker` the default after Phase 1
   parity passes, keeping `local` for debugging.
4. **Supported envelope.** Confirm §7.2, in particular the Gradle 7.0 floor and
   excluding repositories whose tests need Testcontainers.
5. **Corpus size.** 50 repositories is enough to rank failure reasons; 100
   gives per-stratum pass rates with usable confidence intervals.
6. **Network for verification builds.** Verification runs agent-written code
   with the build container's bridge network. Recommended: keep the bridge,
   since the narrow mounts (§3.2) already protect the guards and database.
   Alternative: run verification `--offline` once dependencies are warmed.
   That adds a failure mode for test suites that download at test time, so it
   would need a P6 reason code (`test_requires_network`) and a corresponding
   §7.2 exclusion, not an assumption.
