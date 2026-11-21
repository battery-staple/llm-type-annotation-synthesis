package pipeline.stages

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import pipeline.cas.ArtifactNames
import pipeline.cas.CacheKeys
import pipeline.cas.CasEngine
import pipeline.cas.StageArtifactWriter
import pipeline.db.CommitTriageUpdate
import pipeline.ports.BuildRunner
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.util.Hashing
import pipeline.util.PipelineJson

/**
 * Workspace ingestion and Gradle cache pre-warming.
 *
 * Allocates an ephemeral worktree at clean C-1 from a bare mirror, records
 * ingestion metadata on the `commits` row, and pre-warms the Gradle cache by
 * compiling the unpatched tree. Network/repository outages surface as
 * [StageResult.TransientFailure]; an unpatched tree that does not compile is a
 * deterministic [StageResult.Discard] (`build_broken_unpatched`).
 *
 * The returned [IngestionWorktree] carries absolute worktree handles for the
 * orchestrator, but the CAS bundle only ever stores the commit hashes and a
 * repo-relative worktree path, never an absolute path.
 */
class IngestionStage(private val buildRunner: BuildRunner) : PipelineStage<IngestionInput, IngestionWorktree> {
    override val stageName: StageName = StageName.INGESTION

    private val log = KotlinLogging.logger {}

    override fun computeCacheKey(ctx: RunContext, input: IngestionInput): CasKey {
        val cwd = File(System.getProperty("user.dir"))
        val stageInput = mapOf(
            "repo_identity" to repoIdentity(input.repoUrl, cwd),
            "commit" to input.commitHash.hash,
            "parent" to input.parentHash.hash
        )
        val configSlice = mapOf(
            "source_compatibility" to ctx.config.environment.sourceCompatibility,
            "target_compatibility" to ctx.config.environment.effectiveTargetCompatibility,
            "gradle_wrapper" to ctx.config.build.wrapperExecutable,
            "project_name" to ctx.config.project.name
        )
        return CacheKeys.compute(
            ctx = ctx,
            stage = stageName,
            stageInput = stageInput,
            upstreamKeys = emptyList(),
            configSlice = configSlice,
            promptTemplate = null
        )
    }

    override suspend fun execute(ctx: RunContext, input: IngestionInput): StageResult<IngestionWorktree> {
        val repoName = repoBaseName(input.repoUrl)
        val evalReposDir = ctx.repoDir.absoluteFile.parentFile ?: File("eval-repos")
        log.info { "Ingestion: ensuring mirror for repo=$repoName commit=${input.commitHash} parent=${input.parentHash}" }

        val mirror = try {
            Git.ensureMirror(input.repoUrl, evalReposDir, repoName)
        } catch (t: Throwable) {
            log.warn { "Ingestion: mirror fetch failed (transient): ${t.message}" }
            return StageResult.TransientFailure(
                error = RuntimeException("Mirror fetch failed for ${input.repoUrl}: ${t.message}", t),
                stageTurnsTaken = 0,
                transcript = emptyList()
            )
        }

        try {
            Git.addWorktree(mirror, ctx.worktreeDir, input.parentHash.hash)
        } catch (t: Throwable) {
            log.warn { "Ingestion: worktree allocation failed (transient): ${t.message}" }
            return StageResult.TransientFailure(
                error = RuntimeException("Worktree allocation failed at parent ${input.parentHash}: ${t.message}", t),
                stageTurnsTaken = 0,
                transcript = emptyList()
            )
        }
        log.info { "Ingestion: worktree allocated at parent=${input.parentHash}" }

        val metadata = try {
            Git.metadata(mirror, input.commitHash.hash)
        } catch (t: Throwable) {
            log.warn { "Ingestion: commit metadata read failed (transient): ${t.message}" }
            return StageResult.TransientFailure(
                error = RuntimeException("Cannot read metadata for commit ${input.commitHash}: ${t.message}", t),
                stageTurnsTaken = 0,
                transcript = emptyList()
            )
        }

        ctx.database.updateCommit(
            CommitTriageUpdate(
                commitHash = input.commitHash.hash,
                triageStatus = "pending",
                authorDate = metadata.authorDate,
                commitMsg = metadata.message
            )
        )

        log.info { "Ingestion: pre-warming Gradle cache via ${ctx.config.build.compileTask}" }
        val prewarm = try {
            buildRunner.compile(ctx.worktreeDir)
        } catch (t: Throwable) {
            log.warn { "Ingestion: pre-warm threw (transient): ${t.message}" }
            return StageResult.TransientFailure(
                error = RuntimeException("Pre-warm compile threw: ${t.message}", t),
                stageTurnsTaken = 0,
                transcript = emptyList()
            )
        }
        log.info { "Ingestion: pre-warm exit=${prewarm.exitCode} success=${prewarm.success}" }

        if (!prewarm.success) {
            if (isTransientOutput(prewarm.output)) {
                log.warn { "Ingestion: pre-warm failed with network/transient signature; retryable" }
                return StageResult.TransientFailure(
                    error = RuntimeException("Transient pre-warm failure: ${prewarm.output.take(2000)}"),
                    stageTurnsTaken = 0,
                    transcript = emptyList()
                )
            }
            log.info { "Ingestion: unpatched C-1 does not compile; discarding as build_broken_unpatched" }
            ctx.database.updateCommit(
                CommitTriageUpdate(
                    commitHash = input.commitHash.hash,
                    triageStatus = "discarded_build_broken",
                    discardReason = DiscardReason.BUILD_BROKEN_UNPATCHED.name.lowercase(),
                    compilerErrorLog = prewarm.output.take(8000)
                )
            )
            return StageResult.Discard(
                reason = DiscardReason.BUILD_BROKEN_UNPATCHED,
                details = "Unpatched parent ${input.parentHash} does not compile: ${prewarm.output.take(2000)}"
            )
        }

        val cas = CasEngine(ctx.casDir)
        val stagingDir = cas.createStagingDir(stageName)
        val writer = StageArtifactWriter(
            stageName = stageName,
            bundleDir = stagingDir,
            upstreamKeys = emptyList(),
            sampleIndex = ctx.sampleIndex,
            sampleTemperature = ctx.sampleTemperature
        )
        val cwd = File(System.getProperty("user.dir"))
        val relativeWorktree = runCatching {
            cwd.canonicalFile.toPath().relativize(ctx.worktreeDir.canonicalFile.toPath()).toString()
        }.getOrElse { ctx.worktreeDir.name }
        writer.writeJson(
            ArtifactNames.WORKTREE_INFO,
            buildJsonObject {
                put("commit", JsonPrimitive(input.commitHash.hash))
                put("parent", JsonPrimitive(input.parentHash.hash))
                put("worktree", JsonPrimitive(relativeWorktree))
            }
        )
        val key = computeCacheKey(ctx, input)
        val manifest = writer.buildManifest(key)
        log.info { "Ingestion: success commit=${input.commitHash} parent=${input.parentHash} artifacts=${manifest.artifacts.size}" }
        return StageResult.Success(
            data = IngestionWorktree(
                commitHash = input.commitHash,
                parentHash = input.parentHash,
                worktreeDir = ctx.worktreeDir,
                repoDir = ctx.worktreeDir
            ),
            casKey = key,
            artifactsManifest = manifest,
            bundleDir = stagingDir
        )
    }

    /**
     * Rebuilds the typed output from a published bundle. The cached bundle
     * cannot resurrect the original ephemeral worktree path, so both
     * [IngestionWorktree.worktreeDir] and [IngestionWorktree.repoDir] point at
     * the bundle directory; the orchestrator re-materializes the real worktree
     * from the stored commit hashes before handing the value to triage.
     */
    override fun restoreCached(bundleDir: File, manifest: ArtifactsManifest): IngestionWorktree? {
        return runCatching {
            val infoFile = bundleDir.resolve(ArtifactNames.WORKTREE_INFO)
            require(infoFile.isFile) { "missing ${ArtifactNames.WORKTREE_INFO}" }
            val obj = PipelineJson.json.decodeFromString(
                kotlinx.serialization.json.JsonObject.serializer(),
                infoFile.readText()
            )
            val commit = obj.getValue("commit").toString().trim('"')
            val parent = obj.getValue("parent").toString().trim('"')
            IngestionWorktree(
                commitHash = CommitHash(commit),
                parentHash = CommitHash(parent),
                worktreeDir = bundleDir,
                repoDir = bundleDir
            )
        }.getOrElse {
            log.warn { "Ingestion: restoreCached failed: ${it.message}" }
            null
        }
    }

    companion object {
        /** Basename of a repo URL or local path, minus a trailing `.git`. */
        fun repoBaseName(repoUrl: String): String {
            val trimmed = repoUrl.trimEnd('/')
            val last = trimmed.substringAfterLast('/').substringAfterLast('\\')
            return last.removeSuffix(".git").ifBlank { "repo" }
        }

        /**
         * Normalized repo identity for cache keys. Never an absolute path:
         * project-relative when the repo path sits under [cwd], otherwise the
         * repo basename. Remote URLs use their trailing path segment.
         */
        fun repoIdentity(repoUrl: String, cwd: File): String {
            if ("://" in repoUrl) return repoBaseName(repoUrl)
            val noSuffix = repoUrl.removeSuffix(".git")
            val file = File(noSuffix)
            val abs = if (file.isAbsolute) file else cwd.resolve(noSuffix)
            return if (FileOps.isWithin(abs, cwd)) {
                abs.canonicalFile.relativeTo(cwd.canonicalFile).invariantSeparatorsPath
            } else {
                abs.name.ifBlank { "repo" }
            }
        }

        private val TRANSIENT_PATTERNS = listOf(
            "could not resolve",
            "could not download",
            "connection",
            "unknownhost",
            "timed out",
            "read timed out",
            "temporary failure",
            "network is unreachable",
            "connection refused",
            "connection reset"
        )

        fun isTransientOutput(output: String): Boolean {
            val lower = output.lowercase()
            return TRANSIENT_PATTERNS.any { it in lower }
        }

        /** Content hash helper kept local so cache keys hash file bytes, never paths. */
        fun contentHashOrMissing(file: File?): String =
            if (file != null && file.isFile) runCatching { Hashing.sha256(file) }.getOrDefault("missing")
            else "missing"
    }
}
