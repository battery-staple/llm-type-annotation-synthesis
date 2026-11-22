package pipeline.util

import java.io.File
import mu.KotlinLogging

/**
 * Environment access shared by the CLI and its child processes.
 *
 * A `.env` file is loaded once, before configuration parsing. The real process
 * environment always wins: `.env` only supplies values that are not already
 * set. Child processes inherit the real environment from [ProcessBuilder];
 * [applyTo] adds only the `.env` values that the process environment lacks.
 *
 * The file is parsed, not sourced: there is no shell expansion and no
 * `$(...)`/`$VAR` substitution.
 */
object Env {
    private val log = KotlinLogging.logger {}

    private val values = LinkedHashMap<String, String>()

    @Volatile
    private var source: File? = null

    private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Loads `.env` next to [configFile], falling back to the working directory.
     * `PIPELINE_ENV_FILE` overrides both locations. Idempotent per process.
     */
    fun loadFor(
        configFile: File,
        explicitEnvFile: File? = System.getenv("PIPELINE_ENV_FILE")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
    ) {
        if (source != null) return
        val configDir = configFile.absoluteFile.parentFile ?: File(".")
        val candidates = listOfNotNull(
            explicitEnvFile,
            configDir.resolve(".env"),
            File(".env").absoluteFile
        ).distinctBy { it.absoluteFile.toPath().normalize().toString() }
        val file = candidates.firstOrNull { it.isFile } ?: return
        load(file)
    }

    internal fun load(file: File) {
        val parsed = parse(file.readText())
        values.putAll(parsed)
        source = file.absoluteFile
        log.info { "Loaded ${parsed.size} environment variable(s) from ${file.absolutePath}" }
    }

    fun get(key: String): String? = System.getenv(key) ?: values[key]

    /** `.env` values that are not already present in the process environment. */
    fun forChild(): Map<String, String> = values.filterKeys { System.getenv(it) == null }

    /** Adds `.env` fallbacks without overriding values already in [environment]. */
    fun applyTo(environment: MutableMap<String, String>) {
        forChild().forEach { (key, value) -> environment.putIfAbsent(key, value) }
    }

    internal fun parse(text: String): Map<String, String> {
        val parsed = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (!keyPattern.matches(key)) return@forEach
            parsed[key] = decodeValue(line.substring(separator + 1).trim())
        }
        return parsed
    }

    private fun decodeValue(raw: String): String {
        if (raw.startsWith('"') || raw.startsWith('\'')) {
            val quote = raw.first()
            val decoded = StringBuilder()
            var escaped = false
            var index = 1
            while (index < raw.length) {
                val char = raw[index]
                if (quote == '"' && char == '\\' && !escaped) {
                    escaped = true
                    index++
                    continue
                }
                if (char == quote && !escaped) return decoded.toString()
                if (escaped) {
                    decoded.append(
                        when (char) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> char
                        }
                    )
                    escaped = false
                } else {
                    decoded.append(char)
                }
                index++
            }
            return raw
        }
        val comment = raw.indexOf(" #")
        return if (comment >= 0) raw.substring(0, comment).trimEnd() else raw
    }

    internal fun resetForTests() {
        values.clear()
        source = null
    }
}
