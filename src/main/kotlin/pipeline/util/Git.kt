package pipeline.util

import java.io.File

data class CommitMetadata(
    val hash: String,
    val parents: List<String>,
    val authorDate: String,
    val message: String
)

object Git {
    fun run(cwd: File, vararg args: String, timeoutSeconds: Long = 300, env: Map<String, String> = emptyMap()): ProcResult =
        Processes.run(listOf("git") + args.toList(), cwd, environment = env, timeoutSeconds = timeoutSeconds)

    fun requireOk(result: ProcResult, action: String): String {
        if (result.exitCode != 0) {
            throw GitException("$action failed (exit ${result.exitCode}): ${result.output.takeLast(4000)}")
        }
        return result.stdout.trim()
    }

    fun revParse(cwd: File, ref: String): String = requireOk(run(cwd, "rev-parse", ref), "git rev-parse $ref")

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
        if (!mirror.exists()) {
            val source = resolveRepoUrl(repoUrl)
            val result = run(evalReposDir, "clone", "--mirror", source, mirror.absolutePath, timeoutSeconds = 1800)
            requireOk(result, "git clone --mirror $source")
        } else {
            run(mirror, "fetch", "--all", "--prune", timeoutSeconds = 600)
        }
        return mirror
    }

    fun resolveRepoUrl(repoUrl: String): String {
        val file = File(repoUrl)
        return if (file.exists()) file.absolutePath else repoUrl
    }

    fun addWorktree(repo: File, destination: File, commit: String) {
        destination.parentFile?.mkdirs()
        FileOps.deleteRecursively(destination)
        requireOk(
            run(repo, "worktree", "add", "--detach", destination.absolutePath, commit, timeoutSeconds = 600),
            "git worktree add $destination"
        )
    }

    fun removeWorktree(repo: File, destination: File) {
        if (!destination.exists()) return
        run(repo, "worktree", "remove", "--force", destination.absolutePath)
        FileOps.deleteRecursively(destination)
        run(repo, "worktree", "prune")
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
