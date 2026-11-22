package pipeline.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pipeline.stages.BenchmarkId
import pipeline.validators.SourceRoots

@Serializable
data class CrawlerRepoEntry(
    val id: String,
    @SerialName("repo_url") val repoUrl: String,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val build: BuildSection = BuildSection(),
    @SerialName("source_compatibility") val sourceCompatibility: String? = null,
    @SerialName("target_compatibility") val targetCompatibility: String? = null,
    @SerialName("production_roots") val productionRoots: List<String>? = null,
    @SerialName("test_roots") val testRoots: List<String>? = null
)

@Serializable
data class CrawlerManifest(val repos: List<CrawlerRepoEntry> = emptyList())

data class CrawlerRepoTarget(
    val benchmarkId: BenchmarkId,
    val repoUrl: String,
    val repoIdentity: String,
    val defaultBranch: String,
    val build: BuildSection,
    val sourceCompatibility: String,
    val targetCompatibility: String,
    val productionRoots: List<String>,
    val testRoots: List<String>
)

object CrawlerManifestLoader {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun load(file: File, config: ProjectConfig): List<CrawlerRepoTarget> {
        val absolute = file.absoluteFile
        require(absolute.isFile) { "Crawler manifest not found: ${absolute.absolutePath}" }
        val manifest = yaml.decodeFromString(CrawlerManifest.serializer(), absolute.readText())
        val base = absolute.parentFile
        val seen = mutableSetOf<String>()
        return manifest.repos.map { row ->
            require(seen.add(row.id)) { "Duplicate repo id '${row.id}' in ${file.path}" }
            val repoUrl = row.repoUrl.let { raw ->
                if (raw.contains("://") || raw.startsWith("git@") || raw.startsWith("file:")) raw
                else File(raw).let { f -> if (f.isAbsolute) f else File(base, raw) }.toPath().normalize().toString()
            }
            val source = row.sourceCompatibility ?: config.environment.sourceCompatibility
            val target = row.targetCompatibility ?: row.sourceCompatibility ?: config.environment.effectiveTargetCompatibility
            CrawlerRepoTarget(
                benchmarkId = BenchmarkId.parse(row.id),
                repoUrl = repoUrl,
                repoIdentity = row.repoUrl,
                defaultBranch = row.defaultBranch,
                build = row.build,
                sourceCompatibility = source,
                targetCompatibility = target,
                productionRoots = SourceRoots.withDefaults(row.productionRoots, SourceRoots.DEFAULT_PRODUCTION_ROOTS),
                testRoots = SourceRoots.withDefaults(row.testRoots, SourceRoots.DEFAULT_TEST_ROOTS)
            )
        }
    }

    fun resolve(targets: List<CrawlerRepoTarget>, rawId: String): CrawlerRepoTarget? {
        val parsed = BenchmarkId.parse(rawId)
        return targets.firstOrNull {
            it.benchmarkId.canonical == rawId ||
                it.benchmarkId.alias == rawId ||
                it.benchmarkId == parsed ||
                it.benchmarkId.canonical == parsed.canonical
        }
    }
}
