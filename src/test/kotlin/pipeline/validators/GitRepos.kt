package pipeline.validators

import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

internal object GitRepos {

    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    fun runGit(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        assertTrue(code == 0, "git ${args.joinToString(" ")} failed (exit $code): $output")
        return output
    }

    fun init(dir: File, files: Map<String, String>) {
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@example.com")
        runGit(dir, "config", "user.name", "Test")
        files.forEach { (rel, text) ->
            val target = dir.resolve(rel)
            target.parentFile.mkdirs()
            target.writeText(text)
        }
        runGit(dir, "add", "-A")
        runGit(dir, "commit", "-q", "-m", "baseline")
    }
}
