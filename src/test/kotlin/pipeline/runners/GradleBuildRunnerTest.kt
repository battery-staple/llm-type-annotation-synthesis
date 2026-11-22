package pipeline.runners

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pipeline.config.BuildSection
import pipeline.config.ProjectConfig
import pipeline.ports.TestStatus
import pipeline.stages.Fqcn
import pipeline.stages.TestCoordinate
import pipeline.util.FileOps

/**
 * Regression tests for the project-qualified Gradle invocation.
 *
 * An empty `subproject` must mean exactly the root project: CMSX includes a
 * `:regression` subproject, and an unqualified `test --tests ...` fans out
 * there, finds no matching tests, and exits non-zero even when the root
 * regression test passed.
 */
class GradleBuildRunnerTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { dir ->
            FakeGradle.install(dir)
        }

    private fun runner(build: BuildSection = BuildSection()): GradleBuildRunner =
        GradleBuildRunner(ProjectConfig(), build, "17", "17")

    @Test
    fun `empty subproject runs root-qualified cleanTest and test tasks`() = runBlocking {
        val dir = tempDir("gradle-runner-root")
        try {
            val result = runner().runTests(
                dir,
                testFilter = TestCoordinate(Fqcn("example.Sample"), "works")
            )
            val args = FakeGradle.args(dir)
            assertTrue(":cleanTest" in args, "cleanTest must be root-qualified: $args")
            assertTrue(":test" in args, "test must be root-qualified: $args")
            assertFalse("test" in args, "unqualified `test` would fan out to subprojects: $args")
            assertFalse("cleanTest" in args, "unqualified `cleanTest` would fan out to subprojects: $args")
            assertTrue("--tests" in args, "filter flag must be present: $args")
            assertTrue("example.Sample.works" in args, "filter must be present: $args")

            assertTrue(result.success, "a passing root test run must succeed: exit=${result.exitCode} ${result.output}")
            assertEquals(0, result.exitCode)
            assertEquals(1, result.testsFound)
            assertEquals(1, result.outcomes.count { it.status == TestStatus.PASSED })
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }

    @Test
    fun `explicit subproject still qualifies both tasks`() = runBlocking {
        val dir = tempDir("gradle-runner-subproject")
        try {
            runner(BuildSection(subproject = "module")).runTests(dir, testFilter = null)
            val args = FakeGradle.args(dir)
            assertTrue(":module:cleanTest" in args, "got: $args")
            assertTrue(":module:test" in args, "got: $args")
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }

    @Test
    fun `compile task is root-qualified too`() = runBlocking {
        val dir = tempDir("gradle-runner-compile")
        try {
            val result = runner().compile(dir, customQualsJar = null)
            assertTrue(result.success, "fake compile must succeed: ${result.output}")
            assertTrue(":compileJava" in FakeGradle.args(dir), "got: ${FakeGradle.args(dir)}")
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }

    @Test
    fun `xml without a zero exit code is not reported as success`() = runBlocking {
        val dir = tempDir("gradle-runner-failure")
        try {
            File(dir, "gradlew").writeText(
                FakeGradle.script(exitCode = 1)
            )
            val result = runner().runTests(dir, testFilter = null)
            assertEquals(1, result.exitCode)
            assertEquals(1, result.testsFound, "the stale/passing XML is still parsed for forensics")
            assertFalse(result.success, "a non-zero Gradle exit must not be masked by the presence of XML")
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }
}

/** Installs a fake `./gradlew` that records args and writes one JUnit XML. */
internal object FakeGradle {

    fun script(exitCode: Int = 0): String = """
        #!/usr/bin/env bash
        set -u
        printf '%s\n' "${'$'}@" > "${'$'}PWD/args.txt"
        mkdir -p build/test-results/test
        cat > build/test-results/test/TEST-example.Sample.xml <<'XML'
        <testsuite name="example.Sample" tests="1" failures="0" errors="0" skipped="0">
          <testcase classname="example.Sample" name="works" time="0.01"/>
        </testsuite>
        XML
        exit $exitCode
    """.trimIndent() + "\n"

    fun install(dir: File, exitCode: Int = 0) {
        val wrapper = File(dir, "gradlew")
        wrapper.writeText(script(exitCode))
        wrapper.setExecutable(true)
    }

    fun args(dir: File): List<String> =
        File(dir, "args.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
}
