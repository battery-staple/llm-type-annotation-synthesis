package pipeline.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

// --- Process execution -------------------------------------------------------

data class ProcResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val output: String get() = buildString {
        if (stdout.isNotEmpty()) append(stdout)
        if (stderr.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(stderr)
        }
    }
}

object Processes {
    fun run(
        command: List<String>,
        cwd: File,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 600,
        stdin: String? = null
    ): ProcResult {
        val builder = ProcessBuilder(command).directory(cwd)
        builder.environment().apply {
            putAll(environment)
            // Keep PWD consistent with the actual working directory; tools that
            // resolve paths from PWD would otherwise target the pipeline checkout.
            this["PWD"] = cwd.absolutePath
        }
        val process = builder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { stdout.appendLine(it) } }
        }
        val errThread = Thread {
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderr.appendLine(it) } }
        }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()
        if (stdin != null) {
            runCatching {
                process.outputStream.bufferedWriter().use { it.write(stdin) }
            }
        } else {
            runCatching { process.outputStream.close() }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            terminateTree(process)
        }
        outThread.join(5_000)
        errThread.join(5_000)
        return ProcResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = !finished
        )
    }

    fun terminateTree(process: Process) {
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        runCatching { process.destroy() }
        descendants.forEach { runCatching { it.destroy() } }
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            runCatching { process.destroyForcibly() }
            descendants.forEach { runCatching { it.destroyForcibly() } }
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

// --- Hashing -----------------------------------------------------------------

object Hashing {
    fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256Bytes(text.toByteArray(Charsets.UTF_8))

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Canonical, length-prefixed field encoding. Field names are sorted so call
     * sites cannot accidentally rely on argument order, and each field is
     * prefixed with its name, a type tag, and its byte length to make
     * ("ab", "c") / ("a", "bc") style collisions impossible.
     */
    fun canonicalFields(fields: Map<String, Any?>): String {
        val sb = StringBuilder()
        fields.toSortedMap().forEach { (key, value) ->
            val (tag, encoded) = encode(value)
            sb.append(key.length).append(':').append(key)
            sb.append(tag)
            val bytes = encoded.toByteArray(Charsets.UTF_8)
            sb.append(bytes.size).append(':').append(encoded)
            sb.append(';')
        }
        return sb.toString()
    }

    fun canonicalHash(fields: Map<String, Any?>): String = sha256(canonicalFields(fields))

    private fun encode(value: Any?): Pair<String, String> = when (value) {
        null -> "N" to ""
        is Boolean -> "B" to value.toString()
        is Byte, is Short, is Int, is Long -> "I" to value.toString()
        is Float, is Double -> "F" to value.toString()
        is File -> "F" to value.absolutePath
        is String -> "S" to value
        is Map<*, *> -> "M" to canonicalFields(value.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> "L" to value.joinToString(",") { v ->
            val (t, e) = encode(v)
            "$t${e.toByteArray(Charsets.UTF_8).size}:$e"
        }
        is ByteArray -> "Y" to sha256Bytes(value)
        else -> "S" to value.toString()
    }
}

// --- Filesystem --------------------------------------------------------------

object FileOps {
    val DEFAULT_EXCLUDED_DIRS = setOf(
        ".git", ".gradle", "build", "target", "out", ".cache", "logs", "data",
        "eval-repos", "eval-fixtures", ".worktrees", ".scratch", ".idea", "node_modules",
        ".venv", "__pycache__"
    )

    val DEFAULT_EXCLUDED_FILE_SUFFIXES = listOf(
        ".patch", ".diff", "test_patch.diff", "triage_metadata.json",
        "test_results.xml", "suite_results.xml", "repair.patch", "annotations.patch"
    )

    fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.walkBottomUp().forEach { f ->
            runCatching { f.setWritable(true, false) }
            runCatching { f.delete() }
        }
        if (file.exists()) {
            runCatching { file.setWritable(true, false) }
            runCatching { file.deleteRecursively() }
        }
    }

    fun isWithin(child: File, parent: File): Boolean {
        val parentPath = parent.canonicalFile.toPath().normalize()
        val childPath = child.canonicalFile.toPath().normalize()
        return childPath.startsWith(parentPath)
    }

    /**
     * Copies a repository tree into a sanitized agent workspace. Symlinks are
     * skipped, VCS metadata and pipeline-owned directories are excluded, and
     * patch/report artifacts are never copied.
     */
    fun exportSanitized(
        sourceRoot: File,
        destination: File,
        excludeTests: Boolean = false,
        testReadOnly: Boolean = false
    ) {
        deleteRecursively(destination)
        destination.mkdirs()
        val sourcePath = sourceRoot.canonicalFile.toPath()
        Files.walk(sourcePath).use { stream ->
            stream.forEach { path ->
                if (path == sourcePath) return@forEach
                val relative = sourcePath.relativize(path)
                val first = relative.getName(0).toString()
                if (Files.isSymbolicLink(path)) return@forEach
                if (first in DEFAULT_EXCLUDED_DIRS) return@forEach
                if (excludeTests && relative.toString().replace('\\', '/').let { it.contains("/src/test/") || it.startsWith("src/test/") }) {
                    return@forEach
                }
                val target = destination.toPath().resolve(relative)
                if (path.isDirectory()) {
                    Files.createDirectories(target)
                } else if (path.isRegularFile()) {
                    val name = relative.fileName.toString()
                    if (DEFAULT_EXCLUDED_FILE_SUFFIXES.any { name.endsWith(it) }) return@forEach
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        makeGradleWrapperExecutable(destination)
        if (testReadOnly) makeTestsReadOnly(destination)
    }

    fun copyTree(sourceRoot: File, destination: File, excludeNames: Set<String> = emptySet()) {
        deleteRecursively(destination)
        destination.mkdirs()
        val sourcePath = sourceRoot.canonicalFile.toPath()
        Files.walk(sourcePath).use { stream ->
            stream.forEach { path ->
                if (path == sourcePath) return@forEach
                val relative = sourcePath.relativize(path)
                if (relative.any { it.toString() in excludeNames }) return@forEach
                if (Files.isSymbolicLink(path)) return@forEach
                val target = destination.toPath().resolve(relative)
                if (path.isDirectory()) Files.createDirectories(target)
                else if (path.isRegularFile()) {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        makeGradleWrapperExecutable(destination)
    }

    fun mirrorTreeInto(sourceRoot: File, destinationRoot: File, excludedRelativePaths: Set<String> = emptySet()) {
        val sourcePath = sourceRoot.canonicalFile.toPath()
        Files.walk(sourcePath).use { stream ->
            stream.forEach { path ->
                if (path == sourcePath) return@forEach
                val relative = sourcePath.relativize(path).toString().replace('\\', '/')
                if (excludedRelativePaths.any { relative == it || relative.startsWith("$it/") }) return@forEach
                if (Files.isSymbolicLink(path)) return@forEach
                if (relative.split('/').any { it in setOf(".gradle", "build", "target", "out", ".git") }) return@forEach
                val target = destinationRoot.toPath().resolve(relative)
                if (path.isDirectory()) Files.createDirectories(target)
                else if (path.isRegularFile()) {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    fun makeGradleWrapperExecutable(root: File) {
        listOf(root.resolve("gradlew")).forEach { wrapper ->
            if (wrapper.isFile) wrapper.setExecutable(true)
        }
    }

    fun makeTestsReadOnly(root: File) {
        val testDirs = mutableListOf<File>()
        root.walkTopDown().forEach { file ->
            val normalized = file.path.replace('\\', '/')
            if (file.isDirectory && (normalized.endsWith("/src/test") || normalized == "${root.path.replace('\\', '/')}/src/test")) {
                testDirs += file
            }
        }
        testDirs.forEach { dir ->
            dir.walkBottomUp().forEach { f ->
                if (f.isFile) f.setWritable(false, false)
                if (f.isDirectory) f.setWritable(false, false)
            }
        }
    }

    fun readText(file: File): String = file.readText()

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

fun Path.hasName(name: String): Boolean = fileName?.toString() == name
