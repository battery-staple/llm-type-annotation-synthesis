package pipeline.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BenchmarkManifestTest {
    @Test
    fun `applies defaults and resolves relative repository path`() {
        val dir = Files.createTempDirectory("benchmark-manifest").toFile()
        try {
            val file = dir.resolve("benchmarks.yaml")
            file.writeText(manifest("repos/demo"))

            val entry = BenchmarkManifestLoader.load(file, ProjectConfig()).single()

            assertEquals(dir.resolve("repos/demo").toString(), entry.repoUrl)
            assertEquals("repos/demo", entry.repoIdentity)
            assertEquals("main", entry.defaultBranch)
            assertEquals(BuildSection(), entry.build)
            assertEquals("17", entry.sourceCompatibility)
            assertEquals("17", entry.targetCompatibility)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `applies compatibility branch and partial build overrides`() {
        val dir = Files.createTempDirectory("benchmark-manifest-overrides").toFile()
        try {
            val file = dir.resolve("benchmarks.yaml")
            file.writeText(
                manifest(
                    "repos/demo",
                    extra = """
                        source_compatibility: "11"
                        target_compatibility: "8"
                        default_branch: trunk
                        build:
                          compile_task: buildJava
                    """.trimIndent()
                )
            )

            val entry = BenchmarkManifestLoader.load(file, ProjectConfig()).single()

            assertEquals("11", entry.sourceCompatibility)
            assertEquals("8", entry.targetCompatibility)
            assertEquals("trunk", entry.defaultBranch)
            assertEquals("buildJava", entry.build.compileTask)
            assertEquals("test", entry.build.testTask)
            assertEquals(BuildSection().copy(compileTask = "buildJava"), entry.build)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `falls back to global compatibility defaults`() {
        val dir = Files.createTempDirectory("benchmark-manifest-compat-defaults").toFile()
        try {
            val file = dir.resolve("benchmarks.yaml")
            file.writeText(manifest("repos/demo"))
            val config = ProjectConfig(
                environment = EnvironmentSection(sourceCompatibility = "11", targetCompatibility = "8")
            )

            val entry = BenchmarkManifestLoader.load(file, config).single()

            assertEquals("11", entry.sourceCompatibility)
            assertEquals("8", entry.targetCompatibility)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `benchmark source override also sets target`() {
        val dir = Files.createTempDirectory("benchmark-manifest-compat-source").toFile()
        try {
            val file = dir.resolve("benchmarks.yaml")
            file.writeText(manifest("repos/demo", extra = "source_compatibility: \"21\""))
            val config = ProjectConfig(
                environment = EnvironmentSection(sourceCompatibility = "11", targetCompatibility = "8")
            )

            val entry = BenchmarkManifestLoader.load(file, config).single()

            assertEquals("21", entry.sourceCompatibility)
            assertEquals("21", entry.targetCompatibility)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preserves repository URL verbatim`() {
        val dir = Files.createTempDirectory("benchmark-manifest-url").toFile()
        try {
            val file = dir.resolve("benchmarks.yaml")
            file.writeText(manifest("https://example.com/demo.git"))

            val entry = BenchmarkManifestLoader.load(file, ProjectConfig()).single()

            assertEquals("https://example.com/demo.git", entry.repoUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun manifest(repoUrl: String, extra: String = ""): String {
        val base = """
        benchmarks:
          - id: demo
            repo_url: "$repoUrl"
            commit_hash: ${"a".repeat(40)}
            parent_hash: ${"b".repeat(40)}
            regression_test_class: example.DemoTest
            regression_test_method: fails
        """.trimIndent()
        val extraBlock = extra.lines()
            .filter { it.isNotBlank() }
            .joinToString("\n") { "    $it" }
        return if (extraBlock.isEmpty()) base else "$base\n$extraBlock"
    }
}
