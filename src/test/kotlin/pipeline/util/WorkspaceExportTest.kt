package pipeline.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceExportTest {

    /**
     * RoaringBitmap, caffeine, and wiremock ship AGENTS.md; caffeine and wiremock
     * ship CLAUDE.md (caffeine under .claude/). OpenCode reads these as
     * instructions, and a project-level OpenCode config could re-enable tools.
     */
    @Test
    fun `export drops agent instruction and config files at any depth`() {
        val root = Files.createTempDirectory("export-agent-files").toFile()
        try {
            val source = root.resolve("repo")
            mapOf(
                "AGENTS.md" to "root instructions",
                "CLAUDE.md" to "root instructions",
                "opencode.json" to "{}",
                "opencode.jsonc" to "{}",
                ".opencode/agent/build.md" to "agent",
                ".claude/CLAUDE.md" to "nested",
                "module/AGENTS.md" to "module instructions",
                "src/main/java/App.java" to "class App {}",
                "docs/agents-guide.md" to "ordinary docs",
            ).forEach { (rel, text) ->
                source.resolve(rel).apply { parentFile.mkdirs() }.writeText(text)
            }
            val destination = root.resolve("workspace")
            FileOps.exportSanitized(source, destination)

            val exported = destination.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(destination).invariantSeparatorsPath }.toSet()
            assertEquals(setOf("src/main/java/App.java", "docs/agents-guide.md"), exported)
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    /**
     * JSqlParser's build calls `git describe` during configuration, so helpers
     * failed in a workspace without `.git`. The snapshot holds one commit with
     * the current files and no project history.
     */
    @Test
    fun `workspace snapshot is a one-commit repository of the current files`() {
        val root = Files.createTempDirectory("workspace-snapshot").toFile()
        try {
            val workspace = root.resolve("workspace")
            workspace.resolve("src/App.java").apply { parentFile.mkdirs() }.writeText("class App {}")
            workspace.resolve(".gitignore").writeText("src/\n")
            Git.initSnapshot(workspace)

            assertEquals("1", Git.requireOk(Git.run(workspace, "rev-list", "--all", "--count"), "rev-list"))
            val tracked = Git.requireOk(Git.run(workspace, "ls-files"), "ls-files").lines().toSet()
            assertTrue("src/App.java" in tracked, "ignored files must still be committed: $tracked")
            val status = Git.requireOk(Git.run(workspace, "status", "--porcelain"), "status")
            assertEquals("", status)
            val describe = Git.run(workspace, "describe", "--tags", "--always", "--dirty=-SNAPSHOT")
            assertEquals(0, describe.exitCode, describe.output)
            assertFalse(describe.stdout.contains("SNAPSHOT"))
        } finally {
            FileOps.deleteRecursively(root)
        }
    }
}
