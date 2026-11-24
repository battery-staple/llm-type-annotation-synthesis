package pipeline.runners

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.ports.ExecSpec
import pipeline.ports.Mount
import pipeline.stages.CheckerCategory
import pipeline.stages.CheckerConfig
import pipeline.stages.Fqcn
import pipeline.util.FileOps
import pipeline.util.Git

/**
 * `docker run` command construction (a pure function, Dockerization plan
 * section 5 "Testing approach") and the build runner's use of it, with a fake
 * `docker` executable that records its arguments. No Docker daemon needed.
 */
class DockerBackendTest {

    private val image = RuntimeImage(
        id = "sha256:abc",
        jdks = mapOf("8" to "/opt/java/8", "21" to "/opt/java/21", "25" to "/opt/java/25"),
        gradleHome = "/gradle-home",
        home = "/home/lltas",
    )

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun `run arguments isolate the container and pin the image by ID`() {
        val backend = DockerBackend(image, memory = "6g", cpus = 2.0, docker = "docker", ownerPid = 42)
        val args = backend.runArgs(
            ExecSpec(
                command = listOf("./gradlew", ":compileJava"),
                cwd = File("/work/tree"),
                environment = mapOf("Z_VAR" to "z", "JAVA_HOME" to "/opt/java/21"),
                mounts = listOf(Mount(File("/work/tree"), readOnly = false), Mount(File("/harness/init.gradle"))),
            ),
            name = "lltas-build-42-x",
        )
        assertEquals(listOf("docker", "run", "--rm", "--init"), args.take(4))
        assertTrue(args.containsAll(listOf("--name", "lltas-build-42-x", "--memory", "6g", "--cpus", "2.0")), "$args")
        assertTrue("lltas.owner_pid=42" in args, "owner label: $args")
        assertTrue("type=volume,source=lltas-gradle-home,target=/gradle-home" in args, "$args")
        assertTrue("type=bind,source=/work/tree,target=/work/tree" in args, "worktree is writable: $args")
        assertTrue("type=bind,source=/harness/init.gradle,target=/harness/init.gradle,readonly" in args, "$args")
        assertEquals("/work/tree", args[args.indexOf("--workdir") + 1])
        // Environment is explicit and sorted; nothing is inherited by name.
        val envs = args.withIndex().filter { it.value == "--env" }.map { args[it.index + 1] }
        assertEquals(listOf("JAVA_HOME=/opt/java/21", "Z_VAR=z"), envs)
        assertEquals(listOf("sha256:abc", "./gradlew", ":compileJava"), args.takeLast(3))
    }

    @Test
    fun `mounts are normalized, merged, and nested duplicates dropped`() {
        val mounts = DockerBackend.normalizeMounts(
            listOf(
                Mount(File("/a/b/../b"), readOnly = true),
                Mount(File("/a/b"), readOnly = false),
                Mount(File("/a/b/c.jar"), readOnly = false),
                Mount(File("/a/b/ro.jar"), readOnly = true),
                Mount(File("/m/repo.git"), readOnly = true),
                Mount(File("/m/repo.git/worktrees/x"), readOnly = true),
            )
        )
        assertEquals(
            listOf(
                Mount(File("/a/b"), readOnly = false),
                Mount(File("/a/b/ro.jar"), readOnly = true),
                Mount(File("/m/repo.git"), readOnly = true),
            ),
            mounts,
        )
    }

    @Test
    fun `mount fields with commas are quoted the way docker parses them`() {
        assertEquals(
            "type=bind,\"source=/x,y\",\"target=/x,y\",readonly",
            DockerBackend.bindMountSpec(Mount(File("/x,y"))),
        )
    }

    @Test
    fun `image labels describe the JDKs and an unknown runtime JDK fails`() {
        val parsed = RuntimeImage.parseInspect("sha256:1|8,21|/opt/java/|/gradle-home|/home/lltas\n")
        assertEquals(mapOf("8" to "/opt/java/8", "21" to "/opt/java/21"), parsed.jdks)
        assertEquals("/opt/java/21", parsed.jdkHome("21"))
        assertFailsWith<IllegalArgumentException> { parsed.jdkHome("17") }
        assertFailsWith<IllegalStateException> { RuntimeImage.parseInspect("sha256:1|<no value>|/opt/java|/g|/h") }
    }

    @Test
    fun `memory sizes parse like docker's`() {
        assertEquals(6L * 1024 * 1024 * 1024, DockerBackend.parseMemory("6g"))
        assertEquals(512L * 1024 * 1024, DockerBackend.parseMemory("512M"))
        assertFailsWith<IllegalArgumentException> { DockerBackend.parseMemory("6 GB") }
    }

    @Test
    fun `a Gradle build in docker mode gets image paths, toolchain flags, and narrow mounts`() = runBlocking {
        val root = tempDir("docker-runner")
        try {
            val fake = FakeDocker(root.resolve("bin"))
            val worktree = root.resolve("run/worktree").apply { mkdirs() }
            val cfHome = root.resolve("vendor/cf").apply { mkdirs() }
            val jar = root.resolve("cache/bundle/custom_quals.jar").apply { parentFile.mkdirs(); writeText("jar") }
            val config = ProjectConfig().let {
                it.copy(environment = it.environment.copy(javaHome = "/host/jdk", gradleUserHome = "/host/gradle", checkerFrameworkHome = cfHome.path))
            }
            val runner = GradleBuildRunner(
                config, BuildSection(runtimeJdk = "25"), "17", "17",
                backend = DockerBackend(image, "6g", 4.0, docker = fake.executable.path, ownerPid = 7),
            )
            val checker = CheckerConfig(jar, "org.example.Checker", CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY, listOf(Fqcn("q.Top")))
            val result = runner.compile(worktree, checker)
            assertTrue(result.success, result.output)

            val args = fake.calls().single()
            val envs = args.withIndex().filter { it.value == "--env" }.map { args[it.index + 1] }.toSet()
            assertTrue("JAVA_HOME=/opt/java/25" in envs, "runtime_jdk selects the image JDK: $envs")
            assertTrue("GRADLE_USER_HOME=/gradle-home" in envs, "$envs")
            assertFalse(envs.any { it.contains("/host/") }, "host JAVA_HOME / GRADLE_USER_HOME must not reach the container: $envs")
            assertTrue("CUSTOM_QUAL_JAR_PATH=${jar.absolutePath}" in envs, "harness paths are same-path: $envs")

            val mounts = args.withIndex().filter { it.value == "--mount" }.map { args[it.index + 1] }
            assertTrue("type=bind,source=${worktree.absolutePath},target=${worktree.absolutePath}" in mounts, "$mounts")
            assertTrue("type=bind,source=${jar.absolutePath},target=${jar.absolutePath},readonly" in mounts, "$mounts")
            assertTrue("type=bind,source=${cfHome.absolutePath},target=${cfHome.absolutePath},readonly" in mounts, "$mounts")
            val initScript = root.resolve("run/.pipeline-init/checker.init.gradle").absolutePath
            assertTrue("type=bind,source=$initScript,target=$initScript,readonly" in mounts, "$mounts")
            assertFalse(mounts.any { it.contains("source=${root.resolve("cache").absolutePath},") }, "only the jar, not its directory: $mounts")

            assertTrue("-Porg.gradle.java.installations.auto-download=false" in args, "$args")
            assertTrue("-Porg.gradle.java.installations.auto-detect=false" in args, "$args")
            assertTrue("-Porg.gradle.java.installations.paths=/opt/java/8,/opt/java/21,/opt/java/25" in args, "$args")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `a runtime JDK the image lacks fails before any build`() {
        assertFailsWith<IllegalArgumentException> {
            GradleBuildRunner(ProjectConfig(), BuildSection(runtimeJdk = "17"), "17", "17",
                backend = DockerBackend(image, "6g", 4.0, docker = "docker"))
        }
    }

    @Test
    fun `a timeout removes the container, not only the docker client`() {
        val root = tempDir("docker-timeout")
        try {
            val fake = FakeDocker(root.resolve("bin"), runSeconds = 30)
            val backend = DockerBackend(image, "6g", 4.0, docker = fake.executable.path, ownerPid = 9)
            val result = backend.run(ExecSpec(listOf("sleep", "30"), root), timeoutSeconds = 1)
            assertTrue(result.timedOut)
            val calls = fake.calls()
            val name = calls.first().let { it[it.indexOf("--name") + 1] }
            assertTrue(name.startsWith("lltas-build-9-"), name)
            assertTrue(calls.any { it == listOf("rm", "-f", name) }, "expected docker rm -f $name: $calls")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `a container that never started is an infrastructure error, not a build result`() {
        val root = tempDir("docker-125")
        try {
            val fake = FakeDocker(root.resolve("bin"), runExit = 125,
                runStderr = "docker: Error response from daemon: No such image: sha256:abc")
            val backend = DockerBackend(image, "6g", 4.0, docker = fake.executable.path)
            assertFailsWith<ExecutionInfrastructureException> {
                backend.run(ExecSpec(listOf("./gradlew"), root), timeoutSeconds = 30)
            }
            // A command that itself exits 125 inside the container is an ordinary result.
            val plain = FakeDocker(root.resolve("bin2"), runExit = 125, runStderr = "build failed")
            val result = DockerBackend(image, "6g", 4.0, docker = plain.executable.path)
                .run(ExecSpec(listOf("./gradlew"), root), timeoutSeconds = 30)
            assertEquals(125, result.exitCode)
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `a linked worktree needs its repository's Git directory`() {
        val root = tempDir("git-dirs").canonicalFile
        try {
            val repo = root.resolve("repo").apply { mkdirs() }
            fun git(dir: File, vararg args: String) = Git.requireOk(Git.run(dir, *args), args.joinToString(" "))
            git(repo, "init", "-q")
            repo.resolve("f.txt").writeText("x")
            git(repo, "add", ".")
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c")
            val mirror = root.resolve("mirror.git")
            git(root, "clone", "-q", "--mirror", repo.path, mirror.path)
            val worktree = root.resolve("wt")
            git(mirror, "worktree", "add", "-q", "--detach", worktree.path, "HEAD")

            assertEquals(listOf(mirror), Git.externalGitDirs(worktree))
            assertEquals(emptyList(), Git.externalGitDirs(repo), "a repository's own .git is inside the mount")

            mirror.resolve("objects/info").mkdirs()
            mirror.resolve("objects/info/alternates").writeText(repo.resolve(".git/objects").path + "\n")
            assertEquals(listOf(mirror, repo.resolve(".git/objects")), Git.externalGitDirs(worktree))
        } finally {
            FileOps.deleteRecursively(root)
        }
    }
}

/** Records each invocation's arguments (one call per file); `run` sleeps [runSeconds], prints [runStderr], exits [runExit]. */
private class FakeDocker(dir: File, runSeconds: Int = 0, runExit: Int = 0, runStderr: String = "") {
    private val log = dir.resolve("calls").apply { mkdirs() }
    val executable: File = dir.resolve("docker").apply {
        writeText(
            """
            #!/usr/bin/env bash
            n=${'$'}(ls "${log.absolutePath}" | wc -l | tr -d ' ')
            f="${log.absolutePath}/${'$'}(printf '%05d' "${'$'}n")"
            printf '%s\n' "${'$'}@" > "${'$'}f"
            if [ "${'$'}1" = "run" ]; then
              sleep $runSeconds
              [ -n "$runStderr" ] && echo "$runStderr" >&2
              exit $runExit
            fi
            exit 0
            """.trimIndent() + "\n"
        )
        setExecutable(true)
    }

    fun calls(): List<List<String>> =
        log.listFiles().orEmpty().sortedBy { it.name }.map { f -> f.readLines().filter { it.isNotEmpty() } }
}
