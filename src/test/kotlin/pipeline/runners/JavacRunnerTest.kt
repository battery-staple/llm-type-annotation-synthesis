package pipeline.runners

import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pipeline.util.FileOps

/**
 * JSqlParser compiles on a JDK 17 toolchain, which rejected a qualifier jar
 * compiled by the pipeline's JDK 21 ("bad class file ... wrong version 65.0").
 * The jar must be readable by every JDK in the envelope, whatever JVM runs the
 * pipeline.
 */
class JavacRunnerTest {

    @Test
    fun `qualifier jar is compiled for Java 8 class files`() {
        val root = Files.createTempDirectory("javac-runner").toFile()
        try {
            val source = root.resolve("src/lltas/probe/ProbeTop.java")
            source.parentFile.mkdirs()
            source.writeText(
                """
                package lltas.probe;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
                public @interface ProbeTop {}
                """.trimIndent()
            )
            val jar = root.resolve("out/quals.jar")
            val result = JavacRunner().compileAndPackageJar(listOf(source), jar, emptyList())

            assertTrue(result.success, "compile failed: ${result.diagnostics}")
            // -Xlint:-options silences the "--release 8 is obsolete" warning.
            assertFalse(result.diagnostics.any { it.kind == "WARNING" }, "unexpected warnings: ${result.diagnostics}")
            assertEquals(52, classFileMajorVersion(jar, "lltas/probe/ProbeTop.class"))
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `qualifier sources may use only Java 8 features`() {
        val root = Files.createTempDirectory("javac-runner-release").toFile()
        try {
            val source = root.resolve("src/q/UsesVar.java")
            source.parentFile.mkdirs()
            source.writeText("package q; public @interface UsesVar { } class Helper { void m() { var x = 1; } }")
            val result = JavacRunner().compileAndPackageJar(listOf(source), root.resolve("q.jar"), emptyList())
            assertFalse(result.success, "`var` is Java 10 and must fail under --release 8")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    private fun classFileMajorVersion(jar: File, entry: String): Int =
        JarFile(jar).use { zip ->
            DataInputStream(zip.getInputStream(zip.getEntry(entry))).use { input ->
                check(input.readInt() == 0xCAFEBABE.toInt())
                input.readUnsignedShort() // minor
                input.readUnsignedShort()
            }
        }
}
