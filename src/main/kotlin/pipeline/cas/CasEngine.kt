package pipeline.cas

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.serialization.json.JsonElement
import pipeline.stages.ArtifactFile
import pipeline.stages.ArtifactsManifest
import pipeline.stages.CasKey
import pipeline.stages.Sha256
import pipeline.stages.StageName
import pipeline.util.Hashing
import pipeline.util.PipelineJson

/** Canonical artifact basenames. Stages must not invent ad-hoc names. */
object ArtifactNames {
    const val MANIFEST = "manifest.json"
    const val WORKTREE_INFO = "worktree_info.json"

    const val TEST_PATCH = "test_patch.diff"
    const val TRIAGE_METADATA = "triage_metadata.json"
    const val TRIAGE_TRANSCRIPT = "triage_transcript.json"

    const val CUSTOM_QUALS_JAR = "custom-quals.jar"
    const val PLACEMENT_GUIDE = "placement_guide.md"
    const val CUSTOM_QUALS_SRC_DIR = "custom_quals_src"
    const val SYNTHESIS_DECISION = "synthesis_decision.json"
    const val SYNTHESIS_TRANSCRIPT = "synthesis_transcript.json"
    const val SYNTHESIS_METRICS = "synthesis_metrics.json"

    const val ANNOTATIONS_PATCH = "annotations.patch"
    const val AST_AUDIT = "ast_audit.json"
    const val INITIAL_CHECKER_ERRORS = "initial_checker_errors.log"
    const val APPLICATION_TRANSCRIPT = "application_transcript.json"

    const val REPAIR_PATCH = "repair.patch"
    const val FINAL_CHECKER_ERRORS = "final_checker_errors.log"
    const val ANTI_CHEAT_AUDIT = "anti_cheat_audit.json"
    const val GUARDRAIL_EVENTS = "guardrail_events.json"
    const val FIXER_TRANSCRIPT = "fixer_transcript.json"
    const val FIXER_METRICS = "fixer_metrics.json"

    const val TEST_RESULTS_XML = "test_results.xml"
    const val SUITE_RESULTS_XML = "suite_results.xml"
    const val VERIFICATION_SUMMARY = "verification_summary.json"
}

class StageArtifactWriter(
    private val stageName: StageName,
    val bundleDir: File,
    private val upstreamKeys: List<CasKey> = emptyList(),
    private val sampleIndex: Int = 0,
    private val sampleTemperature: Double = 0.0
) {
    private val written = linkedMapOf<String, File>()

    init {
        bundleDir.mkdirs()
    }

    fun writeText(name: String, content: String): File {
        val target = fileFor(name)
        target.parentFile?.mkdirs()
        target.writeText(content)
        written[name] = target
        return target
    }

    fun writeBytes(name: String, content: ByteArray): File {
        val target = fileFor(name)
        target.parentFile?.mkdirs()
        target.writeBytes(content)
        written[name] = target
        return target
    }

    fun writeJson(name: String, element: JsonElement): File =
        writeText(name, PipelineJson.json.encodeToString(JsonElement.serializer(), element))

    fun writeJsonString(name: String, content: String): File = writeText(name, content)

    fun copyIn(name: String, source: File): File {
        require(source.isFile) { "Artifact source does not exist: ${source.absolutePath}" }
        val target = fileFor(name)
        target.parentFile?.mkdirs()
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        written[name] = target
        return target
    }

    /** Registers an artifact written through another handle (for example a generated directory or JAR). */
    fun register(name: String, file: File = fileFor(name)): File {
        require(file.exists()) { "Artifact does not exist: ${file.absolutePath}" }
        written[name] = file
        return file
    }

    fun registerDirectory(prefix: String = ArtifactNames.CUSTOM_QUALS_SRC_DIR): File {
        val dir = bundleDir.resolve(prefix)
        require(dir.isDirectory) { "Artifact directory does not exist: ${dir.absolutePath}" }
        written[prefix] = dir
        return dir
    }

    fun buildManifest(cacheKey: CasKey, success: Boolean = true): ArtifactsManifest {
        val artifacts = written.toSortedMap().map { (name, file) ->
            if (file.isDirectory) {
                val listing = file.walkTopDown().filter { it.isFile }.toList()
                val digest = Hashing.sha256(
                    Hashing.canonicalFields(
                        listing.associate { it.relativeTo(file).invariantSeparatorsPath to Hashing.sha256(it) }
                    )
                )
                ArtifactFile(prefixName(name), Sha256(digest), listing.sumOf { it.length() })
            } else {
                ArtifactFile(prefixName(name), Sha256(Hashing.sha256(file)), file.length())
            }
        }
        return ArtifactsManifest(
            stageName = stageName,
            cacheKey = cacheKey,
            upstreamKeys = upstreamKeys,
            sampleIndex = sampleIndex,
            sampleTemperature = sampleTemperature,
            createdAt = Instant.now().toString(),
            success = success,
            artifacts = artifacts
        )
    }

    private fun prefixName(name: String): String = name

    private fun fileFor(name: String): File {
        require(name.isNotBlank() && !name.startsWith("/") && !name.contains("..")) { "Invalid artifact name: $name" }
        return bundleDir.resolve(name)
    }
}

data class CasBundle(val dir: File, val manifest: ArtifactsManifest)

class CasEngine(
    private val root: File,
    val enabled: Boolean = true
) {
    init {
        root.mkdirs()
    }

    fun stageDir(stage: StageName): File = root.resolve(stage.identifier)

    fun createStagingDir(stage: StageName): File {
        val parent = stageDir(stage)
        parent.mkdirs()
        val staging = parent.resolve(".staging-${System.nanoTime()}-${Thread.currentThread().id}")
        staging.mkdirs()
        return staging
    }

    fun publish(stage: StageName, key: CasKey, stagingDir: File, manifest: ArtifactsManifest): File {
        val target = stageDir(stage).resolve(key.toString())
        writingManifest(stagingDir, manifest)
        target.parentFile.mkdirs()
        if (target.exists()) {
            // A concurrent writer or a retry already published this deterministic key.
            FileOpsLocal.deleteRecursively(stagingDir)
            return target
        }
        try {
            Files.move(stagingDir.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(stagingDir.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    fun lookup(stage: StageName, key: CasKey, includeDiscards: Boolean = false): CasBundle? {
        if (!enabled) return null
        val dir = stageDir(stage).resolve(key.toString())
        if (!dir.isDirectory) return null
        val manifestFile = dir.resolve(ArtifactNames.MANIFEST)
        if (!manifestFile.isFile) return null
        val manifest = runCatching {
            PipelineJson.json.decodeFromString(ArtifactsManifest.serializer(), manifestFile.readText())
        }.getOrNull() ?: return null
        if (manifest.cacheKey != key || manifest.stageName != stage) return null
        if (!manifest.success && !includeDiscards) return null
        if (!validateArtifacts(dir, manifest)) return null
        return CasBundle(dir, manifest)
    }

    fun validateArtifacts(dir: File, manifest: ArtifactsManifest): Boolean {
        return manifest.artifacts.all { artifact ->
            val file = dir.resolve(artifact.path)
            if (artifact.path == ArtifactNames.CUSTOM_QUALS_SRC_DIR) {
                file.isDirectory && directoryDigest(file) == artifact.sha256.toString()
            } else {
                file.isFile && file.length() == artifact.sizeBytes && Hashing.sha256(file) == artifact.sha256.toString()
            }
        }
    }

    /** Must match the directory digest produced by [StageArtifactWriter.buildManifest]. */
    private fun directoryDigest(dir: File): String {
        val listing = dir.walkTopDown().filter { it.isFile }.toList()
        return Hashing.sha256(
            Hashing.canonicalFields(
                listing.associate { it.relativeTo(dir).invariantSeparatorsPath to Hashing.sha256(it) }
            )
        )
    }

    private fun writingManifest(stagingDir: File, manifest: ArtifactsManifest) {
        val file = stagingDir.resolve(ArtifactNames.MANIFEST)
        file.writeText(PipelineJson.json.encodeToString(ArtifactsManifest.serializer(), manifest))
    }

    /** Kept private to avoid exposing filesystem helpers to stages. */
    private object FileOpsLocal {
        fun deleteRecursively(file: File) {
            if (!file.exists()) return
            file.walkBottomUp().forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * Content fingerprint of the compiled stage/validator/runtime bytecode. The JVM's
 * code source is hashed on first use; a manually maintained version string is
 * deliberately not used.
 */
object StageCodeFingerprint {
    private val cached: Map<String, String> by lazy { computeAll() }

    fun forStage(stage: StageName): String = cached[stage.identifier] ?: cached["__all__"] ?: "missing"

    private fun computeAll(): Map<String, String> {
        val classpathRoots = classpathRoots()
        val bytes = linkedMapOf<String, String>()
        classpathRoots.forEach { root ->
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel.startsWith("pipeline/")) bytes[rel] = Hashing.sha256(file)
                }
            } else if (root.isFile && root.name.endsWith(".jar")) {
                runCatching {
                    java.util.zip.ZipFile(root).use { zip ->
                        zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("pipeline/") && it.name.endsWith(".class") }
                            .forEach { entry -> bytes[entry.name] = Hashing.sha256Bytes(zip.getInputStream(entry).readBytes()) }
                    }
                }
            }
        }
        val all = Hashing.sha256(Hashing.canonicalFields(bytes))
        val result = mutableMapOf<String, String>("__all__" to all)
        StageName.entries.forEach { stage ->
            val relevant = bytes.filterKeys { key ->
                key.contains("/stages/${stageClassFragment(stage)}") ||
                    key.startsWith("pipeline/validators/") ||
                    key.startsWith("pipeline/runners/") ||
                    key.startsWith("pipeline/cas/")
            }
            result[stage.identifier] = Hashing.sha256(Hashing.canonicalFields(relevant + ("__all__" to all)))
        }
        return result
    }

    private fun stageClassFragment(stage: StageName): String = when (stage) {
        StageName.INGESTION -> "Ingestion"
        StageName.TRIAGE -> "Triage"
        StageName.SYNTHESIS -> "Synthesis"
        StageName.APPLICATION -> "Application"
        StageName.REPAIR -> "Repair"
        StageName.VERIFICATION -> "Verification"
    }

    private fun classpathRoots(): List<File> {
        val roots = linkedSetOf<File>()
        runCatching {
            val location = StageCodeFingerprint::class.java.protectionDomain.codeSource.location
            roots += File(location.toURI())
        }
        val cp = System.getProperty("java.class.path", "")
        cp.split(File.pathSeparator).filter { it.isNotBlank() }.forEach { roots += File(it) }
        return roots.filter { it.exists() }
    }
}

/** Deterministic cache-key construction shared by every stage. */
object CacheKeys {
    fun compute(
        ctx: pipeline.stages.RunContext,
        stage: StageName,
        stageInput: Map<String, Any?>,
        upstreamKeys: List<CasKey>,
        configSlice: Map<String, Any?>,
        promptTemplate: String?,
        extra: Map<String, Any?> = emptyMap(),
        includeSampleIdentity: Boolean = true
    ): CasKey {
        val runtime = mapOf(
            "java_version" to System.getProperty("java.version"),
            "java_vendor" to System.getProperty("java.vendor"),
            "os_name" to System.getProperty("os.name"),
            "checker_framework_home" to ctx.config.environment.checkerFrameworkHome,
            "source_compatibility" to ctx.sourceCompatibility,
            "target_compatibility" to ctx.targetCompatibility,
            "gradle_wrapper" to ctx.build.wrapperExecutable
        )
        // Deterministic stages omit sample identity: a different seed cannot
        // change their output, so including it would only create duplicate
        // bundles for byte-identical artifacts.
        val fields: Map<String, Any?> = buildMap {
            put("stage_name", stage.identifier)
            put("stage_input", Hashing.canonicalHash(stageInput))
            put("upstream_artifacts", upstreamKeys.map { it.toString() })
            put("config_slice", Hashing.canonicalHash(configSlice))
            put("prompt_template", promptTemplate?.let { Hashing.sha256(it) } ?: "")
            put("stage_code", StageCodeFingerprint.forStage(stage))
            if (includeSampleIdentity) {
                put("sample_seed", ctx.sampleIndex)
                put("sample_temperature", ctx.sampleTemperature)
            }
            put("runtime_environment", Hashing.canonicalHash(runtime))
            put("extra", Hashing.canonicalHash(extra))
        }
        return CasKey.fromHex(Hashing.canonicalHash(fields))
    }
}

fun createJarFromDirectory(directory: File, jarFile: File) {
    jarFile.parentFile?.mkdirs()
    JarOutputStream(jarFile.outputStream().buffered()).use { jar ->
        directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
            val entryName = file.relativeTo(directory).invariantSeparatorsPath
            jar.putNextEntry(JarEntry(entryName))
            file.inputStream().use { it.copyTo(jar as OutputStream) }
            jar.closeEntry()
        }
    }
}
