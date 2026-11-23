# Dockerization Plan: Containerized Builds and Agent Sessions

Status: proposed (2026-11-23). Nothing in this plan is implemented yet.

This plan runs the pipeline's builds and agent sessions inside controlled Docker
containers so each target project builds in a known, pinned environment and
agents cannot reach the host. It follows from the JSqlParser and Agrona runs,
which exposed harness bugs, host-state dependencies, and an agent sandbox that
does not contain the agent.

---

## 1. What Docker Fixes and What It Does Not

Docker fixes the environment problems. It does not fix the four known harness
bugs: they are javac and Gradle configuration mistakes that fail the same way
inside a container. They are fixed first, on the host (Phase 0), so that a
failure seen inside Docker can only come from Docker.

| Issue | Fixed by Docker? | Actual fix |
|---|---|---|
| 1. Qualifier jar compiled for the pipeline's Java 21 (`bad class file … wrong version 65.0`) | No | `JavacRunner` runs inside the pipeline's JVM; pass `--release <target_compatibility>`. Docker does remove the related risk of an unset `JAVA_HOME` selecting JDK 27. |
| 2. Gradle module inference makes classpath jars unreadable when `module-info.java` exists | No | Fix in `checker.init.gradle`. |
| 3. `checker-qual.jar` missing from the main compile classpath (breaks `-Werror` projects) | No | One line in `checker.init.gradle`. |
| 4. `checkerPass` inherits project compiler flags (`-Werror`, `-Xlint`) | No | Clear inherited `compilerArgs` in `checker.init.gradle`. |
| `run-compile.sh` fails because the agent workspace has no `.git` | Partly | Synthetic one-commit `.git` in the workspace (§3.8); helper must also use the init script. |
| Application prompt contradicts its tooling | No | Give the application agent a run-checker helper; fix the prompt. |
| Agent not contained (filesystem search, `/tmp` writes, web requests, reads under `$HOME`) | **Yes** | Workspace-only mounts and an egress allowlist (§3.6, §3.7). |
| Caffeine pins JDK 26; Gradle would auto-download it | **Yes** | Pinned JDKs in the image, toolchain auto-download disabled, clear discard when a JDK is missing. |
| Laptop sleep stalls runs | No | The Docker VM pauses on sleep too. Keep using `caffeinate -i`. |
| Shared checkout, `pipeline.log`, and database | No | Unchanged; filter by run ID. |

### Hidden host state

`~/.gradle/init.d/zz-cf-compile-init.gradle` exists on the development machine.
Gradle applies it to every build on the host: it adds `CUSTOM_QUAL_JAR_PATH` to
`compileJava`/`compileTestJava` and rewrites `JavaCompile` arguments. Containers
will not have it, so host and container runs would diverge. Phase 0 moves its
behavior into `src/main/resources/templates/checker.init.gradle`, or deletes it
if it is obsolete.

---

## 2. Architecture

The orchestrator stays on the host. Every untrusted or environment-sensitive
subprocess runs in a container.

```
HOST (trusted): PipelineOrchestrator, Git mirrors/worktrees, guards (AST/FS/diff),
                CAS, SQLite, JavacRunner (with --release), logs/viewer
   │ docker run --rm (one container per invocation, labelled lltas.run_id=…)
   ├── BUILD container : harness Gradle compile / test / checkerPass
   │                     network: bridge (Maven Central, Gradle plugin portal)
   └── AGENT container : opencode run … (one per agent session)
                         mounts: workspace (rw), Checker Framework (ro), Gradle cache (ro)
                         network: LLM provider only (proxy), or none
```

Rationale:

- The guards, database, and CAS decide whether an agent cheated, so they stay
  outside anything the agent can touch.
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
- `start(...): Process` returning a streaming process for the agent's JSON
  event stream.

Implementations:

- `LocalBackend` wraps today's `Processes.run` and `ProcessBuilder` paths.
  Behavior is unchanged.
- `DockerBackend` invokes the `docker` CLI directly. No docker-java dependency.

Configuration `execution.backend: local | docker` keeps the local path available
for comparison runs, consistent with the project's ablation principle.

### 3.2 Same-path mounts

Host paths are mounted at identical paths in the container
(`-v /Users/…/project:/Users/…/project`). Worktrees under `.worktrees/` contain
`.git` files pointing to absolute paths in `eval-repos/<name>.git`;
`CUSTOM_QUAL_JAR_PATH`, the init-script path, and file paths in checker
diagnostics are also absolute. Identical paths mean nothing needs translating.
The mirror is mounted read-only so build scripts that call `git` (JSqlParser)
work in harness builds.

In Docker mode, set `PIPELINE_SCRATCH_DIR` to a known path instead of relying
on `java.io.tmpdir` (`/var/folders/…` on macOS).

### 3.3 One container per invocation

Use `docker run --rm --name <unique>` per Gradle invocation and per agent
session, not `docker exec` into a long-lived container. Killing a `docker exec`
client does not kill the process inside the container. With one container per
invocation, a timeout is `docker rm -f <name>`, which replaces `terminateTree`
and stops leaked Gradle daemons.

Cost: a cold Gradle JVM each time, roughly 5–15 s, small next to a checker run
of about 5 minutes on JSqlParser. Daemon reuse is a later optimization.

### 3.4 Gradle home in a named volume

Harness builds use a named volume `lltas-gradle-home` as `GRADLE_USER_HOME`,
not a bind mount of `~/.gradle`. This keeps the host `init.d` out, avoids slow
file locking over VirtioFS, and lets concurrent containers share the cache
safely (all containers run in the same VM).

Agent containers get the cache read-only through `GRADLE_RO_DEP_CACHE`, a
private writable `GRADLE_USER_HOME`, and `--offline`. Wrapper distributions are
not covered by the read-only cache, so `wrapper/dists` is copied or mounted
separately. Dependencies are warmed by harness builds (ingestion and triage)
before any agent runs.

### 3.5 JDK selection

One runtime image contains Temurin 8, 11, 17, and 21 (plus 25/26 if Caffeine is
in scope), with `org.gradle.java.installations.paths=…` and
`org.gradle.java.installations.auto-download=false`. A missing toolchain fails
fast and is recorded as a discard rather than triggering a download.

New benchmark field `runtime_jdk` in `benchmarks.yaml`: the JDK that runs
Gradle. Old Gradle wrappers cannot run on JDK 21. A later improvement selects it
automatically from the Gradle version in `gradle/wrapper/gradle-wrapper.properties`.

### 3.6 OpenCode inside the agent container

OpenCode's tools (bash, read, edit) execute in OpenCode's own process, so
containing them requires running OpenCode in the container. The host still
reads its stdout and parses events as today; only the command built in
`src/main/kotlin/pipeline/runners/AgentRunner.kt` changes. The `PWD` workaround
becomes `-w /workspace`.

The per-session `OPENCODE_DB` is placed in a host directory mounted into the
container, so reasoning reconciliation can read it after the session exits.

Known limitation: that DB contains the provider credential rows, and the agent
can read it. This is no worse than today.

### 3.7 Agent network policy

Agent containers attach to an internal Docker network with no external route,
plus a small proxy container (tinyproxy or squid) that allows only the LLM
provider's hosts. The agent container sets `HTTPS_PROXY`. An OpenCode config in
the container also denies `webfetch`/`websearch` through `permission`, giving
two layers.

Today `repair.internet_access: false` only sets `PIPELINE_AGENT_NO_NETWORK=1`,
which nothing reads. This makes the setting effective.

### 3.8 Synthetic `.git` in agent workspaces

After the sanitized export, run `git init && git add -A && git commit` in the
workspace. Build scripts that call `git` during configuration then work, and
there is still no history for the agent to inspect. The synthetic `.git` is
added to `MIRROR_EXCLUDES` and to the guards' ignore lists.

### 3.9 Provenance and caching

Record the backend and the image digest in `evaluation_runs` (config snapshot).
Include both in the CAS cache key for stages that run builds or agents, so host
and container results are never mixed in the cache.

### 3.10 Resources

The Docker Desktop VM currently has 7.7 GiB. The checker fork requests a 4 GB
heap, and the Gradle daemon and OpenCode need more, so `concurrency.builds: 2`
will exhaust memory. Either raise Docker Desktop memory to about 11–12 GB (of
16 GB) or use `builds: 1` in Docker mode. Every container gets `--memory` and
`--cpus`, so an out-of-memory kill takes down one container, not the VM.

### 3.11 Cleanup

Label every container `lltas.run_id=<id>`. Extend the dead-owner run
reconciliation (commit 327455e) to `docker rm -f` orphaned containers, and the
Ctrl+C handler (commit 71d9988) to stop the current run's containers.

On Linux hosts, run containers with `--user $(id -u):$(id -g)` so host-side
`git clean -fdx` can remove files the container created. Docker Desktop on
macOS maps ownership automatically.

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
    image: "lltas-runtime:0.1"  # pin by digest once published
    agent_network: "llm_only"   # llm_only | none | full
    memory: "10g"
    cpus: 4
  ```

Modified:

- `src/main/kotlin/pipeline/runners/BuildRunner.kt`: `runGradle` goes through
  the backend.
- `src/main/kotlin/pipeline/runners/AgentRunner.kt`: command construction,
  DB mount, termination.
- `src/main/kotlin/pipeline/runners/JavacRunner.kt`: `--release` (Phase 0).
- `src/main/resources/templates/checker.init.gradle`: bugs 2–4 and the
  `init.d` behavior (Phase 0).
- `src/main/kotlin/pipeline/Main.kt`: backend wiring where `GradleBuildRunner`
  and `CoroutineOpenCodeRunner` are constructed.
- `src/main/kotlin/pipeline/config/BenchmarkManifest.kt`: `runtime_jdk`.
- `src/main/kotlin/pipeline/stages/ApplicationStage.kt` (`prepareWorkspace`)
  and `src/main/kotlin/pipeline/stages/RepairStage.kt`
  (`prepareRepairWorkspace`, `writeRunCheckerScript`): container paths for
  `CHECKERFRAMEWORK_HOME`, synthetic `.git`, run-checker helper for application.
- `src/main/resources/templates/prompts/application.md`: remove the
  contradictory classpath and verification instructions.
- Run reconciliation and interrupt handling: container cleanup.

Preflight (§7), new:

- `src/main/kotlin/pipeline/stages/PreflightStage.kt`: checks P1–P6 and
  reason-code classification.
- `src/main/resources/templates/preflight/`: the fixed probe lattice sources
  (`ProbeTop`, `ProbeBottom`) and the probe snippets.
- `preflight_results` table in `src/main/kotlin/pipeline/db/Schema.kt`.
- `pipeline preflight` subcommand in `src/main/kotlin/pipeline/Main.kt`.

---

## 5. Phases

| Phase | Work | Exit criteria | Estimate |
|---|---|---|---|
| **0. Harness fixes (no Docker)** | Bugs 1–4 (bug 4 implemented as the clean `checkerPass` configuration in §7.5); move or remove the `init.d` script; application run-checker helper and prompt fix | The JSqlParser and Agrona probe files pass on the host | 1–2 days |
| **0.5 Preflight (no Docker)** | Preflight stage, reason codes, `preflight_results` table, `pipeline preflight` CLI (§7.3); run on the 12 eval repos | Every eval repo ends in `pass` or a specific reason code, never `unknown`; results match the known issues in §1 | 2–3 days |
| **1. Builds in a container** | Runtime image, `ExecutionBackend`, Gradle volume, same-path mounts, `runtime_jdk`, image digest recorded | synthetic-id-swap and cmsx give the same verdicts on `local` and `docker`; preflight results on the 12 eval repos are identical on both backends; JSqlParser checker time measured on both | 2–3 days |
| **2. Agents in a container** | OpenCode in the container, workspace-only mounts, synthetic `.git`, offline Gradle for agents, egress proxy, OpenCode permission config | Containment canary test passes (below) | 3–4 days |
| **3. Hardening** | Resource limits, labels and orphan cleanup, Ctrl+C handling, cache-key change, Docker-optional tests, README | A run killed with Ctrl+C leaves no containers; `./gradlew test` passes on a machine without Docker | 1–2 days |

Phase 0.5 can run in parallel with Phase 0. Its probe checks reproduce bugs 1–4
automatically, so they also serve as the regression tests for the Phase 0 fixes.

### Containment canary test

Modeled on `BlindnessCanaryTest`. A scripted stand-in for the agent runs in an
agent container and attempts to:

- list `/Users` and read files under the host home directory,
- `curl https://github.com`,
- write to the host `/tmp`,
- read the Git mirror under `eval-repos/`.

Every attempt must fail while a request to the LLM provider succeeds.

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
| Java toolchains 17, 21, 25 | JSqlParser, cmsx, caffeine, reactor-core | Partly. Checker Framework 3.42 runs on JDKs up to 21, so Java 25 sources cannot be checked. |
| `module-info.java` | JSqlParser, RoaringBitmap, caffeine (4 files), picocli (8) | Bug 2, fixed in Phase 0. |
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

A repository outside the envelope is not a pipeline failure. Preflight records
it as unsupported with a reason, and it is excluded from evaluation metrics.

### 7.3 Preflight Stage

Preflight automates the probe files used to diagnose bugs 1–4. It runs after
ingestion and before triage, so an unsupported repository costs a few build
minutes and no LLM tokens. It runs on either execution backend.

Checks, in order. The first failure ends preflight:

| Check | What it does | Typical reason codes |
|---|---|---|
| P1 Environment | Static checks, no build: wrapper present, Gradle version parsed, runtime and toolchain JDKs available, source level ≤ 21, no Lombok/MapStruct, no `com.android.*` | `build_tool_unsupported`, `gradle_too_old`, `jdk_unavailable`, `source_level_unsupported`, `processor_unsupported` |
| P2 Baseline compile | `compileJava` and `compileTestJava` at C-1 with no init script | `baseline_compile_failed` |
| P3 Integration compile | The same tasks with the init script and a fixed probe lattice (`ProbeTop` / `ProbeBottom`, jar built with `--release N`) | `qualifier_jar_version`, `module_mode`, `werror_interaction`, `foreign_compiler_plugin` |
| P4 Probe annotation | In a scratch copy, add one `@ProbeTop` to a field or parameter in one main file and one test file; compile main and test | `qualifier_not_on_classpath` |
| P5 Checker run | Run `checkerPass` twice: on the probe-annotated tree (must finish without a crash, with the checker invoked), then with one deliberately ill-typed assignment (must report exactly one error at the expected location) | `checker_crash`, `checker_silent`, `diagnostics_unparsed` |
| P6 Test harness | Run one test class with the probe jar on the classpath; JUnit XML must appear | `test_xml_missing`, `test_requires_docker` |

Any check can also end in `timeout`. A failure that matches no known pattern is
`unknown`, and the raw log tail is kept.

The second half of P5 matters most. It confirms the checker actually reports
errors, which protects against the bug 4 failure mode where the checker fails
with no file-level errors and the run is scored as zero errors.

Outputs:

- A `preflight_results` row: status, failed check, reason code, Gradle version,
  runtime and toolchain JDKs, backend and image digest, duration, log path.
- Runs that fail preflight end with lifecycle status `unsupported`, and the
  reason code is stored with them.

Caching: the preflight result depends on the build configuration, not on every
source change. Cache it on a hash of the build files (`*.gradle`, `*.gradle.kts`,
`gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`,
`gradle/libs.versions.toml`, `buildSrc/`), the init script, the probe lattice,
and the image digest. In crawler mode, adjacent commits usually share a result.

Standalone use: `pipeline preflight --manifest repos.yaml` runs preflight
without the rest of the pipeline. The corpus measurement uses this.

### 7.4 Corpus Measurement

The generality claim in the project write-up is a measured pass rate, not an
argument.

1. **Eval repos.** Run preflight on the 12 eval repos. Record the expected
   result for each in advance, as `expected_fit` already does in
   `benchmarks.yaml`, then compare.
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
  Gradle toolchain for JDK 21 and `options.release = <project source level>`.
  The checker is then independent of the JDK the project builds with. This
  fixes JDK 8 projects (whose JDK rejects the init script's `--add-exports`
  flags) and projects that build on JDK 25 while their sources are 21 or lower.
- **Give `checkerPass` a clean compiler configuration.** Rather than inheriting
  the project's `JavaCompile` settings and undoing them one at a time (`-Werror`,
  `-Xlint`, Error Prone), set `compilerArgs` to exactly the checker flags. Keep
  only the harness's own argument providers, and disable Error Prone on the
  task when its extension is present. This is how Phase 0 should fix bug 4. The
  five Error Prone repositories in §7.1 verify it.
- **Compile the qualifier jar with `--release` set to the project's target
  level.** This is the bug 1 fix; P3 verifies it on every repository.

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
