package pipeline.stages

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.validators.GitRepos

/**
 * A qualifier in a package the project also owns stays invisible to a module
 * project even with --add-reads, because a package belongs to one module.
 */
class ProjectPackagesTest {

    @Test
    fun `parses package declarations and ignores other lines`() {
        val packages = ProjectPackages.parse(
            """
            package com.example.app;
              package   com.example.app.util ;
            // package not.a.declaration;
            import com.example.other;
            """.trimIndent()
        )
        assertEquals(setOf("com.example.app", "com.example.app.util"), packages)
    }

    @Test
    fun `reports qualifier packages the project declares`() {
        val problems = ProjectPackages.collisions(
            qualifierPackages = setOf("com.example.app", "lltas.qual"),
            projectPackages = setOf("com.example.app", "com.example.app.util"),
        )
        assertEquals(1, problems.size)
        assertTrue("`com.example.app`" in problems.single())
        assertTrue(ProjectPackages.collisions(setOf("com.example.qual"), setOf("com.example.app")).isEmpty())
    }

    @Test
    fun `reads packages at a commit, not the working tree`() {
        val dir = Files.createTempDirectory("project-packages").toFile()
        try {
            GitRepos.init(dir, mapOf("src/main/java/com/example/App.java" to "package com.example;\nclass App {}\n"))
            val commit = Git.revParse(dir, "HEAD")
            dir.resolve("src/main/java/com/example/Later.java").writeText("package com.example.later;\nclass Later {}\n")

            val packages = assertNotNull(ProjectPackages.atCommit(dir, commit))
            assertEquals(setOf("com.example"), packages)
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }
}
