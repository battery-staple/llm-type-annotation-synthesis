package pipeline.config

import java.io.File

/**
 * One repository state for standalone `pipeline preflight`. [commit] is the
 * commit to check: a benchmark's parent (C-1), a repository entry's
 * `preflight_commit`, or null for the head of [defaultBranch].
 */
data class PreflightTarget(
    val id: String,
    val repoUrl: String,
    val repoIdentity: String,
    val commit: String?,
    /** The benchmark's fixed commit; for repository entries, the checked commit itself. */
    val fixCommit: String?,
    val defaultBranch: String,
    val build: BuildSection,
    val sourceCompatibility: String,
    val targetCompatibility: String,
    val productionRoots: List<String>,
    val testRoots: List<String>,
    val testClasses: List<String>,
    val expected: String?,
    val expectedBasis: String?,
)

/** Reads preflight targets from a benchmark manifest (`benchmarks:`) or a crawler manifest (`repos:`). */
object PreflightTargets {
    fun load(file: File, config: ProjectConfig): List<PreflightTarget> {
        val text = file.absoluteFile.readText()
        val isBenchmarkManifest = Regex("""(?m)^benchmarks\s*:""").containsMatchIn(text)
        return if (isBenchmarkManifest) fromBenchmarks(file, config) else fromRepos(file, config)
    }

    private fun fromBenchmarks(file: File, config: ProjectConfig): List<PreflightTarget> {
        val rows = BenchmarkManifestLoader.rows(file).associateBy { it.id }
        return BenchmarkManifestLoader.load(file, config).map { entry ->
            val row = rows.getValue(entry.benchmarkId.canonical)
            PreflightTarget(
                id = row.id,
                repoUrl = entry.repoUrl,
                repoIdentity = entry.repoIdentity,
                commit = entry.parentHash.hash,
                fixCommit = entry.commitHash.hash,
                defaultBranch = entry.defaultBranch,
                build = entry.build,
                sourceCompatibility = entry.sourceCompatibility,
                targetCompatibility = entry.targetCompatibility,
                productionRoots = entry.productionRoots,
                testRoots = entry.testRoots,
                // The regression test usually arrives with the fix, so at C-1 it
                // is only a candidate; PreflightSupport falls back to another test.
                testClasses = listOfNotNull(row.preflightTestClass, row.regressionTestClass),
                expected = row.expectedPreflight,
                expectedBasis = row.expectedPreflightBasis,
            )
        }
    }

    private fun fromRepos(file: File, config: ProjectConfig): List<PreflightTarget> =
        CrawlerManifestLoader.load(file, config).map { target ->
            PreflightTarget(
                id = target.benchmarkId.canonical,
                repoUrl = target.repoUrl,
                repoIdentity = target.repoIdentity,
                commit = target.preflightCommit,
                fixCommit = target.preflightCommit,
                defaultBranch = target.defaultBranch,
                build = target.build,
                sourceCompatibility = target.sourceCompatibility,
                targetCompatibility = target.targetCompatibility,
                productionRoots = target.productionRoots,
                testRoots = target.testRoots,
                testClasses = listOfNotNull(target.preflightTestClass),
                expected = target.expectedPreflight,
                expectedBasis = target.expectedPreflightBasis,
            )
        }
}
