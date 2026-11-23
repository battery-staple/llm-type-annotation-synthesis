package pipeline.runners

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import pipeline.ports.JavacCompiler
import pipeline.ports.JavacDiagnostic
import pipeline.ports.JavacResult

/**
 * In-process qualifier compiler: compiles sources and packages classes into a jar.
 *
 * The jar is loaded by every JVM that touches the target project: the javac that
 * compiles it (often an older Gradle toolchain JDK), the javac running
 * `checkerPass`, and test JVMs that reflect on the annotations. Without
 * `--release`, the class files would carry the pipeline JVM's version (65 on
 * JDK 21), which a JDK 17 toolchain rejects with "bad class file ... wrong
 * version". A fixed `--release 8` (class-file version 52) is readable by every
 * JDK in the supported envelope and does not depend on a project's configured
 * Java level, which may be a default rather than a fact.
 */
class JavacRunner : JavacCompiler {

    companion object {
        /** Qualifier sources may use only Java 8 features (TYPE_USE arrived in 8). */
        const val QUALIFIER_RELEASE = "8"
    }

    override fun compileAndPackageJar(
        sourceFiles: List<File>,
        outputJar: File,
        classpath: List<File>
    ): JavacResult {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return JavacResult(
                success = false,
                exitCode = 1,
                diagnostics = listOf(
                    JavacDiagnostic(
                        kind = "ERROR",
                        lineNumber = null,
                        columnNumber = null,
                        message = "No system Java compiler available (running on a JRE without javac)"
                    )
                )
            )
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val tmpDir = Files.createTempDirectory("javac-classes-").toFile()
        try {
            val fileManager = compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8)
            fileManager.use { manager ->
                manager.setLocation(
                    javax.tools.StandardLocation.CLASS_OUTPUT,
                    listOf(tmpDir)
                )
                val options = mutableListOf(
                    "--release", QUALIFIER_RELEASE,
                    // JDK 21 warns that --release 8 is obsolete; the warning is expected.
                    "-Xlint:-options",
                    "-proc:none",
                    "-d", tmpDir.absolutePath,
                )
                if (classpath.isNotEmpty()) {
                    options += "-cp"
                    options += classpath.joinToString(File.pathSeparator) { it.absolutePath }
                }
                val units = manager.getJavaFileObjectsFromFiles(sourceFiles)
                val task = compiler.getTask(null, manager, diagnostics, options, null, units)
                val ok = task.call()
                val collected = diagnostics.diagnostics.map { d ->
                    JavacDiagnostic(
                        kind = d.kind.toString(),
                        lineNumber = runCatching { d.lineNumber }.getOrNull()?.takeIf { it >= 0 },
                        columnNumber = runCatching { d.columnNumber }.getOrNull()?.takeIf { it >= 0 },
                        message = runCatching { d.getMessage(null) }.getOrNull() ?: d.toString()
                    )
                }
                if (!ok) {
                    return JavacResult(success = false, exitCode = 1, diagnostics = collected)
                }
                outputJar.parentFile?.mkdirs()
                JarOutputStream(outputJar.outputStream().buffered()).use { jar ->
                    tmpDir.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .forEach { classFile ->
                            val relative = tmpDir.toPath().relativize(classFile.toPath()).toString().replace('\\', '/')
                            jar.putNextEntry(JarEntry(relative))
                            classFile.inputStream().use { it.copyTo(jar) }
                            jar.closeEntry()
                        }
                }
                return JavacResult(success = true, exitCode = 0, diagnostics = collected)
            }
        } finally {
            runCatching { tmpDir.deleteRecursively() }
        }
    }
}
