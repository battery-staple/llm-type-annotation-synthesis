package pipeline.util

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitWorktreeLockTest {
    @Test
    fun `worktree operations share one lock regardless of repository path`() {
        assertSame(Git.worktreeLockFor(File("mirror.git")), Git.worktreeLockFor(File("linked-worktree")))
    }

    @Test
    fun `file lock is keyed on the common git dir so a mirror and its worktree contend`() {
        val root = Files.createTempDirectory("git-worktree-file-lock").toFile()
        val processes = mutableListOf<Process>()
        try {
            val mirrorA = createMirrorWithWorktree(root, "a")
            val worktreeA = File(root, "worktrees/a/ingestion")
            val mirrorB = createMirrorWithWorktree(root, "b")

            val aAttempt = File(root, "a.attempt")
            val bAttempt = File(root, "b.attempt")
            val cAttempt = File(root, "c.attempt")
            val aAcquired = File(root, "a.acquired")
            val bAcquired = File(root, "b.acquired")
            val cAcquired = File(root, "c.acquired")
            val aRelease = File(root, "a.release")
            val bRelease = File(root, "b.release")
            val cRelease = File(root, "c.release")

            val first = startProbe(mirrorA, aAttempt, aAcquired, aRelease).also { processes += it }
            assertTrue(waitFor { aAttempt.exists() }, "first probe did not start")
            assertTrue(waitFor { aAcquired.exists() }, "first probe did not acquire the lock")

            // A linked worktree resolves to the same common git dir as its bare
            // mirror, so it must contend on exactly the same lock file.
            val sameMirror = startProbe(worktreeA, bAttempt, bAcquired, bRelease).also { processes += it }
            assertTrue(waitFor { bAttempt.exists() }, "second probe did not reach the lock")
            assertFalse(
                waitFor(1_000) { bAcquired.exists() },
                "a linked worktree acquired the mirror's lock while it was held"
            )

            val otherMirror = startProbe(mirrorB, cAttempt, cAcquired, cRelease).also { processes += it }
            assertTrue(waitFor { cAttempt.exists() }, "third probe did not start")
            assertTrue(waitFor { cAcquired.exists() }, "a different mirror must not be blocked")

            cRelease.writeText("go")
            aRelease.writeText("go")
            assertTrue(waitFor { bAcquired.exists() }, "second probe never acquired after release")
            bRelease.writeText("go")

            processes.forEach { process ->
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                assertEquals(0, process.exitValue(), "lock probe failed")
            }
        } finally {
            processes.forEach { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            root.deleteRecursively()
        }
    }

    /** Builds a real bare mirror and one linked worktree so gitCommonDir is exercised. */
    private fun createMirrorWithWorktree(root: File, name: String): File {
        val source = File(root, "source-$name").apply { mkdirs() }
        assertEquals(0, Git.run(source, "init", "-q").exitCode)
        assertEquals(0, Git.run(source, "config", "user.email", "test@example.com").exitCode)
        assertEquals(0, Git.run(source, "config", "user.name", "Test").exitCode)
        File(source, "Example.java").writeText("class Example {}\n")
        assertEquals(0, Git.run(source, "add", "-A").exitCode)
        assertEquals(0, Git.run(source, "commit", "-q", "--no-gpg-sign", "-m", "baseline").exitCode)

        val mirror = File(root, "$name.git")
        val clone = Git.run(root, "clone", "-q", "--mirror", source.absolutePath, mirror.absolutePath)
        assertEquals(0, clone.exitCode, clone.output)
        val add = Git.run(mirror, "worktree", "add", "--detach", File(root, "worktrees/$name/ingestion").absolutePath, "HEAD")
        assertEquals(0, add.exitCode, add.output)
        return mirror
    }

    private fun startProbe(repo: File, attempt: File, acquired: File, release: File): Process {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val command = listOf(
            java, "-cp", System.getProperty("java.class.path"),
            "pipeline.util.WorktreeLockProbe",
            repo.absolutePath, attempt.absolutePath, acquired.absolutePath, release.absolutePath
        )
        return ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

/** Test-only process that holds the cross-process lock until a marker file appears. */
object WorktreeLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val attempt = File(args[1])
        val acquired = File(args[2])
        val release = File(args[3])
        attempt.writeText("attempting")
        Git.withWorktreeFileLock(File(args[0])) {
            acquired.writeText("acquired")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!release.exists()) {
                check(System.nanoTime() < deadline) { "release marker never appeared" }
                Thread.sleep(10)
            }
        }
    }
}
