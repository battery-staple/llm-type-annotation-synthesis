package pipeline.util

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorktreeLifecycleTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun mirrorWithCommit(root: File): Pair<File, String> {
        val source = File(root, "source").apply { mkdirs() }
        assertEquals(0, Git.run(source, "init", "-q").exitCode)
        assertEquals(0, Git.run(source, "config", "user.email", "test@example.com").exitCode)
        assertEquals(0, Git.run(source, "config", "user.name", "Test").exitCode)
        File(source, "Example.java").writeText("class Example {}\n")
        assertEquals(0, Git.run(source, "add", "-A").exitCode)
        assertEquals(0, Git.run(source, "commit", "-q", "--no-gpg-sign", "-m", "baseline").exitCode)
        val commit = Git.revParse(source, "HEAD")

        val mirror = File(root, "mirror.git")
        val clone = Git.run(root, "clone", "-q", "--mirror", source.absolutePath, mirror.absolutePath)
        assertEquals(0, clone.exitCode, clone.output)
        return mirror to commit
    }

    private fun registeredPaths(repo: File): Set<String> =
        Git.run(repo, "worktree", "list", "--porcelain").stdout.lineSequence()
            .filter { it.startsWith("worktree ") }
            .map { File(it.removePrefix("worktree ")).canonicalPath }
            .toSet()

    @Test
    fun `worktree can be re-added at the same path after its directory was deleted`() {
        val root = tempDir("git-worktree-stale")
        try {
            val (mirror, commit) = mirrorWithCommit(root)
            val destination = File(root, "worktrees/run_1/ingestion")

            Git.addWorktree(mirror, destination, commit)
            assertTrue(destination.isDirectory)
            assertEquals(commit, Git.currentCommit(destination))
            assertEquals(2, registeredPaths(mirror).size)

            // Simulate a run whose worktree directory vanished behind git's back.
            destination.deleteRecursively()
            assertTrue(destination.canonicalPath in registeredPaths(mirror))

            // Must not throw "missing but already registered worktree".
            Git.addWorktree(mirror, destination, commit)
            assertTrue(destination.isDirectory)
            assertEquals(commit, Git.currentCommit(destination))
            assertEquals(2, registeredPaths(mirror).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `removeWorktree clears a stale registration when the directory is already gone`() {
        val root = tempDir("git-worktree-remove-stale")
        try {
            val (mirror, commit) = mirrorWithCommit(root)
            val destination = File(root, "worktrees/run_2/ingestion")
            Git.addWorktree(mirror, destination, commit)
            destination.deleteRecursively()
            assertTrue(destination.canonicalPath in registeredPaths(mirror))

            Git.removeWorktree(mirror, destination)

            assertFalse(destination.canonicalPath in registeredPaths(mirror))
            assertEquals(1, registeredPaths(mirror).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stale registration with a mismatched admin directory name does not block re-add`() {
        val root = tempDir("git-worktree-renamed-admin")
        try {
            val (mirror, commit) = mirrorWithCommit(root)
            val destination = File(root, "worktrees/run_3/ingestion")
            Git.addWorktree(mirror, destination, commit)

            // Reproduce the incident shape: admin entry named ingestion430 whose
            // gitdir still points at a deleted .../run_3/ingestion directory.
            val admin = File(mirror, "worktrees").listFiles()!!.single()
            assertTrue(admin.renameTo(File(admin.parentFile, "ingestion430")))
            destination.deleteRecursively()
            assertTrue(destination.canonicalPath in registeredPaths(mirror))

            Git.addWorktree(mirror, destination, commit)

            assertTrue(destination.isDirectory)
            assertEquals(commit, Git.currentCommit(destination))
            assertEquals(2, registeredPaths(mirror).size)
            assertFalse(File(mirror, "worktrees/ingestion430").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `targeted prune removes only the admin entry for the stale destination`() {
        val root = tempDir("git-worktree-targeted-prune")
        try {
            val (mirror, commit) = mirrorWithCommit(root)
            val stale = File(root, "worktrees/run_stale/ingestion")
            val other = File(root, "worktrees/run_other/ingestion")
            Git.addWorktree(mirror, stale, commit)
            Git.addWorktree(mirror, other, commit)
            val staleAdmin = adminEntryFor(mirror, stale)
            val otherAdmin = adminEntryFor(mirror, other)

            // The directory is gone but its administrative entry is still registered.
            stale.deleteRecursively()
            assertTrue(stale.canonicalPath in registeredPaths(mirror))

            // Exercise the hand-written fallback directly: git's own remove is
            // not involved, so this covers the custom gitdir-scanning cleanup.
            Git.pruneStaleWorktreeEntry(mirror, stale)

            assertFalse(staleAdmin.exists())
            assertTrue(otherAdmin.exists())
            assertFalse(stale.canonicalPath in registeredPaths(mirror))
            assertTrue(other.canonicalPath in registeredPaths(mirror))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun adminEntryFor(mirror: File, destination: File): File =
        File(mirror, "worktrees").listFiles()!!.first { entry ->
            File(entry, "gitdir").readText().trim() == File(destination, ".git").absolutePath
        }

    @Test
    fun `worktree lock setup fails loudly when the common git dir cannot be resolved`() {
        val root = tempDir("git-worktree-no-common-dir")
        try {
            val notARepo = File(root, "not-a-repo").apply { mkdirs() }
            val destination = File(root, "worktrees/run_1/ingestion")

            val error = assertFailsWith<GitException> {
                Git.addWorktree(notARepo, destination, "HEAD")
            }
            assertTrue(error.message?.contains("git rev-parse --git-common-dir") == true, error.message)
            // The old failure path silently keyed the lock on the repo root.
            assertFalse(File(notARepo, "pipeline-worktree.lock").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stale registration cleanup leaves other runs worktrees untouched`() {
        val root = tempDir("git-worktree-isolated")
        try {
            val (mirror, commit) = mirrorWithCommit(root)
            val other = File(root, "worktrees/run_other/ingestion")
            val stale = File(root, "worktrees/run_stale/ingestion")
            Git.addWorktree(mirror, other, commit)
            Git.addWorktree(mirror, stale, commit)
            assertEquals(3, registeredPaths(mirror).size)

            stale.deleteRecursively()
            Git.addWorktree(mirror, stale, commit)

            assertTrue(other.isDirectory)
            assertTrue(other.canonicalPath in registeredPaths(mirror))
            assertEquals(commit, Git.currentCommit(other))
            assertEquals(3, registeredPaths(mirror).size)
        } finally {
            root.deleteRecursively()
        }
    }
}
