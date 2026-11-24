package pipeline.runners

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import pipeline.config.ExecutionBackendKind
import pipeline.config.ExecutionSection
import pipeline.config.ProjectConfig
import pipeline.ports.ExecSpec
import pipeline.ports.Mount
import pipeline.util.FileOps
import pipeline.util.Processes

/**
 * Runs real build containers. Skipped when Docker or the runtime image
 * (`docker build -f docker/runtime.Dockerfile -t lltas-runtime:0.1 docker`) is
 * unavailable, so `./gradlew test` passes on a machine without Docker.
 *
 * A build container runs agent-written code during verification, so it must
 * see only what it was given: the worktree writable, harness files read-only,
 * and nothing else of the host.
 */
class DockerBackendIntegrationTest {

    private fun backendOrSkip(): DockerBackend {
        val image = ExecutionSection().image
        val available = runCatching {
            Processes.run(listOf("docker", "image", "inspect", image), File("."), timeoutSeconds = 30).exitCode == 0
        }.getOrDefault(false)
        assumeTrue(available, "Docker daemon or image $image not available")
        return DockerBackend.connect(ProjectConfig(execution = ExecutionSection(backend = ExecutionBackendKind.DOCKER)))
    }

    @Test
    fun `a build container sees only its mounts`() {
        val backend = backendOrSkip()
        val root = Files.createTempDirectory("docker-canary").toFile().canonicalFile
        try {
            val worktree = root.resolve("worktree").apply { mkdirs() }
            val harness = root.resolve("harness.jar").apply { writeText("harness") }
            val secret = root.resolve("secret.env").apply { writeText("TOKEN=1") }
            val script = """
                echo built > "${worktree.path}/out.txt"
                cat "${harness.path}" >/dev/null && echo HARNESS_READABLE
                ( echo x >> "${harness.path}" ) 2>/dev/null && echo HARNESS_WRITABLE
                cat "${secret.path}" 2>/dev/null && echo SECRET_VISIBLE
                ls "${File(System.getProperty("user.home")).path}" 2>/dev/null | head -1 | grep -q . && echo HOME_VISIBLE
                [ -e /var/run/docker.sock ] && echo DOCKER_SOCKET
                echo "user=${'$'}(id -un) java=${'$'}JAVA_HOME"
            """.trimIndent()
            val result = backend.run(
                ExecSpec(
                    command = listOf("bash", "-c", script),
                    cwd = worktree,
                    environment = mapOf("JAVA_HOME" to backend.image.jdkHome("21")),
                    mounts = listOf(Mount(worktree, readOnly = false), Mount(harness, readOnly = true)),
                ),
                timeoutSeconds = 120,
            )
            assertEquals(0, result.exitCode, result.output)
            assertEquals("built", worktree.resolve("out.txt").readText().trim(), "the worktree is writable")
            assertTrue("HARNESS_READABLE" in result.stdout, result.output)
            assertFalse("HARNESS_WRITABLE" in result.stdout, "harness files are read-only: ${result.output}")
            assertFalse("SECRET_VISIBLE" in result.stdout, "unmounted host files are invisible: ${result.output}")
            assertFalse("HOME_VISIBLE" in result.stdout, "the host home directory is invisible: ${result.output}")
            assertFalse("DOCKER_SOCKET" in result.stdout, "no Docker socket: ${result.output}")
            assertTrue("user=lltas java=/opt/java/21" in result.stdout, result.output)
            assertEquals("harness", harness.readText())
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `a timed-out build container is removed`() {
        val backend = backendOrSkip()
        val dir = Files.createTempDirectory("docker-timeout").toFile().canonicalFile
        try {
            val result = backend.run(ExecSpec(listOf("sleep", "300"), dir, mounts = listOf(Mount(dir, false))), timeoutSeconds = 3)
            assertTrue(result.timedOut)
            val left = Processes.run(
                listOf("docker", "ps", "-aq", "--filter", "label=${DockerBackend.LABEL_OWNER_PID}=${ProcessHandle.current().pid()}"),
                File("."), timeoutSeconds = 30
            ).stdout.trim()
            assertEquals("", left, "no container may outlive its timeout")
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }
}
