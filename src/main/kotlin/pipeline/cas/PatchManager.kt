package pipeline.cas

import java.io.File
import pipeline.util.Git

data class ChangedPath(val status: String, val path: String) {
    val isAddition: Boolean get() = status.startsWith("A")
    val isDeletion: Boolean get() = status.startsWith("D")
    val isRename: Boolean get() = status.startsWith("R")
    val isModification: Boolean get() = status.startsWith("M")
}

/**
 * Generates and applies patches against an explicit Git index so newly created
 * files are visible (`git add -N`, then diff the whole working tree against
 * HEAD so unstaged tracked modifications are included too).
 */
class PatchManager(private val worktree: File) {
    fun generatePatch(): String = Git.stagedDiff(worktree)

    fun writePatch(destination: File): File {
        destination.parentFile?.mkdirs()
        destination.writeText(generatePatch())
        return destination
    }

    fun check(patch: File): Boolean = Git.checkPatch(worktree, patch).exitCode == 0

    fun apply(patch: File): Boolean = Git.applyPatch(worktree, patch).exitCode == 0

    fun changedPaths(): List<ChangedPath> = Git.changedPaths(worktree).mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 2) null else ChangedPath(parts[0], parts[1])
    }

    fun resetIndex() = Git.resetIndex(worktree)
}
