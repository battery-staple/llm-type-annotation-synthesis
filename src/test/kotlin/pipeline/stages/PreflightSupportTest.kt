package pipeline.stages

import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pipeline.util.FileOps
import pipeline.util.Git
import pipeline.validators.GitRepos

class PreflightSupportTest {

    // --- Classification: outputs as observed on the eval repositories ------

    @Test
    fun `bug 1 - a qualifier jar from a newer JDK is qualifier_jar_version`() {
        val output = """
            bad class file: /h/probe-quals.jar(/lltas/probe/ProbeTop.class)
                class file has wrong version 65.0, should be 61.0
        """.trimIndent()
        assertEquals(PreflightReason.QUALIFIER_JAR_VERSION, PreflightSupport.classify("P3", output, false))
    }

    @Test
    fun `bug 2 - a module that cannot read the harness jars is module_mode`() {
        val output = "/w/src/main/java/demo/app/Account.java:2: error: package lltas.probe is not visible"
        assertEquals(PreflightReason.MODULE_MODE, PreflightSupport.classify("P3", output, false))
    }

    @Test
    fun `bugs 3 and 4 - a harness warning under -Werror is werror_interaction`() {
        val output = """
            warning: Cannot find annotation method 'value()' in type 'SubtypeOf'
            error: warnings found and -Werror specified
        """.trimIndent()
        assertEquals(PreflightReason.WERROR_INTERACTION, PreflightSupport.classify("P3", output, false))
    }

    @Test
    fun `a failure thrown by the init script is init_script_failure`() {
        // RoaringBitmap before Phase 0: addAll on an immutable compilerArgs list.
        val output = """
            > Could not create task ':bsi:checkerPass'.
               > java.lang.UnsupportedOperationException (no error message)
            Caused by: java.lang.UnsupportedOperationException
            	at checker_init_dl9n252lsck344psn79iqnphv${'$'}_run_closure3.doCall(checker.init.gradle:365)
        """.trimIndent()
        assertEquals(PreflightReason.INIT_SCRIPT_FAILURE, PreflightSupport.classify("P3", output, false))
    }

    /** wiremock: an init-script `allprojects` block broke its buildSrc catalog accessors. */
    @Test
    fun `a configuration failure after a clean baseline is init_script_failure`() {
        val output = """
            * What went wrong:
            An exception occurred applying plugin request [id: 'wiremock.common-conventions']
            > Failed to apply plugin 'wiremock.common-conventions'.
               > Extension of type 'LibrariesForLibs' does not exist.
        """.trimIndent()
        assertEquals(PreflightReason.INIT_SCRIPT_FAILURE, PreflightSupport.classify("P3", output, false))
        // In P2 the init script is absent, so the same failure is a broken baseline.
        assertEquals(PreflightReason.BASELINE_COMPILE_FAILED, PreflightSupport.classify("P2", output, false))
    }

    @Test
    fun `error prone in a failing compile is foreign_compiler_plugin`() {
        val output = "> Task :compileJava FAILED\n/w/A.java:3: error: [ErrorProne] An unhandled exception was thrown by the Error Prone static analysis plugin."
        assertEquals(PreflightReason.FOREIGN_COMPILER_PLUGIN, PreflightSupport.classify("P3", output, false))
    }

    @Test
    fun `a JDK 8 checker fork is checker_jdk_unsupported`() {
        val output = "Unrecognized option: --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED\nError: Could not create the Java Virtual Machine."
        assertEquals(PreflightReason.CHECKER_JDK_UNSUPPORTED, PreflightSupport.classify("P5", output, false))
    }

    @Test
    fun `environment failures win over the check's default`() {
        val toolchain = "> No matching toolchains found for requested specification: {languageVersion=26, vendor=any}"
        assertEquals(PreflightReason.JDK_UNAVAILABLE, PreflightSupport.classify("P1", toolchain, false))
        assertEquals(PreflightReason.JDK_UNAVAILABLE, PreflightSupport.classify("P2", toolchain, false))
        val runtime = "BUG! exception in phase 'semantic analysis': Unsupported class file major version 71"
        assertEquals(PreflightReason.GRADLE_RUNTIME_UNSUPPORTED, PreflightSupport.classify("P1", runtime, false))
        // jobrunr: its Micronaut build plugin needs Gradle itself to run on JDK 25.
        val plugin = """
            > Could not resolve io.micronaut.gradle:micronaut-gradle-plugin:5.0.2.
                 > Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.
        """.trimIndent()
        assertEquals(PreflightReason.GRADLE_RUNTIME_UNSUPPORTED, PreflightSupport.classify("P1", plugin, false))
    }

    @Test
    fun `defaults per check`() {
        assertEquals(PreflightReason.BASELINE_COMPILE_FAILED, PreflightSupport.classify("P2", "Compilation failed", false))
        assertEquals(PreflightReason.UNKNOWN, PreflightSupport.classify("P3", "something new", false))
        assertEquals(
            PreflightReason.QUALIFIER_NOT_ON_CLASSPATH,
            PreflightSupport.classify("P4", "/w/LltasProbe.java:6: error: package lltas.probe does not exist", false),
        )
    }

    @Test
    fun `timeouts are suite_timeout in P7 and timeout elsewhere`() {
        assertEquals(PreflightReason.SUITE_TIMEOUT, PreflightSupport.classify("P7", "", true))
        assertEquals(PreflightReason.TIMEOUT, PreflightSupport.classify("P5", "", true))
    }

    @Test
    fun `docker requirements are recognized in test checks`() {
        val output = "java.lang.IllegalStateException: Could not find a valid Docker environment. Please see logs and check configuration"
        assertEquals(PreflightReason.TEST_REQUIRES_DOCKER, PreflightSupport.classify("P7", output, false))
        assertTrue(PreflightSupport.requiresDocker(output))
    }

    @Test
    fun `only strong network signals are transient`() {
        assertTrue(PreflightSupport.isTransient("java.net.UnknownHostException: repo.maven.apache.org"))
        // A missing artifact is deterministic (for example a mavenLocal()-only dependency).
        assertFalse(PreflightSupport.isTransient("Could not resolve all files for configuration ':compileClasspath'."))
    }

    @Test
    fun `every classification is a known reason code`() {
        listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7").forEach { check ->
            listOf("", "bad class file wrong version", "warnings found and -Werror specified").forEach { output ->
                assertTrue(PreflightSupport.classify(check, output, false) in PreflightReason.ALL)
            }
        }
    }

    // --- Facts read from the tree ------------------------------------------

    @Test
    fun `reads the wrapper's Gradle version`() = withDir { root ->
        fun props(url: String) = root.resolve("gradle/wrapper/gradle-wrapper.properties").apply {
            parentFile.mkdirs()
            writeText("distributionBase=GRADLE_USER_HOME\ndistributionUrl=$url\n")
        }
        props("https\\://services.gradle.org/distributions/gradle-9.9.1-all.zip")
        assertEquals("9.9.1", PreflightSupport.wrapperGradleVersion(root))
        props("https\\://services.gradle.org/distributions/gradle-8.5-bin.zip")
        assertEquals("8.5", PreflightSupport.wrapperGradleVersion(root))
        props("https\\://services.gradle.org/distributions/gradle-9.4.0-rc-1-bin.zip")
        assertEquals("9.4.0", PreflightSupport.wrapperGradleVersion(root))
        assertTrue(PreflightSupport.versionAtLeast("7.0", 7, 0))
        assertTrue(PreflightSupport.versionAtLeast("10.1", 7, 0))
        assertFalse(PreflightSupport.versionAtLeast("6.9.4", 7, 0))
    }

    @Test
    fun `java levels parse in every spelling`() {
        assertEquals(8, PreflightSupport.javaLevel("1.8"))
        assertEquals(8, PreflightSupport.javaLevel("VERSION_1_8"))
        assertEquals(11, PreflightSupport.javaLevel("11"))
        assertEquals(21, PreflightSupport.javaLevel("VERSION_21"))
        assertNull(PreflightSupport.javaLevel(null))
    }

    /** RoaringBitmap compiles at release 8 but declares nothing; the class files say what the build did. */
    @Test
    fun `observed level is the highest class-file version, ignoring module-info and versioned entries`() = withDir { dir ->
        classFile(dir.resolve("a/A.class"), 52)
        classFile(dir.resolve("a/B.class"), 55)
        classFile(dir.resolve("module-info.class"), 53)
        classFile(dir.resolve("META-INF/versions/17/a/A.class"), 61)
        assertEquals(11, PreflightSupport.observedLevel(dir))
        assertNull(PreflightSupport.observedLevel(dir.resolve("missing")))
    }

    @Test
    fun `test class choice prefers candidates and skips abstract or empty classes`() = withDir { root ->
        val tests = root.resolve("core/src/test/java/com/x")
        tests.mkdirs()
        tests.resolve("BaseTest.java").writeText("package com.x; abstract class BaseTest { @Test void t() {} }")
        tests.resolve("HelperTest.java").writeText("package com.x; class HelperTest { }")
        tests.resolve("RealTest.java").writeText("package com.x; class RealTest { @Test void t() {} }")
        tests.resolve("PreferredTest.java").writeText("package com.x; class PreferredTest { @Test void longerBodyHere() {} }")

        val auto = PreflightSupport.chooseTestClass(root, "core", listOf("src/test"), listOf(Fqcn("com.x.Missing")))
        assertEquals("com.x.RealTest", auto?.first?.value)
        val preferred = PreflightSupport.chooseTestClass(root, "core", listOf("src/test"), listOf(Fqcn("com.x.PreferredTest")))
        assertEquals("com.x.PreferredTest", preferred?.first?.value)
        assertNull(PreflightSupport.chooseTestClass(root, "absent", listOf("src/test"), emptyList()))
    }

    // --- Cache key ----------------------------------------------------------

    @Test
    fun `build configuration hash ignores Java sources but not build logic`() = withDir { root ->
        GitRepos.init(
            root,
            mapOf(
                "build.gradle" to "plugins { id 'java' }",
                "src/main/java/a/A.java" to "class A {}",
                "src/test/java/a/ATest.java" to "class ATest {}",
                "buildSrc/src/main/java/Conv.java" to "class Conv {}",
                "gradle/plugins/settings.gradle.kts" to "",
                "gradle/plugins/src/main/java/P.java" to "class P {}",
            )
        )
        fun hash() = PreflightSupport.buildConfigHash(root, Git.revParse(root, "HEAD"), listOf("src/main"), listOf("src/test"))
        fun commit(path: String, text: String) {
            root.resolve(path).writeText(text)
            GitRepos.runGit(root, "commit", "-qam", "change $path", "--no-gpg-sign")
        }
        val base = hash()
        commit("src/main/java/a/A.java", "class A { int x; }")
        commit("src/test/java/a/ATest.java", "class ATest { int y; }")
        assertEquals(base, hash(), "production and test sources must not change the key")

        commit("buildSrc/src/main/java/Conv.java", "class Conv { int z; }")
        val afterBuildSrc = hash()
        assertNotEquals(base, afterBuildSrc, "buildSrc is build logic")
        commit("gradle/plugins/src/main/java/P.java", "class P { int w; }")
        val afterIncluded = hash()
        assertNotEquals(afterBuildSrc, afterIncluded, "an included build is build logic")
        commit("build.gradle", "plugins { id 'java-library' }")
        assertNotEquals(afterIncluded, hash())
    }

    private fun classFile(file: File, major: Int) {
        file.parentFile.mkdirs()
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0xCAFEBABE.toInt())
            out.writeShort(0)
            out.writeShort(major)
        }
    }

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("preflight-support").toFile()
        try {
            block(dir)
        } finally {
            FileOps.deleteRecursively(dir)
        }
    }
}
