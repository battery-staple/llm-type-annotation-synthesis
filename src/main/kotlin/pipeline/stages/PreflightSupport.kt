package pipeline.stages

import java.io.DataInputStream
import java.io.File
import pipeline.util.Git
import pipeline.validators.SourceRoots

/**
 * Pure helpers for [PreflightStage]: reason-code classification of build
 * output, the build-configuration hash behind the cache key, and facts read
 * from the tree (Gradle version, class-file level, test class).
 *
 * Every rule is a condition on build output or files, never a check on a
 * repository's name or path (Dockerization plan, "Fixes must be general").
 */
object PreflightSupport {

    // --- Reason-code classification --------------------------------------

    private val JDK_UNAVAILABLE = listOf(
        "No matching toolchains found",
        "Cannot find a Java installation on your machine",
        "No locally installed toolchains match",
        "Toolchain download repositories have not been configured",
        "auto-provisioning is disabled",
        "Toolchain auto-provisioning",
    )

    private val GRADLE_RUNTIME = listOf(
        "Unsupported class file major version",
        "Unsupported Java. ",
        "is not compatible with the Java version",
        "Gradle requires JVM",
        "Could not initialize class org.codehaus.groovy",
        "Your build is currently configured to use incompatible Java",
        // A build plugin that needs a newer JVM than the one running Gradle (jobrunr's Micronaut plugin).
        "Dependency requires at least JVM runtime version",
    )

    /**
     * Failures while Gradle configures the build rather than while it compiles.
     * From P3 on, P2 has already configured the same build without the init
     * script, so a configuration failure there was caused by the init script.
     */
    private val CONFIGURATION_FAILURE = listOf(
        "A problem occurred configuring",
        "An exception occurred applying plugin request",
        "Could not create task",
        "A problem occurred evaluating",
    )

    private val CHECKER_JDK = listOf(
        "Unrecognized option: --add-exports",
        "Unrecognized option: --add-opens",
        "Could not create the Java Virtual Machine",
        "The Checker Framework cannot run",
        "Checker Framework only supports",
    )

    private val DOCKER = listOf(
        "Could not find a valid Docker environment",
        "DockerClientProviderStrategy",
        "Cannot connect to the Docker daemon",
        "docker.sock",
    )

    /** Strong network signals only: an artifact that does not exist is not transient. */
    private val TRANSIENT = listOf(
        "UnknownHostException",
        "Connection refused",
        "connect timed out",
        "Network is unreachable",
        "Temporary failure in name resolution",
        "Read timed out",
    )

    fun isTransient(output: String): Boolean = TRANSIENT.any { it in output }

    fun requiresDocker(output: String): Boolean = DOCKER.any { it in output }

    /**
     * Classifies a failed Gradle invocation of [check]. The most specific
     * pattern wins: environment problems first, then the harness integration
     * failures bugs 1-4 produced, then the check's own default.
     */
    fun classify(check: String, output: String, timedOut: Boolean): String {
        if (timedOut) return if (check == "P7") PreflightReason.SUITE_TIMEOUT else PreflightReason.TIMEOUT
        if (JDK_UNAVAILABLE.any { it in output }) return PreflightReason.JDK_UNAVAILABLE
        if (GRADLE_RUNTIME.any { it in output }) return PreflightReason.GRADLE_RUNTIME_UNSUPPORTED
        if ("checker.init.gradle: unsupported annotation processor" in output) return PreflightReason.PROCESSOR_UNSUPPORTED
        if (check == "P1") return PreflightReason.UNKNOWN
        if (check == "P2") return PreflightReason.BASELINE_COMPILE_FAILED
        if ("bad class file" in output && "wrong version" in output) return PreflightReason.QUALIFIER_JAR_VERSION
        if ("is an explicit module" in output ||
            Regex("""package \S+ is not visible|does not read|module not found|is declared in module""").containsMatchIn(output)
        ) return PreflightReason.MODULE_MODE
        if ("warnings found and -Werror specified" in output) return PreflightReason.WERROR_INTERACTION
        if (Regex("""-Xplugin|Error ?Prone|errorprone""").containsMatchIn(errorLines(output))) {
            return PreflightReason.FOREIGN_COMPILER_PLUGIN
        }
        if (check == "P4" && Regex("""package lltas\.probe does not exist|cannot find symbol[\s\S]{0,200}Probe(Top|Bottom)""").containsMatchIn(output)) {
            return PreflightReason.QUALIFIER_NOT_ON_CLASSPATH
        }
        if (check == "P5" && CHECKER_JDK.any { it in output }) return PreflightReason.CHECKER_JDK_UNSUPPORTED
        if ("checker.init.gradle:" in output || Regex("""checker_init_\w+\$""").containsMatchIn(output) ||
            CONFIGURATION_FAILURE.any { it in output }
        ) {
            return PreflightReason.INIT_SCRIPT_FAILURE
        }
        if ((check == "P6" || check == "P7") && requiresDocker(output)) return PreflightReason.TEST_REQUIRES_DOCKER
        return PreflightReason.UNKNOWN
    }

    /** Compiler error lines and Gradle's "What went wrong" section, where plugin names appear in a failure. */
    private fun errorLines(output: String): String =
        output.lineSequence().filter { line ->
            ": error:" in line || line.startsWith("error:") || line.startsWith("> ")
        }.joinToString("\n")

    // --- Facts read from the tree ------------------------------------------

    private val distributionVersion = Regex("""gradle-(\d+(?:\.\d+)*)(?:-[\w.-]+?)?-(?:bin|all)\.zip""")

    /** The Gradle version in `gradle/wrapper/gradle-wrapper.properties`, or null. */
    fun wrapperGradleVersion(root: File): String? {
        val props = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (!props.isFile) return null
        val url = props.readLines().firstOrNull { it.trim().startsWith("distributionUrl") } ?: return null
        return distributionVersion.find(url)?.groupValues?.get(1)
    }

    /** True when [version] is at least [major].[minor]. */
    fun versionAtLeast(version: String, major: Int, minor: Int): Boolean {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        val actualMajor = parts.getOrElse(0) { 0 }
        val actualMinor = parts.getOrElse(1) { 0 }
        return actualMajor > major || (actualMajor == major && actualMinor >= minor)
    }

    /** "1.8" and "8" both mean Java 8; other values parse as integers. */
    fun javaLevel(raw: String?): Int? {
        val value = raw?.trim()?.removePrefix("VERSION_")?.replace('_', '.') ?: return null
        if (value.isEmpty()) return null
        return if (value.startsWith("1.")) value.removePrefix("1.").toIntOrNull() else value.substringBefore('.').toIntOrNull()
    }

    /**
     * The Java level the compile actually produced: the highest class-file
     * version among the classes in [classesDir], ignoring `module-info.class`
     * and multi-release `META-INF/versions/` entries. Null when no class exists.
     */
    fun observedLevel(classesDir: File?): Int? {
        if (classesDir == null || !classesDir.isDirectory) return null
        return classesDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
            .filter { "META-INF/versions/" !in it.invariantSeparatorsPath }
            .mapNotNull { file -> classFileMajor(file)?.let { it - 44 } }
            .maxOrNull()
    }

    private fun classFileMajor(file: File): Int? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != 0xCAFEBABE.toInt()) return null
            input.readUnsignedShort()
            input.readUnsignedShort()
        }
    }.getOrNull()

    /**
     * A test class to run in P6, in order: the configured class, the regression
     * test class if its source exists at C-1, and otherwise the first concrete
     * `*Test.java` containing `@Test` under the target project's test roots.
     * Returns the class name and its source file.
     */
    fun chooseTestClass(
        root: File,
        subprojectDir: String,
        testRoots: List<String>,
        candidates: List<Fqcn>,
    ): Pair<Fqcn, File>? {
        val root = root.absoluteFile
        val base = if (subprojectDir.isEmpty()) root else root.resolve(subprojectDir)
        if (!base.isDirectory) return null
        val testFiles = base.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", "node_modules") }
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { SourceRoots.isTestPath(it.relativeTo(root).invariantSeparatorsPath, testRoots) }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
        candidates.forEach { fqcn ->
            val suffix = "/" + fqcn.value.replace('.', '/') + ".java"
            testFiles.firstOrNull { it.invariantSeparatorsPath.endsWith(suffix) }?.let { return fqcn to it }
        }
        return testFiles
            .filter { it.name.endsWith("Test.java") }
            .sortedWith(compareBy({ it.length() }, { it.invariantSeparatorsPath }))
            .firstNotNullOfOrNull { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                val pkg = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(text)?.groupValues?.get(1)
                val concrete = "@Test" in text && !Regex("""abstract\s+class""").containsMatchIn(text)
                if (concrete) Fqcn(listOfNotNull(pkg, file.name.removeSuffix(".java")).joinToString(".")) to file else null
            }
    }

    // --- Cache key ----------------------------------------------------------

    /**
     * Hash of the build configuration at [commit]: every committed file except
     * Java sources under the production and test roots. Build logic in
     * `buildSrc` and in included builds (directories with their own settings
     * file, such as caffeine's `gradle/plugins`) is kept even where its path
     * matches a source root. Blob hashes come from `git ls-tree`, so nothing
     * is read from disk.
     */
    fun buildConfigHash(gitDir: File, commit: String, productionRoots: List<String>, testRoots: List<String>): String {
        val listing = Git.requireOk(Git.run(gitDir, "ls-tree", "-r", "--full-tree", commit), "git ls-tree $commit")
        val entries = listing.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val path = line.substring(tab + 1)
            val sha = line.substring(0, tab).split(' ').getOrNull(2) ?: return@mapNotNull null
            path to sha
        }.toList()
        val nestedBuilds = entries.map { it.first }
            .filter { it.substringAfterLast('/') in setOf("settings.gradle", "settings.gradle.kts") && '/' in it }
            .map { it.substringBeforeLast('/') + "/" } + listOf("buildSrc/")
        val kept = entries.filterNot { (path, _) ->
            path.endsWith(".java") &&
                nestedBuilds.none { path.startsWith(it) } &&
                (SourceRoots.isUnderRoots(path, productionRoots) || SourceRoots.isUnderRoots(path, testRoots))
        }
        return pipeline.util.Hashing.sha256(kept.joinToString("\n") { "${it.second} ${it.first}" })
    }

    /** Blob hash of [path] at [commit], or "missing". */
    fun blobHash(gitDir: File, commit: String, path: String): String {
        val result = Git.run(gitDir, "rev-parse", "$commit:$path")
        return if (result.exitCode == 0) result.stdout.trim() else "missing"
    }
}
