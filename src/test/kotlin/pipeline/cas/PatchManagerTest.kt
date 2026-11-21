package pipeline.cas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pipeline.validators.GitRepos

class PatchManagerTest {

    @Test
    fun `unstaged tracked modification is captured in patch and changed paths`() {
        val dir = GitRepos.tempDir("patchmgr")
        GitRepos.init(dir, mapOf("src/Foo.java" to "class Foo {}\n"))
        dir.resolve("src/Foo.java").writeText("class Foo { int x; }\n")

        val changed = PatchManager(dir).changedPaths()
        assertTrue(
            changed.any { it.status.startsWith("M") && it.path == "src/Foo.java" },
            "unstaged modification must be reported, got: $changed"
        )

        val patch = PatchManager(dir).generatePatch()
        assertTrue(patch.isNotBlank(), "patch must not be empty for unstaged tracked modification")
        assertTrue(patch.contains("src/Foo.java"), "patch must mention the modified file")
        assertTrue(patch.contains("+class Foo { int x; }"), "patch must contain the new line")
    }

    @Test
    fun `new untracked file is captured via intent-to-add`() {
        val dir = GitRepos.tempDir("patchmgr")
        GitRepos.init(dir, mapOf("src/Foo.java" to "class Foo {}\n"))
        dir.resolve("src/Bar.java").writeText("class Bar {}\n")

        val changed = PatchManager(dir).changedPaths()
        assertTrue(
            changed.any { it.status.startsWith("A") && it.path == "src/Bar.java" },
            "new file must be reported as added, got: $changed"
        )
        assertTrue(PatchManager(dir).generatePatch().contains("src/Bar.java"))
    }

    @Test
    fun `staged modification is captured`() {
        val dir = GitRepos.tempDir("patchmgr")
        GitRepos.init(dir, mapOf("src/Foo.java" to "class Foo {}\n"))
        dir.resolve("src/Foo.java").writeText("class Foo { int x; }\n")
        GitRepos.runGit(dir, "add", "-A")

        val changed = PatchManager(dir).changedPaths()
        assertTrue(changed.any { it.path == "src/Foo.java" }, "staged modification must be reported")
        assertTrue(PatchManager(dir).generatePatch().isNotBlank(), "patch must not be empty for staged change")
    }

    @Test
    fun `clean tree yields empty patch and no changed paths`() {
        val dir = GitRepos.tempDir("patchmgr")
        GitRepos.init(dir, mapOf("src/Foo.java" to "class Foo {}\n"))

        assertEquals(emptyList(), PatchManager(dir).changedPaths())
        assertTrue(PatchManager(dir).generatePatch().isBlank(), "clean tree must produce an empty patch")
    }
}
