package pipeline.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertSame

class GitWorktreeLockTest {
    @Test
    fun `worktree operations share one lock regardless of repository path`() {
        assertSame(Git.worktreeLockFor(File("mirror.git")), Git.worktreeLockFor(File("linked-worktree")))
    }
}
