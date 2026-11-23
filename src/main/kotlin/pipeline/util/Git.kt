package pipeline.util

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CommitMetadata(
    val hash: String,
    val parents: List<String>,
    val authorDate: String,
    val message: String
)

object Git {
    private val repositoryLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val worktreeFileLockMutex = ReentrantLock()
    internal val worktreeOperationsLock = ReentrantLock()
    internal fun worktreeLockFor(repo: File): ReentrantLock = worktreeOperationsLock
    private fun lockFor(repo: File): ReentrantLock = repositoryLocks.computeIfAbsent(
        canonicalPath(repo)
    ) { ReentrantLock() }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** The common git dir is shared by a bare mirror and all of its linked worktrees. */
    private fun gitCommonDir(repo: File): File {
        val absolute = run(repo, "rev-parse", "--path-format=absolute", "--git-common-dir")
        val result = if (absolute.exitCode == 0 && absolute.stdout.isNotBlank()) absolute
        else run(repo, "rev-parse", "--git-common-dir")
        // Fail loudly instead of silently keying the cross-process lock on a
        // bogus path (File("") resolves to the repo root) when rev-parse fails.
        val stdout = requireOk(result, "git rev-parse --git-common-dir")
        require(stdout.isNotBlank()) { "git rev-parse --git-common-dir returned an empty path for $repo" }
        val path = File(stdout)
        val resolved = if (path.isAbsolute) path else File(repo, path.path)
        return File(canonicalPath(resolved))
    }

    /**
     * Cross-process mutex for worktree lifecycle mutations. The OS file lock
     * provides the actual exclusion between pipeline processes; the JVM-side
     * mutex only prevents OverlappingFileLockException when two threads in this
     * process acquire lock files at the same time. The lock file lives at the
     * root of the common git dir, outside git's own worktrees/ metadata.
     */
    internal fun <T> withWorktreeFileLock(repo: File, block: () -> T): T {
        val lockFile = File(gitCommonDir(repo), "pipeline-worktree.lock")
        return worktreeFileLockMutex.withLock {
            lockFile.parentFile?.mkdirs()
            FileOutputStream(lockFile, true).use { stream ->
                val lock = stream.channel.lock()
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun worktreePaths(repo: File): Set<String> {
        // Never treat a git failure as "no registrations": removeWorktree would
        // then delete the directory while leaving a live registration behind.
        val stdout = requireOk(run(repo, "worktree", "list", "--porcelain"), "git worktree list")
        return stdout.lineSequence()
            .filter { it.startsWith("worktree ") }
            .map { canonicalPath(File(it.removePrefix("worktree "))) }
            .toSet()
    }

    /**
     * Last-resort cleanup: remove only the administrative entries whose gitdir
     * file points at [destination]. This is a targeted prune, never a blanket
     * `git worktree prune` over unrelated registrations.
     */
    internal fun pruneStaleWorktreeEntry(repo: File, destination: File) {
        val gitDir = gitCommonDir(repo)
        val destinationGitFile = canonicalPath(File(destination, ".git"))
        val destinationPath = canonicalPath(destination)
        File(gitDir, "worktrees").takeIf { it.isDirectory }?.listFiles()?.forEach { entry ->
            val target = runCatching { File(entry, "gitdir").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val pointedAt = if (File(target).isAbsolute) canonicalPath(File(target))
                            else canonicalPath(File(gitDir, target))
            if (pointedAt == destinationGitFile || pointedAt == destinationPath) {
                FileOps.deleteRecursively(entry)
            }
        }
    }

    /**
     * Clears only the registration for [destination], never another run's worktree.
     * Returns null once the destination is gone from the registration list, or
     * git's failure output when the registration could not be cleared, so the
     * caller can report the underlying diagnostic instead of losing it.
     */
    private fun unregisterWorktree(repo: File, destination: File): String? {
        val destinationPath = canonicalPath(destination)
        if (destinationPath !in worktreePaths(repo)) return null
        val removal = run(repo, "worktree", "remove", "--force", destination.absolutePath)
        if (removal.exitCode == 0) return null

        pruneStaleWorktreeEntry(repo, destination)
        return if (destinationPath !in worktreePaths(repo)) null else removal.output.takeLast(4000)
    }

    fun run(cwd: File, vararg args: String, timeoutSeconds: Long = 300, env: Map<String, String> = emptyMap()): ProcResult =
        Processes.run(listOf("git") + args.toList(), cwd, environment = env, timeoutSeconds = timeoutSeconds)

    fun requireOk(result: ProcResult, action: String): String {
        if (result.exitCode != 0) {
            throw GitException("$action failed (exit ${result.exitCode}): ${result.output.takeLast(4000)}")
        }
        return result.stdout.trim()
    }

    fun revParse(cwd: File, ref: String): String = requireOk(run(cwd, "rev-parse", ref), "git rev-parse $ref")

    /**
     * Turns an agent workspace into a one-commit repository holding exactly its
     * current files. Build scripts that call `git` during configuration
     * (`git describe`, `git rev-parse HEAD`) then work, while the workspace still
     * carries no project history. The identity is passed inline, and hooks,
     * templates, and signing from the user's Git configuration are disabled.
     */
    fun initSnapshot(dir: File) {
        val noUserConfig = arrayOf(
            "-c", "core.hooksPath=/dev/null",
            "-c", "commit.gpgsign=false",
            "-c", "user.name=pipeline",
            "-c", "user.email=pipeline@localhost",
        )
        requireOk(run(dir, *noUserConfig, "init", "-q", "--template=", "."), "git init (workspace snapshot)")
        requireOk(run(dir, *noUserConfig, "add", "-A", "-f", "."), "git add (workspace snapshot)")
        requireOk(
            run(dir, *noUserConfig, "commit", "-q", "--no-verify", "--allow-empty", "-m", "workspace snapshot"),
            "git commit (workspace snapshot)"
        )
    }

    /** Like [requireOk] but preserves the exact stdout bytes (unified diffs need their trailing newline). */
    fun requireOkRaw(result: ProcResult, action: String): String {
        if (result.exitCode != 0) {
            throw GitException("$action failed (exit ${result.exitCode}): ${result.output.takeLast(4000)}")
        }
        return result.stdout
    }

    fun isRepository(dir: File): Boolean = File(dir, ".git").exists() || runCatching { revParse(dir, "--git-dir") }.isSuccess

    fun metadata(repo: File, ref: String): CommitMetadata {
        val raw = requireOk(run(repo, "show", "-s", "--format=%H%x00%P%x00%cI%x00%B", ref), "git show $ref")
        val parts = raw.split('\u0000', limit = 4)
        return CommitMetadata(
            hash = parts.getOrElse(0) { "" },
            parents = parts.getOrElse(1) { "" }.split(' ').filter { it.isNotBlank() },
            authorDate = parts.getOrElse(2) { "" },
            message = parts.getOrElse(3) { "" }.trim()
        )
    }

    fun listCommits(repo: File, ref: String, maxCount: Int): List<CommitMetadata> {
        require(maxCount > 0) { "maxCount must be positive, got $maxCount" }
        val raw = requireOk(
            run(repo, "log", "-n", maxCount.toString(), "--format=%H%x00%P%x00%cI%x00%B%x1e", ref),
            "git log -n $maxCount $ref"
        )
        return raw.split('\u001e')
            .mapNotNull { record ->
                val trimmed = record.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split('\u0000', limit = 4)
                val hash = parts.getOrElse(0) { "" }.trim()
                if (hash.isEmpty()) return@mapNotNull null
                CommitMetadata(
                    hash = hash,
                    parents = parts.getOrElse(1) { "" }.split(' ').filter { it.isNotBlank() },
                    authorDate = parts.getOrElse(2) { "" }.trim(),
                    message = parts.getOrElse(3) { "" }.trim()
                )
            }
    }

    fun mirrorPath(evalReposDir: File, repoName: String): File = File(evalReposDir, "$repoName.git")

    /**
     * Returns a bare mirror for [repoUrl]. Local directories are mirror-cloned;
     * already-present mirrors are fetched when they point at the same URL.
     */
    fun ensureMirror(repoUrl: String, evalReposDir: File, repoName: String): File {
        evalReposDir.mkdirs()
        val mirror = mirrorPath(evalReposDir, repoName)
        return lockFor(mirror).withLock {
            if (!mirror.exists()) {
                val source = resolveRepoUrl(repoUrl)
                val result = run(evalReposDir, "clone", "--mirror", source, mirror.absolutePath, timeoutSeconds = 1800)
                requireOk(result, "git clone --mirror $source")
            } else {
                requireOk(run(mirror, "fetch", "--all", "--prune", timeoutSeconds = 600), "git fetch --all")
            }
            mirror
        }
    }

    fun resolveRepoUrl(repoUrl: String): String {
        val file = File(repoUrl)
        return if (file.exists()) file.absolutePath else repoUrl
    }

    fun addWorktree(repo: File, destination: File, commit: String) {
        worktreeLockFor(repo).withLock {
            withWorktreeFileLock(repo) {
                destination.parentFile?.mkdirs()
                FileOps.deleteRecursively(destination)
                unregisterWorktree(repo, destination)
                var result = run(repo, "worktree", "add", "--detach", destination.absolutePath, commit, timeoutSeconds = 600)
                // Last-resort fallback, still under the lock: only a missing
                // worktree registered at this exact path may be overridden.
                if (result.exitCode != 0 && !destination.exists() && canonicalPath(destination) in worktreePaths(repo)) {
                    result = run(
                        repo, "worktree", "add", "--detach", "--force", destination.absolutePath, commit,
                        timeoutSeconds = 600
                    )
                }
                requireOk(result, "git worktree add $destination")
            }
        }
    }

    fun removeWorktree(repo: File, destination: File) {
        worktreeLockFor(repo).withLock {
            withWorktreeFileLock(repo) {
                // Unregister even when the directory is already gone: a stale
                // registration blocks a later add at the same path.
                val error = unregisterWorktree(repo, destination)
                if (error != null) {
                    throw GitException("git worktree remove $destination failed: registration still present; git reported: ${error.trim()}")
                }
                FileOps.deleteRecursively(destination)
            }
        }
    }

    /**
     * Stages intent-to-add entries (so new files are visible) and returns a
     * binary-safe unified diff of the whole working tree against HEAD.
     *
     * The diff must be against HEAD — not `--cached` — because agent edits
     * mirrored into the worktree are unstaged modifications of tracked files,
     * which `git diff --cached` does not report. Diffing against HEAD captures
     * staged changes, unstaged tracked modifications, and intent-to-add
     * untracked files alike.
     */
    fun stagedDiff(worktree: File): String {
        requireOk(run(worktree, "add", "-N", "-A", "."), "git add -N -A")
        return requireOkRaw(run(worktree, "diff", "HEAD", "--no-color", "--binary"), "git diff HEAD")
    }

    fun unstagedWorkingTreeDiff(worktree: File): String =
        requireOk(run(worktree, "diff", "--no-color", "--binary"), "git diff")

    fun diffBetween(repo: File, from: String, to: String, pathspecs: List<String> = emptyList()): String {
        val args = mutableListOf("diff", "--no-color", "--binary", "$from..$to")
        args += pathspecs
        return requireOkRaw(run(repo, *args.toTypedArray()), "git diff $from..$to")
    }

    fun checkPatch(worktree: File, patch: File): ProcResult = run(
        worktree, "apply", "--check", "--whitespace=nowarn", patch.absolutePath, timeoutSeconds = 120
    )

    fun applyPatch(worktree: File, patch: File): ProcResult = run(
        worktree, "apply", "--whitespace=nowarn", patch.absolutePath, timeoutSeconds = 120
    )

    fun changedPaths(worktree: File): List<String> {
        requireOk(run(worktree, "add", "-N", "-A", "."), "git add -N -A")
        val out = requireOk(
            run(worktree, "diff", "HEAD", "--name-status", "--no-renames", "--no-color"),
            "git diff HEAD --name-status"
        )
        return out.lines().filter { it.isNotBlank() }
    }

    fun resetIndex(worktree: File) {
        run(worktree, "reset", "-q")
    }

    fun currentCommit(worktree: File): String = revParse(worktree, "HEAD")

    fun cleanUntrackedAndIgnored(worktree: File) {
        run(worktree, "clean", "-fdx")
    }

    fun statusPorcelain(worktree: File): String = requireOk(run(worktree, "status", "--porcelain"), "git status")
}

class GitException(message: String) : RuntimeException(message)
