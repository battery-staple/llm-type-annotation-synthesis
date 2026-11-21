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

/** In-process qualifier compiler: compiles sources and packages classes into a jar. */
class JavacRunner : JavacCompiler {

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
                val options = mutableListOf("-proc:none", "-d", tmpDir.absolutePath)
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
