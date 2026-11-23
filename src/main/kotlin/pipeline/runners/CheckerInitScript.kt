package pipeline.runners

import java.io.File
import pipeline.config.BuildSection

/**
 * The one checker init script, shared by every harness Gradle invocation and by
 * the repair agent's `run-checker.sh`. Keeping a single source prevents the
 * agent-side checker from drifting from the harness checker that scores it.
 */
object CheckerInitScript {
    const val RESOURCE = "/templates/checker.init.gradle"
    const val FILE_NAME = "checker.init.gradle"

    fun template(): String =
        CheckerInitScript::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: error("$FILE_NAME template not found on classpath")

    /** Writes the bundled template to `<dir>/checker.init.gradle` and returns it. */
    fun writeTo(dir: File): File {
        dir.mkdirs()
        return dir.resolve(FILE_NAME).also { it.writeText(template()) }
    }
}

/**
 * Project-qualifies [task]. An empty subproject means exactly the root project,
 * so `test` does not fan out to unrelated subprojects (CMSX includes
 * `:regression`, where a `--tests` filter matches no tests and fails the whole
 * invocation).
 */
internal fun qualifiedTask(build: BuildSection, task: String): String {
    val sub = build.subproject.trim().trim(':')
    return if (sub.isEmpty()) ":$task" else ":$sub:$task"
}
