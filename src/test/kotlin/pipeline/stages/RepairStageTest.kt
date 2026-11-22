package pipeline.stages

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pipeline.util.Git

class RepairStageTest {

    @Test
    fun `annotated baseline commit disables configured signing`() {
        val worktree = Files.createTempDirectory("annotated-baseline-test").toFile()
        try {
            assertEquals(0, Git.run(worktree, "init").exitCode)
            assertEquals(0, Git.run(worktree, "config", "user.name", "Test").exitCode)
            assertEquals(0, Git.run(worktree, "config", "user.email", "test@example.com").exitCode)
            assertEquals(0, Git.run(worktree, "config", "commit.gpgsign", "false").exitCode)

            worktree.resolve("Example.java").writeText("class Example {}\n")
            assertEquals(0, Git.run(worktree, "add", "-A").exitCode)
            assertEquals(0, Git.run(worktree, "commit", "-m", "baseline").exitCode)

            assertEquals(0, Git.run(worktree, "config", "commit.gpgsign", "true").exitCode)
            assertEquals(
                0,
                Git.run(worktree, "config", "gpg.program", "__pipeline_test_missing_signer__").exitCode,
            )
            worktree.resolve("Example.java").appendText("// annotated\n")

            commitAnnotatedBaseline(worktree)

            assertEquals("annotated baseline", Git.metadata(worktree, "HEAD").message)
            assertTrue(Git.statusPorcelain(worktree).isBlank())
        } finally {
            worktree.deleteRecursively()
        }
    }
}
