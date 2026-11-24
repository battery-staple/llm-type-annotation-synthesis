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

    private class CanaryFixture(
        val root: File,
        val home: File,
        val customJar: File,
        val qualJar: File,
        val fakeJdk: FakeJdk,
    ) {
        val checkerJar: File get() = home.resolve("checker/dist/checker.jar")
    }

    /**
     * A stand-in for a JDK installation. Its `bin/java` is a POSIX shell script
     * that records every argument (one per line, quoted so embedded spaces in
     * paths survive) into an absolute args file, prints the configured
     * diagnostics and exits with the configured code.
     */
    private class FakeJdk(root: File, script: String) {
        val home: File = root.resolve("fake-jdk").also { it.resolve("bin").mkdirs() }
        val argsFile: File = root.resolve("java-args.txt")
        private val diagnosticsFile: File = root.resolve("java-diagnostics.txt")

        init {
            diagnosticsFile.writeText("")
            home.resolve("bin/java").apply {
                writeText(
                    buildString {
                        append("#!/bin/sh\n")
                        append("for arg in \"\$@\"; do\n")
                        append("  printf '%s\\n' \"\$arg\" >> '").append(argsFile.absolutePath).append("'\n")
                        append("done\n")
                        append("cat '").append(diagnosticsFile.absolutePath).append("'\n")
                        append(script).append('\n')
                    },
                )
                setExecutable(true)
            }
        }

        fun setDiagnostics(text: String) {
            diagnosticsFile.writeText(text)
        }

        fun recordedArgs(): List<String> = argsFile.readLines()
    }

    private fun runCanary(
        fixture: CanaryFixture,
        checkerHome: File = fixture.home,
        javaHome: String = fixture.fakeJdk.home.absolutePath,
        qualifiers: List<Fqcn> = listOf(qualifier),
    ): CheckerCanaryResult = CheckerCanary.run(
        checkerHome = checkerHome,
        customQualsJar = fixture.customJar,
        checkerQualJar = fixture.qualJar,
        processor = processor,
        qualifiers = qualifiers,
        javaHome = javaHome,
    )

    @Test
    fun `crash output is classified as a canary problem but not a harness failure`() = withFakeChecker(
        diagnostics = """
            error: PolicySignal is not a key in the directSuperMap.  Does it have a @SubtypeOf annotation?
            org.checkerframework.javacutil.TypeSystemError: PolicySignal is not a key in the directSuperMap.
        """.trimIndent(),
        exitCode = 1,
    ) { fixture ->
        val result = runCanary(fixture)
        assertTrue(result.invoked)
        assertFalse(result.harnessFailure)
        assertEquals(1, result.exitCode)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("directSuperMap"), result.problem)
        assertTrue(result.problem.contains("TypeSystemError"), result.problem)
        assertFalse(result.passed)
    }

    @Test
    fun `crash markers are classified even when the fake checker exits cleanly`() = withFakeChecker(
        diagnostics = "org.checkerframework.common.util.BugInCF: broken lattice",
    ) { fixture ->
        val result = runCanary(fixture)
        assertTrue(result.invoked)
        assertFalse(result.harnessFailure)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("BugInCF"), result.problem)
        assertFalse(result.passed)
    }

    @Test
    fun `ordinary type diagnostics are not canary failures`() = withFakeChecker(
        diagnostics = "Canary.java:3: error: [assignment] incompatible types",
    ) { fixture ->
        val result = runCanary(fixture)
        assertTrue(result.invoked)
        assertFalse(result.harnessFailure)
        assertNull(result.problem)
        assertTrue(result.passed)
        assertTrue(result.output.contains("incompatible types"))
    }

    @Test
    fun `paths with spaces are passed intact and checker bin javac is never used`() = withFakeChecker(
        rootPrefix = "checker canary test ",
    ) { fixture ->
        assertTrue(fixture.root.absolutePath.contains(' '), fixture.root.absolutePath)

        // Sentinel: if the canary ever falls back to checker/bin/javac, this
        // script runs and leaves a marker behind.
        val marker = fixture.root.resolve("javac-was-invoked.marker")
        fixture.home.resolve("checker/bin/javac").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\ntouch '${marker.absolutePath}'\n")
            setExecutable(true)
        }

        val result = runCanary(fixture)
        assertTrue(result.passed, result.problem ?: result.output)
        assertTrue(result.invoked)
        assertFalse(result.harnessFailure)

        // `$@` excludes argv[0], so the first recorded argument is `-jar`.
        val args = fixture.fakeJdk.recordedArgs()
        assertTrue(args.isNotEmpty(), "the fake java must have been launched and recorded its arguments")

        val jarIndex = args.indexOf("-jar")
        assertTrue(jarIndex >= 0, "expected a -jar argument in $args")
        assertEquals(fixture.checkerJar.absolutePath, args[jarIndex + 1])

        val cpIndex = args.indexOf("-cp")
        assertTrue(cpIndex >= 0, "expected a -cp argument in $args")
        assertEquals(
            listOf(fixture.customJar.absolutePath, fixture.qualJar.absolutePath)
                .joinToString(File.pathSeparator),
            args[cpIndex + 1],
        )

        val processorIndex = args.indexOf("-processor")
        assertTrue(processorIndex >= 0, "expected a -processor argument in $args")
        assertEquals(processor, args[processorIndex + 1])

        assertTrue(args.contains("-Aquals=${qualifier.value}"), "expected -Aquals intact in $args")

        val outIndex = args.indexOf("-d")
        assertTrue(outIndex >= 0, "expected a -d argument in $args")
        assertTrue(args[outIndex + 1].endsWith("classes"), "expected a classes output dir in $args")

        assertFalse(marker.exists(), "checker/bin/javac sentinel must never be executed")
    }

    @Test
    fun `launcher-style output is classified as a harness failure`() = withFakeChecker(
        diagnostics = "Error: Unable to access jarfile /Users/ro/Documents/Code/School/CS 6158/checker/dist/checker.jar",
        exitCode = 1,
    ) { fixture ->
        val result = runCanary(fixture)
        assertFalse(result.invoked)
        assertTrue(result.harnessFailure)
        assertFalse(result.passed)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("could not start"), result.problem)
    }

    @Test
    fun `missing checker jar is a harness failure`() = withTempDir { root ->
        val home = root.resolve("checker-home").also { it.resolve("checker/dist").mkdirs() }
        val fakeJdk = FakeJdk(root, "exit 0")
        val customJar = root.resolve("quals.jar").also { it.writeBytes(byteArrayOf()) }
        val qualJar = home.resolve("checker/dist/checker-qual.jar").also { it.writeBytes(byteArrayOf()) }

        val result = CheckerCanary.run(
            checkerHome = home,
            customQualsJar = customJar,
            checkerQualJar = qualJar,
            processor = processor,
            qualifiers = listOf(qualifier),
            javaHome = fakeJdk.home.absolutePath,
        )
        assertFalse(result.invoked)
        assertTrue(result.harnessFailure)
        assertFalse(result.passed)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("could not start"), result.problem)
        assertTrue(result.problem.contains("checker JAR"), result.problem)
        assertFalse(fakeJdk.argsFile.exists(), "java must not be launched when the checker JAR is missing")
    }

    @Test
    fun `missing java executable is a harness failure`() = withTempDir { root ->
        val home = root.resolve("checker-home").also { it.resolve("checker/dist").mkdirs() }
        home.resolve("checker/dist/checker.jar").writeBytes(byteArrayOf())
        val customJar = root.resolve("quals.jar").also { it.writeBytes(byteArrayOf()) }
        val qualJar = home.resolve("checker/dist/checker-qual.jar").also { it.writeBytes(byteArrayOf()) }
        val missingJdk = root.resolve("no-java-here").also { it.mkdirs() }

        val result = CheckerCanary.run(
            checkerHome = home,
            customQualsJar = customJar,
            checkerQualJar = qualJar,
            processor = processor,
            qualifiers = listOf(qualifier),
            javaHome = missingJdk.absolutePath,
        )
        assertFalse(result.invoked)
        assertTrue(result.harnessFailure)
        assertFalse(result.passed)
        assertNotNull(result.problem)
        assertTrue(result.problem.contains("could not start"), result.problem)
    }

    private inline fun withFakeChecker(
        diagnostics: String = "",
        exitCode: Int = 0,
        rootPrefix: String = "checker-canary-test",
        block: (CanaryFixture) -> Unit,
    ) {
        withTempDir(rootPrefix) { root ->
            val home = root.resolve("checker-home")
            home.resolve("checker/dist").mkdirs()
            home.resolve("checker/dist/checker.jar").writeBytes(byteArrayOf())
            val fakeJdk = FakeJdk(root, "exit $exitCode")
            fakeJdk.setDiagnostics(diagnostics)
            val customJar = root.resolve("quals.jar").also { it.writeBytes(byteArrayOf()) }
            val qualJar = home.resolve("checker/dist/checker-qual.jar").also { it.writeBytes(byteArrayOf()) }
            block(CanaryFixture(root, home, customJar, qualJar, fakeJdk))
        }
    }

    private inline fun withTempDir(prefix: String = "checker-canary-test", block: (File) -> Unit) {
        val dir = Files.createTempDirectory(prefix).toFile()
        try {
            block(dir)
        } finally {
            pipeline.util.FileOps.deleteRecursively(dir)
        }
    }
}
