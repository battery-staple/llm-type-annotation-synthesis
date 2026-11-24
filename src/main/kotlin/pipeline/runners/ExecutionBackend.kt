package pipeline.runners

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import mu.KotlinLogging
import pipeline.config.ExecutionBackendKind
import pipeline.config.ExecutionSection
import pipeline.config.ProjectConfig
import pipeline.ports.ExecSpec
import pipeline.ports.ExecutionBackend
import pipeline.ports.ExecutionHandle
import pipeline.ports.Mount
import pipeline.util.Env
import pipeline.util.LocalProcessHandle
import pipeline.util.Processes

private val logger = KotlinLogging.logger {}

/** Today's behavior: a host process with the pipeline's environment plus [ExecSpec.environment]. */
class LocalBackend : ExecutionBackend {
    override val name: String = ExecutionBackendKind.LOCAL.identifier
    override val imageIdentity: String? = null

    override fun start(spec: ExecSpec): ExecutionHandle {
        val builder = ProcessBuilder(spec.command).directory(spec.cwd)
        builder.environment().apply {
            putAll(spec.environment)
            Env.applyTo(this)
            // Tools that resolve paths from PWD would otherwise target the pipeline checkout.
            this["PWD"] = spec.cwd.absolutePath
        }
        return LocalProcessHandle(builder.start())
    }
}

/**
 * Facts about the runtime image, read from its labels at startup so the code
 * does not hard-code the image's layout (docker/runtime.Dockerfile sets them).
 */
data class RuntimeImage(
    /** `docker image inspect` ID (`sha256:...`); containers are started from it, not from the tag. */
    val id: String,
    /** JDK feature version to its home in the image. */
    val jdks: Map<String, String>,
    /** Where the shared Gradle home volume is mounted. */
    val gradleHome: String,
    /** `HOME` for the container user. */
    val home: String,
) {
    fun jdkHome(version: String): String = jdks[version]
        ?: throw IllegalArgumentException(
            "runtime_jdk '$version' is not in the runtime image $id (it has ${jdks.keys.joinToString()})"
        )

    companion object {
        const val LABEL_JDKS = "lltas.jdks"
        const val LABEL_JDK_ROOT = "lltas.jdk_root"
        const val LABEL_GRADLE_HOME = "lltas.gradle_home"
        const val LABEL_HOME = "lltas.home"

        /** Parses the `|`-separated output of [DockerBackend.INSPECT_FORMAT]. */
        fun parseInspect(output: String): RuntimeImage {
            val parts = output.trim().split('|')
            require(parts.size == 5) { "unexpected docker image inspect output: '$output'" }
            fun label(i: Int, name: String): String = parts[i].trim().takeUnless { it.isEmpty() || it == "<no value>" }
                ?: throw IllegalStateException("runtime image ${parts[0]} has no '$name' label; rebuild it from docker/runtime.Dockerfile")
            val root = label(2, LABEL_JDK_ROOT).trimEnd('/')
            val versions = label(1, LABEL_JDKS).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return RuntimeImage(
                id = parts[0].trim(),
                jdks = versions.associateWith { "$root/$it" },
                gradleHome = label(3, LABEL_GRADLE_HOME),
                home = label(4, LABEL_HOME),
            )
        }
    }
}

/**
 * Runs each invocation in its own `docker run --rm --init` container
 * (Dockerization plan, sections 3.2-3.4). Host paths are mounted at the same
 * path, so worktree `.git` files, harness paths in the environment, and file
 * names in checker diagnostics need no translation; nothing else of the host
 * is visible. [kill] is `docker rm -f`, because killing the `docker` client
 * leaves the container running.
 */
class DockerBackend(
    val image: RuntimeImage,
    private val memory: String,
    private val cpus: Double,
    private val docker: String = "docker",
    private val ownerPid: Long = ProcessHandle.current().pid(),
) : ExecutionBackend {
    override val name: String = ExecutionBackendKind.DOCKER.identifier
    override val imageIdentity: String get() = image.id

    /**
     * Gradle arguments for every invocation in the image: toolchains come only
     * from the image's JDKs and are never downloaded, so a build that needs a
     * JDK the image lacks fails visibly instead of depending on the network.
     */
    val toolchainArgs: List<String>
        get() = listOf(
            "-Porg.gradle.java.installations.auto-detect=false",
            "-Porg.gradle.java.installations.auto-download=false",
            "-Porg.gradle.java.installations.paths=" + image.jdks.values.joinToString(","),
        )

    /**
     * Like the default, but a container that never started (`docker run`
     * exits 125 and reports a daemon error: the image was removed, a mount
     * source is missing, the daemon stopped) throws instead of returning a
     * result. Otherwise it would read as a failed build and be recorded as a
     * fact about the repository.
     */
    override fun run(spec: ExecSpec, timeoutSeconds: Long, stdin: String?): pipeline.util.ProcResult {
        val result = Processes.collect(start(spec), timeoutSeconds, stdin)
        if (isInfrastructureFailure(result)) {
            throw ExecutionInfrastructureException(
                "build container did not start (docker exit ${result.exitCode}): ${result.stderr.trim().take(2000)}"
            )
        }
        return result
    }

    override fun start(spec: ExecSpec): ExecutionHandle {
        val name = containerName(spec.kind)
        val args = runArgs(spec, name)
        // The docker client keeps the pipeline's environment (DOCKER_HOST,
        // config); only the explicit -e entries reach the container.
        val process = ProcessBuilder(args).directory(spec.cwd).start()
        return DockerHandle(process, name, docker)
    }

    private fun containerName(kind: String): String =
        "lltas-$kind-$ownerPid-${UUID.randomUUID().toString().take(8)}"

    /** The full `docker run` command line; pure, so tests can check it. */
    fun runArgs(spec: ExecSpec, name: String): List<String> = buildList {
        add(docker); add("run"); add("--rm"); add("--init")
        add("--name"); add(name)
        add("--label"); add("$LABEL_OWNER_PID=$ownerPid")
        add("--label"); add("$LABEL_KIND=${spec.kind}")
        add("--memory"); add(memory)
        add("--cpus"); add(cpus.toString())
        add("--mount"); add("type=volume,source=$GRADLE_HOME_VOLUME,target=${image.gradleHome}")
        normalizeMounts(spec.mounts).forEach { mount ->
            add("--mount"); add(bindMountSpec(mount))
        }
        add("--workdir"); add(spec.cwd.absoluteFile.toPath().normalize().toString())
        spec.environment.toSortedMap().forEach { (key, value) ->
            require(key.isNotBlank() && '=' !in key) { "invalid environment variable name '$key'" }
            add("--env"); add("$key=$value")
        }
        add(image.id)
        addAll(spec.command)
    }

    /** Removes every container this process started, for shutdown. */
    fun removeOwnContainers() {
        val ids = runCatching {
            Processes.run(
                listOf(docker, "ps", "-aq", "--filter", "label=$LABEL_OWNER_PID=$ownerPid"),
                File("."), timeoutSeconds = 30
            ).stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        if (ids.isNotEmpty()) {
            runCatching { Processes.run(listOf(docker, "rm", "-f") + ids, File("."), timeoutSeconds = 60) }
        }
    }

    companion object {
        const val GRADLE_HOME_VOLUME = "lltas-gradle-home"
        const val LABEL_OWNER_PID = "lltas.owner_pid"
        const val LABEL_KIND = "lltas.kind"
        const val INSPECT_FORMAT =
            "{{.Id}}|{{index .Config.Labels \"${RuntimeImage.LABEL_JDKS}\"}}" +
                "|{{index .Config.Labels \"${RuntimeImage.LABEL_JDK_ROOT}\"}}" +
                "|{{index .Config.Labels \"${RuntimeImage.LABEL_GRADLE_HOME}\"}}" +
                "|{{index .Config.Labels \"${RuntimeImage.LABEL_HOME}\"}}"

        /**
         * Absolute, normalized, de-duplicated mounts. A path requested both
         * read-only and writable is writable (writable mounts are explicit
         * requests), and a mount inside another one with the same mode is
         * dropped. A read-only mount inside a writable one is kept, since it
         * narrows access.
         */
        fun normalizeMounts(mounts: List<Mount>): List<Mount> {
            val byPath = linkedMapOf<java.nio.file.Path, Boolean>()
            mounts.forEach { m ->
                val path = m.path.absoluteFile.toPath().normalize()
                byPath[path] = (byPath[path] ?: true) && m.readOnly
            }
            return byPath.entries
                .filter { (path, readOnly) ->
                    byPath.none { (other, otherReadOnly) -> other != path && path.startsWith(other) && otherReadOnly == readOnly }
                }
                .sortedBy { it.key.toString() }
                .map { Mount(it.key.toFile(), it.value) }
        }

        /** `--mount` value; fields containing a comma or quote are CSV-quoted, as docker parses them. */
        internal fun bindMountSpec(mount: Mount): String {
            val path = mount.path.path
            fun field(name: String) = "$name=$path".let { f ->
                if (',' in f || '"' in f) "\"" + f.replace("\"", "\"\"") + "\"" else f
            }
            return listOf("type=bind", field("source"), field("target")).joinToString(",") +
                if (mount.readOnly) ",readonly" else ""
        }

        /**
         * Startup checks (plan section 3.11): the daemon answers, the image
         * exists and carries the expected labels, the configured ID (if any)
         * matches, and the Gradle home volume exists. Any failure stops the
         * pipeline before a run is created.
         */
        fun connect(config: ProjectConfig, docker: String = "docker"): DockerBackend {
            val section: ExecutionSection = config.execution
            fun dockerCmd(vararg args: String, timeout: Long = 60) =
                Processes.run(listOf(docker) + args.toList(), File("."), timeoutSeconds = timeout)

            val version = runCatching { dockerCmd("version", "--format", "{{.Server.Version}}") }.getOrNull()
            check(version != null && version.exitCode == 0) {
                "execution.backend is docker, but the Docker daemon is not reachable: ${version?.output?.trim() ?: "docker not found"}"
            }
            val inspect = dockerCmd("image", "inspect", "--format", INSPECT_FORMAT, section.image)
            check(inspect.exitCode == 0) {
                "runtime image '${section.image}' not found. Build it with:\n" +
                    "  docker build -f docker/runtime.Dockerfile -t ${section.image} docker"
            }
            val image = RuntimeImage.parseInspect(inspect.stdout)
            check(section.imageId.isBlank() || section.imageId == image.id) {
                "execution.image '${section.image}' is ${image.id}, but execution.image_id pins ${section.imageId}"
            }
            val volume = dockerCmd("volume", "create", GRADLE_HOME_VOLUME)
            check(volume.exitCode == 0) { "cannot create Docker volume $GRADLE_HOME_VOLUME: ${volume.output.trim()}" }

            val vmBytes = dockerCmd("info", "--format", "{{.MemTotal}}").stdout.trim().toLongOrNull()
            val limit = parseMemory(section.buildMemory)
            if (vmBytes != null) {
                check(limit <= vmBytes) {
                    "execution.build_memory ${section.buildMemory} exceeds the Docker VM's memory (${vmBytes / MIB} MiB)"
                }
                if (limit * config.concurrency.builds > vmBytes) {
                    logger.warn {
                        "concurrency.builds=${config.concurrency.builds} x execution.build_memory=${section.buildMemory} " +
                            "exceeds the Docker VM's ${vmBytes / MIB} MiB; concurrent checker runs may be killed for memory. " +
                            "Use --build-jobs 1 or give the Docker VM more memory."
                    }
                }
            }
            logger.info { "Docker backend: server ${version.stdout.trim()}, image ${section.image} = ${image.id}, JDKs ${image.jdks.keys}" }
            return DockerBackend(image, section.buildMemory, section.cpus, docker)
        }

        private const val MIB = 1024L * 1024L

        /** `docker run` uses exit code 125 for errors of the daemon or client, before the command runs. */
        internal fun isInfrastructureFailure(result: pipeline.util.ProcResult): Boolean =
            !result.timedOut && result.exitCode == 125 &&
                result.stderr.lineSequence().any { it.startsWith("docker: ") || it.startsWith("Error response from daemon") }

        internal fun parseMemory(raw: String): Long {
            val m = Regex("""(\d+)([bkmg]?)""", RegexOption.IGNORE_CASE).matchEntire(raw.trim())
                ?: throw IllegalArgumentException("invalid memory size '$raw'")
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2].lowercase()) {
                "", "b" -> n
                "k" -> n * 1024
                "m" -> n * MIB
                else -> n * 1024 * MIB
            }
        }
    }
}

/** The execution environment failed, not the command; never a result about the repository. */
class ExecutionInfrastructureException(message: String) : RuntimeException(message)

/** The `docker run` client plus the container it started. */
private class DockerHandle(
    private val client: Process,
    private val containerName: String,
    private val docker: String,
) : ExecutionHandle {
    override val stdout: java.io.InputStream get() = client.inputStream
    override val stderr: java.io.InputStream get() = client.errorStream
    override val stdin: java.io.OutputStream get() = client.outputStream
    override fun isAlive(): Boolean = client.isAlive
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = client.waitFor(timeout, unit)
    override fun exitValue(): Int = client.exitValue()

    override fun kill() {
        runCatching { Processes.run(listOf(docker, "rm", "-f", containerName), File("."), timeoutSeconds = 60) }
            .onFailure { logger.warn(it) { "docker rm -f $containerName failed" } }
        Processes.terminateTree(client)
    }
}

/** Builds the configured backend; Docker mode runs its startup checks here. */
object ExecutionBackends {
    fun create(config: ProjectConfig): Pair<ProjectConfig, ExecutionBackend> = when (config.execution.backend) {
        ExecutionBackendKind.LOCAL -> config to LocalBackend()
        ExecutionBackendKind.DOCKER -> {
            val backend = DockerBackend.connect(config)
            Runtime.getRuntime().addShutdownHook(Thread({ backend.removeOwnContainers() }, "remove-own-containers"))
            // Record the resolved ID so the run's config snapshot names the exact image.
            config.copy(execution = config.execution.copy(imageId = backend.image.id)) to backend
        }
    }
}
