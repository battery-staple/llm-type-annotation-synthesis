package pipeline.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pipeline.stages.BenchmarkEntry
import pipeline.stages.BenchmarkId
import pipeline.stages.CommitHash
import pipeline.stages.Fqcn
import pipeline.stages.TestCoordinate

@Serializable
data class BenchmarkManifestEntry(
    val id: String,
    @SerialName("repo_url") val repoUrl: String,
    @SerialName("commit_hash") val commitHash: String,
    @SerialName("parent_hash") val parentHash: String,
    @SerialName("regression_test_class") val regressionTestClass: String,
    @SerialName("regression_test_method") val regressionTestMethod: String,
    @SerialName("test_patch") val testPatch: String? = null,
    val description: String = "",
    @SerialName("default_branch") val defaultBranch: String = "main",
    val build: BuildSection = BuildSection(),
    @SerialName("source_compatibility") val sourceCompatibility: String? = null,
    @SerialName("target_compatibility") val targetCompatibility: String? = null
)

@Serializable
data class BenchmarkManifest(val benchmarks: List<BenchmarkManifestEntry> = emptyList())

object BenchmarkManifestLoader {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun load(file: File, config: ProjectConfig): List<BenchmarkEntry> {
        val absolute = file.absoluteFile
        require(absolute.isFile) { "Benchmark manifest not found: ${absolute.absolutePath}" }
        val manifest = yaml.decodeFromString(BenchmarkManifest.serializer(), absolute.readText())
        val base = absolute.parentFile
        val seen = mutableSetOf<String>()
        return manifest.benchmarks.map { row ->
            require(seen.add(row.id)) { "Duplicate benchmark id '${row.id}' in ${file.path}" }
            val repoUrl = row.repoUrl.let { raw ->
                if (raw.contains("://") || raw.startsWith("git@") || raw.startsWith("file:")) raw
                else File(raw).let { f -> if (f.isAbsolute) f else File(base, raw) }.toPath().normalize().toString()
            }
            val source = row.sourceCompatibility ?: config.environment.sourceCompatibility
            val target = row.targetCompatibility ?: row.sourceCompatibility ?: config.environment.effectiveTargetCompatibility
            BenchmarkEntry(
                benchmarkId = BenchmarkId.parse(row.id),
                repoUrl = repoUrl,
                commitHash = CommitHash(row.commitHash),
                parentHash = CommitHash(row.parentHash),
                regressionTest = TestCoordinate(Fqcn(row.regressionTestClass), row.regressionTestMethod),
                testPatchFile = row.testPatch?.let { File(it).let { f -> if (f.isAbsolute) f else File(base, it) } },
                description = row.description,
                repoIdentity = row.repoUrl,
                defaultBranch = row.defaultBranch,
                build = row.build,
                sourceCompatibility = source,
                targetCompatibility = target
            )
        }
    }

    fun resolve(entries: List<BenchmarkEntry>, rawId: String): BenchmarkEntry? {
        val parsed = BenchmarkId.parse(rawId)
        return entries.firstOrNull {
            it.benchmarkId.canonical == rawId ||
                it.benchmarkId.alias == rawId ||
                it.benchmarkId == parsed ||
                it.benchmarkId.canonical == parsed.canonical
        }
    }
}
