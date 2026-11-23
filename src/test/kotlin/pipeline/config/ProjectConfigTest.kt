package pipeline.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectConfigTest {
    @Test
    fun `concurrency defaults and validates positive limits`() {
        assertEquals(8, ConcurrencySection().agents)
        assertEquals(2, ConcurrencySection().builds)
        assertFailsWith<IllegalArgumentException> { ConcurrencySection(agents = 0) }
        assertFailsWith<IllegalArgumentException> { ConcurrencySection(builds = 0) }
    }
    @Test
    fun `resolves relative paths against config directory`() {
        val dir = Files.createTempDirectory("project-config").toFile()
        try {
            val file = dir.resolve("project.yaml")
            file.writeText(
                """
                triage:
                  manifest_file: manifests/benchmarks.yaml
                paths:
                  eval_repos_dir: data/repos
                  database: data/pipeline.db
                environment:
                  checker_framework_home: tools/checker
                cache:
                  stages_dir: cache/stages
                """.trimIndent()
            )

            val config = ProjectConfig.load(file)

            assertEquals(dir.resolve("manifests/benchmarks.yaml").toString(), config.triage.manifestFile)
            assertEquals(dir.resolve("data/repos").toString(), config.paths.evalReposDir)
            assertEquals(dir.resolve("data/pipeline.db").toString(), config.paths.databaseFile().path)
            assertEquals(dir.resolve("tools/checker").toString(), config.environment.checkerFrameworkHome)
            assertEquals(dir.resolve("cache/stages").toString(), config.cache.stagesDir)
            assertEquals(file.absoluteFile, config.sourceFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejects unsupported builtin checker`() {
        val dir = Files.createTempDirectory("project-config-builtin").toFile()
        try {
            val file = dir.resolve("project.yaml")
            file.writeText(
                """
                synthesis:
                  builtin_checker: "example.UnknownChecker"
                """.trimIndent()
            )

            val error = assertFailsWith<IllegalArgumentException> { ProjectConfig.load(file) }

            assertTrue(error.message.orEmpty().contains("synthesis.builtin_checker"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ignores legacy project and logging sections`() {
        val dir = Files.createTempDirectory("project-config-legacy").toFile()
        try {
            val file = dir.resolve("project.yaml")
            file.writeText(
                """
                triage:
                  mode: crawler
                  manifest_file: benchmarks.yaml
                paths:
                  eval_repos_dir: repos
                project:
                  name: legacy-project
                logging:
                  level: debug
                """.trimIndent()
            )

            val config = ProjectConfig.load(file)

            assertEquals(pipeline.stages.TriageMode.CRAWLER, config.triage.mode)
            assertEquals(dir.resolve("repos").toString(), config.paths.evalReposDir)
            assertEquals(dir.resolve("data/pipeline.db").toString(), config.paths.databaseFile().path)
            assertEquals("", config.environment.javaHome)
            assertEquals(0.0, config.sample.temperature)
        } finally {
            dir.deleteRecursively()
        }
    }
}
