package pipeline.validators

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pipeline.cas.ChangedPath
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.StageName

class FilesystemGuardTest {

    private fun worktree() = GitRepos.tempDir("fsguard")

    private fun audit(
        worktree: java.io.File,
        path: String,
        status: String = "M",
        stage: StageName = StageName.APPLICATION,
        allowPackageInfoAddition: Boolean = true,
        allowTestEdits: Boolean = true
    ) = FilesystemGuard.audit(
        worktree, listOf(ChangedPath(status, path)), stage,
        allowPackageInfoAddition, allowTestEdits
    )

    @Test
    fun `application permits src-main annotation edit`() {
        val dir = worktree()
        val result = audit(dir, "src/main/java/com/example/Foo.java")
        assertTrue(result.valid, "src/main modification must pass, got: ${result.violations}")
    }

    @Test
    fun `application permits new src-main package-info`() {
        val dir = worktree()
        val result = audit(dir, "src/main/java/com/example/package-info.java", status = "A")
        assertTrue(result.valid, "new src/main package-info must pass, got: ${result.violations}")
        assertTrue(FilesystemGuard.isPackageInfo("src/main/java/com/example/package-info.java"))
        assertTrue(FilesystemGuard.packageInfoAllowedPath("src/main/java/com/example/package-info.java"))
    }

    @Test
    fun `rejects build scripts`() {
        val dir = worktree()
        listOf("build.gradle", "build.gradle.kts", "settings.gradle", "pom.xml", "gradle.properties").forEach { path ->
            val result = audit(dir, path)
            assertTrue(!result.valid, "$path must be rejected")
            assertTrue(
                result.violations.any { it.category == GuardrailViolationCategory.BUILD_TAMPERING },
                "expected BUILD_TAMPERING for $path, got: ${result.violations}"
            )
        }
    }

    @Test
    fun `rejects astub files`() {
        val dir = worktree()
        val result = audit(dir, "src/main/java/com/example/foo.astub")
        assertTrue(!result.valid, ".astub must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.BUILD_TAMPERING },
            "expected BUILD_TAMPERING, got: ${result.violations}"
        )
    }

    @Test
    fun `rejects deletions`() {
        val dir = worktree()
        val result = audit(dir, "src/main/java/com/example/Foo.java", status = "D")
        assertTrue(!result.valid, "deletions must be rejected")
    }

    @Test
    fun `rejects additions other than package-info`() {
        val dir = worktree()
        val result = audit(dir, "src/main/java/com/example/New.java", status = "A")
        assertTrue(!result.valid, "additions must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.FILE_ADDITION },
            "expected FILE_ADDITION, got: ${result.violations}"
        )
    }

    @Test
    fun `rejects path escapes`() {
        val dir = worktree()
        listOf("../evil.java", "src/../../evil.java", "/etc/passwd").forEach { path ->
            val result = audit(dir, path)
            assertTrue(!result.valid, "$path must be rejected")
            assertTrue(
                result.violations.any { it.category == GuardrailViolationCategory.PATH_ESCAPE },
                "expected PATH_ESCAPE for $path, got: ${result.violations}"
            )
        }
    }

    @Test
    fun `rejects symlink escapes`() {
        val dir = worktree()
        val outside = GitRepos.tempDir("fsguard-outside").resolve("Evil.java")
        outside.writeText("public class Evil {}")
        val linkParent = dir.resolve("src/main/java/com/example")
        linkParent.mkdirs()
        Files.createSymbolicLink(
            linkParent.resolve("Link.java").toPath(), outside.toPath()
        )

        val result = audit(dir, "src/main/java/com/example/Link.java")
        assertTrue(!result.valid, "symlink escape must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.PATH_ESCAPE },
            "expected PATH_ESCAPE, got: ${result.violations}"
        )
    }

    @Test
    fun `repair rejects src-test edits`() {
        val dir = worktree()
        val result = audit(
            dir, "src/test/java/com/example/FooTest.java",
            stage = StageName.REPAIR, allowPackageInfoAddition = false, allowTestEdits = false
        )
        assertTrue(!result.valid, "test edits must be rejected in repair")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.TEST_TAMPERING },
            "expected TEST_TAMPERING, got: ${result.violations}"
        )
    }

    @Test
    fun `repair rejects additions`() {
        val dir = worktree()
        val result = audit(
            dir, "src/main/java/com/example/New.java", status = "A",
            stage = StageName.REPAIR, allowPackageInfoAddition = false, allowTestEdits = false
        )
        assertTrue(!result.valid, "additions must be rejected in repair")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.FILE_ADDITION },
            "expected FILE_ADDITION, got: ${result.violations}"
        )
    }

    @Test
    fun `repair rejects package-info changes`() {
        val dir = worktree()
        val result = audit(
            dir, "src/main/java/com/example/package-info.java",
            stage = StageName.REPAIR, allowPackageInfoAddition = false, allowTestEdits = false
        )
        assertTrue(!result.valid, "package-info must be frozen in repair")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.PACKAGE_INFO_CHANGE },
            "expected PACKAGE_INFO_CHANGE, got: ${result.violations}"
        )
    }

    @Test
    fun `application test edits follow allowTestEdits flag`() {
        val dir = worktree()
        val allowed = audit(dir, "src/test/java/com/example/FooTest.java", allowTestEdits = true)
        assertTrue(allowed.valid, "test edit should pass when allowed, got: ${allowed.violations}")
        val denied = audit(dir, "src/test/java/com/example/FooTest.java", allowTestEdits = false)
        assertTrue(!denied.valid, "test edit must fail when not allowed")
    }

    @Test
    fun `auditPatchPaths parses headers and rejects escapes`() {
        val patch = """
            diff --git a/src/main/java/com/example/Foo.java b/src/main/java/com/example/Foo.java
            --- a/src/main/java/com/example/Foo.java
            +++ b/src/main/java/com/example/Foo.java
            diff --git a/src/test/java/com/example/FooTest.java b/src/test/java/com/example/FooTest.java
            new file mode 100644
            --- /dev/null
            +++ b/src/test/java/com/example/FooTest.java
            rename from src/main/java/com/example/Old.java
            rename to src/main/java/com/example/New.java
        """.trimIndent()

        val paths = FilesystemGuard.auditPatchPaths(patch)
        assertTrue(paths.contains("src/main/java/com/example/Foo.java"), "got: $paths")
        assertTrue(paths.contains("src/test/java/com/example/FooTest.java"), "got: $paths")
        assertTrue(paths.contains("src/main/java/com/example/Old.java"), "got: $paths")
        assertTrue(paths.contains("src/main/java/com/example/New.java"), "got: $paths")

        val evil = """
            diff --git a/../evil.java b/../evil.java
            --- a/../evil.java
            +++ b/../evil.java
            diff --git a/x.java b/x.java
            --- /dev/null
            +++ /etc/passwd
        """.trimIndent()
        assertEquals(listOf("x.java"), FilesystemGuard.auditPatchPaths(evil))
    }
}
