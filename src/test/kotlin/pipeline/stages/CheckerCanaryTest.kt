package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckerCanaryTest {
    private val processor = "org.checkerframework.common.subtyping.SubtypingChecker"
    private val qualifier = Fqcn("demo.qual.PolicySignal")

    @Test
    fun `crash output is classified as a canary problem`() = withFakeChecker(
        """
        echo 'error: PolicySignal is not a key in the directSuperMap.  Does it have a @SubtypeOf annotation?'
        echo 'org.checkerframework.javacutil.TypeSystemError: PolicySignal is not a key in the directSuperMap.'
        exit 1
        """.trimIndent(),
    ) { home, customJar, qualJar ->
        val result = CheckerCanary.run(home, customJar, qualJar, processor, listOf(qualifier))
        assertTrue(result.invoked)
        assertEquals(1, result.exitCode)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("directSuperMap"), result.problem)
        assertTrue(result.problem.contains("TypeSystemError"), result.problem)
        assertFalse(result.passed)
    }

    @Test
    fun `crash markers are classified even when the fake checker exits cleanly`() = withFakeChecker(
        """
        echo 'org.checkerframework.common.util.BugInCF: broken lattice'
        exit 0
        """.trimIndent(),
    ) { home, customJar, qualJar ->
        val result = CheckerCanary.run(home, customJar, qualJar, processor, listOf(qualifier))
        assertTrue(result.invoked)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("BugInCF"), result.problem)
    }

    @Test
    fun `ordinary type diagnostics are not canary failures`() = withFakeChecker(
        """
        echo 'Canary.java:3: error: [assignment] incompatible types'
        exit 0
        """.trimIndent(),
    ) { home, customJar, qualJar ->
        val result = CheckerCanary.run(home, customJar, qualJar, processor, listOf(qualifier))
        assertTrue(result.invoked)
        assertNull(result.problem)
        assertTrue(result.passed)
        assertTrue(result.output.contains("incompatible types"))
    }

    @Test
    fun `missing checker javac is a validation problem`() = withTempDir { root ->
        val home = root.resolve("empty-checker-home")
        val customJar = root.resolve("quals.jar").also { it.writeBytes(byteArrayOf()) }
        val qualJar = root.resolve("checker-qual.jar").also { it.writeBytes(byteArrayOf()) }
        val result = CheckerCanary.run(home, customJar, qualJar, processor, listOf(qualifier))
        assertFalse(result.invoked)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("missing or not executable"), result.problem)
    }

    private inline fun withFakeChecker(script: String, block: (File, File, File) -> Unit) {
        withTempDir { root ->
            val home = root.resolve("checker-home")
            home.resolve("checker/bin").mkdirs()
            home.resolve("checker/dist").mkdirs()
            home.resolve("checker/bin/javac").apply {
                writeText("#!/bin/sh\n$script\n")
                setExecutable(true)
            }
            val customJar = root.resolve("quals.jar").also { it.writeBytes(byteArrayOf()) }
            val qualJar = home.resolve("checker/dist/checker-qual.jar").also { it.writeBytes(byteArrayOf()) }
            block(home, customJar, qualJar)
        }
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("checker-canary-test").toFile()
        try {
            block(dir)
        } finally {
            pipeline.util.FileOps.deleteRecursively(dir)
        }
    }
}
