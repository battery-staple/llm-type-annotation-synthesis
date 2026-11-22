package pipeline.validators

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import pipeline.PipelineOrchestrator
import pipeline.cas.ChangedPath
import pipeline.config.BenchmarkManifestLoader
import pipeline.config.ProjectConfig
import pipeline.stages.CheckerCategory
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.LocalAnnotationPolicy
import pipeline.stages.ManifestTriageStage
import pipeline.stages.StageName
import pipeline.stages.SynthesizedLattice
import pipeline.util.FileOps
import pipeline.util.Git

/**
 * CMSX layout support: the flat `src/` (production) + `test/` and
 * `regression/src/` (tests) roots must be honored consistently by path
 * classification, the filesystem and AST guards, triage auditing, the
 * synthesis production-diff filter, manifest parsing, and workspace isolation.
 *
 * Every test also pins the legacy default behavior so benchmarks that omit
 * the new configuration keep working unchanged.
 */
class CmsxLayoutTest {

    private val cmsxProd = listOf("src")
    private val cmsxTest = listOf("test", "regression/src")

    private val cmsxProdFile = "src/cms/controller/persistence/AssignmentControllerImpl.java"
    private val cmsxProdHelperInTestSegment = "src/cms/www/action/actions/api/test/BasicTestSetup.java"
    private val cmsxTestFile = "test/cms/controller/persistence/AssignmentControllerImplTest.java"
    private val cmsxRegressionTestFile = "regression/src/utils/Assertions.java"

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { FileOps.deleteRecursively(it) } }
        tempDirs.clear()
    }

    private fun worktree(prefix: String = "cmsx-layout"): File =
        GitRepos.tempDir(prefix).also { tempDirs += it }

    private fun runningAsRoot(): Boolean = System.getProperty("user.name") == "root"

    // --- SourceRoots classification ---

    @Test
    fun `cmsx production file is production not test`() {
        assertTrue(SourceRoots.isProductionPath(cmsxProdFile, cmsxProd))
        assertFalse(SourceRoots.isTestDir(cmsxProdFile, cmsxTest))
        assertFalse(SourceRoots.isTestPath(cmsxProdFile, cmsxTest))
    }

    @Test
    fun `cmsx production helper under a test-named segment stays production`() {
        assertTrue(SourceRoots.isProductionPath(cmsxProdHelperInTestSegment, cmsxProd))
        assertFalse(
            SourceRoots.isTestDir(cmsxProdHelperInTestSegment, cmsxTest),
            "a repository-root-anchored `test` root must not match an inner `api/test` segment"
        )
        assertFalse(SourceRoots.isTestPath(cmsxProdHelperInTestSegment, cmsxTest))
    }

    @Test
    fun `cmsx test files are test in both test roots`() {
        assertTrue(SourceRoots.isTestDir(cmsxTestFile, cmsxTest))
        assertTrue(SourceRoots.isTestPath(cmsxTestFile, cmsxTest))
        assertFalse(SourceRoots.isProductionPath(cmsxTestFile, cmsxProd))

        assertTrue(SourceRoots.isTestDir(cmsxRegressionTestFile, cmsxTest))
        assertTrue(SourceRoots.isTestPath(cmsxRegressionTestFile, cmsxTest))
        assertFalse(SourceRoots.isProductionPath(cmsxRegressionTestFile, cmsxProd))
    }

    @Test
    fun `single-segment roots anchor at the repository root`() {
        assertFalse(SourceRoots.isUnderRoots("regression/src/utils/Assertions.java", cmsxProd))
        assertFalse(SourceRoots.isUnderRoots("cmsx-ui/src/app.ts", cmsxProd))
        assertTrue(SourceRoots.isUnderRoots("src/cms/model/Foo.java", cmsxProd))
        assertFalse(SourceRoots.isTestDir("src/foo/test/Bar.java", cmsxTest))
    }

    @Test
    fun `multi-segment legacy roots match nested modules including the root itself`() {
        val prod = SourceRoots.DEFAULT_PRODUCTION_ROOTS
        val test = SourceRoots.DEFAULT_TEST_ROOTS
        assertTrue(SourceRoots.isUnderRoots("module/src/main/java/Foo.java", prod))
        assertTrue(SourceRoots.isTestDir("module/src/test", test), "directory path must match")
        assertTrue(SourceRoots.isTestDir("module/src/test/java/FooTest.java", test))
        assertFalse(SourceRoots.isTestDir("module/src/main/java/Foo.java", test))
        // Test roots win on overlap, preserving the pre-CMSX behavior.
        assertTrue(SourceRoots.isTestDir("src/main/src/test/Foo.java", test))
    }

    @Test
    fun `git pathspecs mirror root anchoring`() {
        assertEquals(listOf(":(glob)test/**"), SourceRoots.gitPathspecs(listOf("test")))
        assertEquals(
            listOf(":(glob)**/module/src/test/**"),
            SourceRoots.gitPathspecs(listOf("module/src/test"))
        )
    }

    @Test
    fun `test patch discovery selects only configured test roots`() {
        val dir = worktree("cmsx-test-pathspec")
        val prodHelper = "src/cms/www/action/actions/api/test/BasicTestSetup.java"
        val testFile = "test/cms/controller/persistence/AssignmentControllerImplTest.java"
        val regressionTest = "regression/src/utils/Assertions.java"
        GitRepos.init(
            dir,
            mapOf(
                prodHelper to "class BasicTestSetup {}\n",
                testFile to "class AssignmentControllerImplTest {}\n",
                regressionTest to "class Assertions {}\n"
            )
        )
        listOf(prodHelper, testFile, regressionTest).forEach { path ->
            dir.resolve(path).appendText("// changed\n")
        }
        GitRepos.runGit(dir, "add", "-A")
        GitRepos.runGit(dir, "commit", "--no-gpg-sign", "-q", "-m", "change")

        val patch = Git.diffBetween(
            dir, "HEAD~1", "HEAD",
            listOf("--") + SourceRoots.gitPathspecs(cmsxTest)
        )

        assertTrue(patch.contains(testFile), "root test/ path must be discovered: $patch")
        assertTrue(patch.contains(regressionTest), "regression/src path must be discovered: $patch")
        assertFalse(
            patch.contains(prodHelper),
            "a production directory named test must not be discovered as a test path: $patch"
        )
        assertTrue(
            ManifestTriageStage.auditTestPatch(patch, cmsxTest).valid,
            "the derived patch must contain only test paths"
        )
    }

    @Test
    fun `package-info is allowed under cmsx src but not under test`() {
        assertTrue(SourceRoots.packageInfoAllowedPath("src/cms/package-info.java", cmsxProd))
        assertFalse(SourceRoots.packageInfoAllowedPath("test/cms/package-info.java", cmsxProd))
        assertFalse(SourceRoots.packageInfoAllowedPath("regression/src/package-info.java", cmsxProd))
    }

    @Test
    fun `legacy defaults are unchanged`() {
        assertEquals(listOf("src/main"), SourceRoots.DEFAULT_PRODUCTION_ROOTS)
        assertEquals(listOf("src/test"), SourceRoots.DEFAULT_TEST_ROOTS)
        assertTrue(SourceRoots.isTestDir("src/test/java/FooTest.java", SourceRoots.DEFAULT_TEST_ROOTS))
        assertFalse(SourceRoots.isTestDir("src/main/java/Foo.java", SourceRoots.DEFAULT_TEST_ROOTS))
    }

    // --- FilesystemGuard with CMSX roots ---

    private fun cmsxAudit(path: String, status: String = "M", stage: StageName = StageName.REPAIR) =
        FilesystemGuard.audit(
            worktree(), listOf(ChangedPath(status, path)), stage,
            allowPackageInfoAddition = stage == StageName.APPLICATION,
            allowTestEdits = stage == StageName.APPLICATION,
            productionRoots = cmsxProd, testRoots = cmsxTest
        )

    @Test
    fun `repair permits cmsx production edits including the api-test helpers`() {
        listOf(cmsxProdFile, cmsxProdHelperInTestSegment).forEach { path ->
            val result = cmsxAudit(path)
            assertTrue(result.valid, "$path must pass in repair, got: ${result.violations}")
        }
    }

    @Test
    fun `repair rejects cmsx test edits`() {
        listOf(cmsxTestFile, cmsxRegressionTestFile).forEach { path ->
            val result = cmsxAudit(path)
            assertFalse(result.valid, "test edit must fail in repair: $path")
            assertTrue(
                result.violations.any { it.category == GuardrailViolationCategory.TEST_TAMPERING },
                "expected TEST_TAMPERING for $path, got: ${result.violations}"
            )
        }
    }

    @Test
    fun `application permits cmsx test qualifier edits when allowed`() {
        val dir = worktree()
        val allowed = FilesystemGuard.audit(
            dir, listOf(ChangedPath("M", cmsxTestFile)), StageName.APPLICATION,
            allowPackageInfoAddition = true, allowTestEdits = true,
            productionRoots = cmsxProd, testRoots = cmsxTest
        )
        assertTrue(allowed.valid, "test qualifier edit must pass, got: ${allowed.violations}")
        val denied = FilesystemGuard.audit(
            dir, listOf(ChangedPath("M", cmsxTestFile)), StageName.APPLICATION,
            allowPackageInfoAddition = true, allowTestEdits = false,
            productionRoots = cmsxProd, testRoots = cmsxTest
        )
        assertFalse(denied.valid, "test edit must fail when not allowed")
    }

    @Test
    fun `cmsx package-info allowed under src rejected under test`() {
        val underSrc = FilesystemGuard.audit(
            worktree(), listOf(ChangedPath("A", "src/cms/package-info.java")),
            StageName.APPLICATION, allowPackageInfoAddition = true, allowTestEdits = true,
            productionRoots = cmsxProd, testRoots = cmsxTest
        )
        assertTrue(underSrc.valid, "new src package-info must pass, got: ${underSrc.violations}")
        assertTrue(FilesystemGuard.packageInfoAllowedPath("src/cms/package-info.java", cmsxProd))

        val underTest = FilesystemGuard.audit(
            worktree(), listOf(ChangedPath("A", "test/cms/package-info.java")),
            StageName.APPLICATION, allowPackageInfoAddition = true, allowTestEdits = true,
            productionRoots = cmsxProd, testRoots = cmsxTest
        )
        assertFalse(underTest.valid, "test package-info must be rejected")
    }

    @Test
    fun `default overload matches explicit default roots`() {
        val dir = worktree()
        val paths = listOf(
            ChangedPath("M", "src/main/java/com/example/Foo.java"),
            ChangedPath("M", "src/test/java/com/example/FooTest.java"),
            ChangedPath("A", "src/main/java/com/example/package-info.java")
        )
        val defaulted = FilesystemGuard.audit(
            dir, paths, StageName.APPLICATION, allowPackageInfoAddition = true, allowTestEdits = true
        )
        val explicit = FilesystemGuard.audit(
            dir, paths, StageName.APPLICATION, allowPackageInfoAddition = true, allowTestEdits = true,
            productionRoots = SourceRoots.DEFAULT_PRODUCTION_ROOTS,
            testRoots = SourceRoots.DEFAULT_TEST_ROOTS
        )
        assertEquals(defaulted.valid, explicit.valid)
        assertEquals(
            defaulted.violations.map { it.category to it.filePath },
            explicit.violations.map { it.category to it.filePath }
        )
    }

    // --- DiffAwareAstGuard with CMSX roots ---

    private val baselineHelper = """
        package cms.www.action.actions.api.test;

        public class BasicTestSetup {
            public String greet(String name) {
                return "hello " + name;
            }
        }
    """.trimIndent()

    private fun cmsxRepo(): File {
        val dir = worktree("cmsx-diffaware")
        GitRepos.init(
            dir, mapOf(
                cmsxProdFile to baselineHelper.replace("BasicTestSetup", "AssignmentControllerImpl"),
                cmsxProdHelperInTestSegment to baselineHelper,
                cmsxTestFile to "package cms.controller.persistence;\n\npublic class AssignmentControllerImplTest {\n}\n",
                cmsxRegressionTestFile to "package utils;\n\npublic class Assertions {\n}\n"
            )
        )
        return dir
    }

    @Test
    fun `repair body change using cmsx record-pattern idiom passes`() {
        val dir = worktree("cmsx-record-pattern")
        val prod = "src/cms/model/Example.java"
        val baseline = """
            package cms.model;

            public class Example {
                record Box(int value) {}
                public int unwrap(Object o) {
                    if (o instanceof Box(int value)) {
                        return value;
                    }
                    return 0;
                }
            }
        """.trimIndent()
        GitRepos.init(dir, mapOf(prod to baseline))
        dir.resolve(prod).writeText(baseline.replace("return value;", "return value + 1;"))
        val result = DiffAwareAstGuard.validate(
            dir, listOf(ChangedPath("M", prod)), "HEAD",
            LocalAnnotationPolicy.FROZEN, cmsxProd, cmsxTest
        )
        assertTrue(result.valid, "record-pattern body change must pass, got: ${result.violations}")
    }

    @Test
    fun `repair body change in cmsx api-test helper passes`() {
        val dir = cmsxRepo()
        dir.resolve(cmsxProdHelperInTestSegment).writeText(
            baselineHelper.replace("return \"hello \" + name;", "return \"hi \" + name;")
        )
        val result = DiffAwareAstGuard.validate(
            dir, listOf(ChangedPath("M", cmsxProdHelperInTestSegment)), "HEAD",
            LocalAnnotationPolicy.FROZEN, cmsxProd, cmsxTest
        )
        assertTrue(result.valid, "production helper body change must pass, got: ${result.violations}")
    }

    @Test
    fun `repair edit to cmsx test file is rejected`() {
        val dir = cmsxRepo()
        val edits = mapOf(
            cmsxTestFile to "package cms.controller.persistence;\n\npublic class AssignmentControllerImplTest {\n public void extra() {}\n}\n",
            cmsxRegressionTestFile to "package utils;\n\npublic class Assertions {\n public void extra() {}\n}\n"
        )
        edits.forEach { (path, content) ->
            dir.resolve(path).writeText(content)
            val result = DiffAwareAstGuard.validate(
                dir, listOf(ChangedPath("M", path)), "HEAD",
                LocalAnnotationPolicy.FROZEN, cmsxProd, cmsxTest
            )
            assertFalse(result.valid, "test edit must be rejected in repair: $path")
            assertTrue(
                result.violations.any { it.category == GuardrailViolationCategory.TEST_TAMPERING },
                "expected TEST_TAMPERING for $path, got: ${result.violations}"
            )
        }
    }

    // --- Triage patch audit ---

    private fun testPatchFor(path: String) = """
        diff --git a/$path b/$path
        --- a/$path
        +++ b/$path
        @@ -1 +1 @@
        -old
        +new
    """.trimIndent()

    @Test
    fun `triage accepts cmsx test patch and rejects production paths`() {
        listOf(cmsxTestFile, cmsxRegressionTestFile).forEach { path ->
            val accepted = ManifestTriageStage.auditTestPatch(testPatchFor(path), cmsxTest)
            assertTrue(accepted.valid, "cmsx test patch must be accepted: $path (${accepted.reason})")
        }

        listOf(cmsxProdFile, cmsxProdHelperInTestSegment).forEach { path ->
            val rejected = ManifestTriageStage.auditTestPatch(testPatchFor(path), cmsxTest)
            assertFalse(rejected.valid, "production path must be rejected: $path")
        }
    }

    @Test
    fun `triage legacy default behavior is unchanged`() {
        val accepted = ManifestTriageStage.auditTestPatch(testPatchFor("src/test/java/FooTest.java"))
        assertTrue(accepted.valid, "legacy test patch must be accepted: ${accepted.reason}")
        val rejected = ManifestTriageStage.auditTestPatch(testPatchFor("src/main/java/Foo.java"))
        assertFalse(rejected.valid, "legacy production path must be rejected")
    }

    // --- Synthesis production-diff filter (test blindness) ---

    private fun multiFileDiff(vararg paths: String): String =
        paths.joinToString("\n") { path ->
            """
            diff --git a/$path b/$path
            --- a/$path
            +++ b/$path
            @@ -1 +1 @@
            -old
            +new
            """.trimIndent()
        }

    @Test
    fun `production diff keeps production hunks and drops test hunks`() {
        val diff = multiFileDiff(cmsxProdFile, cmsxTestFile)
        val filtered = PipelineOrchestrator.filterProductionDiff(diff, cmsxTest)
        assertTrue(filtered.contains(cmsxProdFile), "production hunk must be kept: $filtered")
        assertFalse(filtered.contains(cmsxTestFile), "test hunk must not reach synthesis: $filtered")
    }

    @Test
    fun `production diff keeps cmsx api-test helpers and drops regression test sources`() {
        val diff = multiFileDiff(cmsxProdHelperInTestSegment, cmsxRegressionTestFile)
        val filtered = PipelineOrchestrator.filterProductionDiff(diff, cmsxTest)
        assertTrue(filtered.contains(cmsxProdHelperInTestSegment), "production helper must be kept: $filtered")
        assertFalse(filtered.contains(cmsxRegressionTestFile), "regression test source must be dropped: $filtered")
    }

    @Test
    fun `production diff drops test files identified only by the filename fallback`() {
        val testNamedProductionRootFile = "src/cms/controller/persistence/AssignmentControllerImplTest.java"
        val filtered = PipelineOrchestrator.filterProductionDiff(
            multiFileDiff(cmsxProdFile, testNamedProductionRootFile), cmsxTest
        )
        assertTrue(filtered.contains(cmsxProdFile))
        assertFalse(filtered.contains(testNamedProductionRootFile), "filename fallback must drop *Test.java: $filtered")
    }

    @Test
    fun `production diff fails closed for unparseable blocks`() {
        val filtered = PipelineOrchestrator.filterProductionDiff(
            "diff --git\n@@ -1 +1 @@\n-old\n+new", cmsxTest
        )
        assertEquals("", filtered, "a block with no parseable paths must be dropped")
    }

    @Test
    fun `production diff parses git-quoted paths with spaces`() {
        val quotedTest = """
            diff --git "a/test/cms/Weird NameTest.java" "b/test/cms/Weird NameTest.java"
            --- "a/test/cms/Weird NameTest.java"
            +++ "b/test/cms/Weird NameTest.java"
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()
        val quotedProd = """
            diff --git "a/src/cms/Weird Name.java" "b/src/cms/Weird Name.java"
            --- "a/src/cms/Weird Name.java"
            +++ "b/src/cms/Weird Name.java"
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()
        val filtered = PipelineOrchestrator.filterProductionDiff("$quotedTest\n$quotedProd", cmsxTest)
        assertFalse(filtered.contains("Weird NameTest.java"), "quoted test path must be dropped: $filtered")
        assertTrue(filtered.contains("Weird Name.java"), "quoted production path must be kept: $filtered")
    }

    // --- Manifest parsing ---

    @Test
    fun `manifest parses cmsx roots and defaults synthetic layout`() {
        val dir = Files.createTempDirectory("cmsx-manifest").toFile()
        try {
            val file = dir.resolve("benchmarks.yaml")
            file.writeText(
                """
                benchmarks:
                  - id: cmsx-assignment-extension
                    repo_url: "eval-repos/cmsx"
                    commit_hash: "${"a".repeat(40)}"
                    parent_hash: "${"b".repeat(40)}"
                    regression_test_class: cms.controller.persistence.AssignmentControllerImplTest
                    regression_test_method: getSubmissionStatus_assignmentGraded_pastDue_hasExtension_accept
                    production_roots: ["src"]
                    test_roots: ["test", "regression/src"]
                  - id: synthetic-id-swap
                    repo_url: "eval-repos/synthetic-id-swap"
                    commit_hash: "${"c".repeat(40)}"
                    parent_hash: "${"d".repeat(40)}"
                    regression_test_class: com.example.synthetic.TransferServiceTest
                    regression_test_method: testTransferDebitsSourceAndCreditsDestination
                """.trimIndent()
            )
            val entries = BenchmarkManifestLoader.load(file, ProjectConfig())
            val cmsx = entries.single { it.benchmarkId.alias == "cmsx-assignment-extension" }
            assertEquals(listOf("src"), cmsx.productionRoots)
            assertEquals(listOf("regression/src", "test"), cmsx.testRoots)
            val synthetic = entries.single { it.benchmarkId.alias == "synthetic-id-swap" }
            assertEquals(SourceRoots.DEFAULT_PRODUCTION_ROOTS, synthetic.productionRoots)
            assertEquals(SourceRoots.DEFAULT_TEST_ROOTS, synthetic.testRoots)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `checked-in manifest pins the cmsx benchmark entry`() {
        val manifest = File("benchmarks.yaml")
        assertTrue(manifest.isFile, "tests must run from the repository root")
        val entries = BenchmarkManifestLoader.load(manifest, ProjectConfig())
        val cmsx = entries.single { it.benchmarkId.alias == "cmsx-assignment-extension" }
        assertEquals("be6f19da0008342cf559b66175fdf1acd3a02b0e", cmsx.commitHash.hash)
        assertEquals("7ee122ff1fee70414fd1c717a2f089955ccbec5a", cmsx.parentHash.hash)
        assertEquals("cms.controller.persistence.AssignmentControllerImplTest", cmsx.regressionTest.testClass.value)
        assertEquals(
            "getSubmissionStatus_assignmentGraded_pastDue_hasExtension_accept",
            cmsx.regressionTest.testMethod
        )
        assertEquals("21", cmsx.sourceCompatibility)
        assertEquals("21", cmsx.targetCompatibility)
        assertEquals(listOf("src"), cmsx.productionRoots)
        assertEquals(listOf("regression/src", "test"), cmsx.testRoots)
    }

    // --- Workspace isolation with CMSX roots ---

    @Test
    fun `sanitized export keeps cmsx prod helpers writable and tests read-only`() {
        val root = Files.createTempDirectory("cmsx-isolation").toFile()
        try {
            val source = File(root, "repo").apply { mkdirs() }
            File(source, "src/cms/www/action/actions/api/test").mkdirs()
            File(source, "src/cms/www/action/actions/api/test/BasicTestSetup.java")
                .writeText("class BasicTestSetup {}")
            File(source, "test/cms/controller/persistence").mkdirs()
            val testFile = File(source, "test/cms/controller/persistence/AssignmentControllerImplTest.java")
            testFile.writeText("class AssignmentControllerImplTest {}")
            File(source, "regression/src/utils").mkdirs()
            val regressionFile = File(source, "regression/src/utils/Assertions.java")
            regressionFile.writeText("class Assertions {}")

            val destination = File(root, "workspace")
            FileOps.exportSanitized(
                source, destination, excludeTests = false, testReadOnly = true,
                testRoots = cmsxTest
            )

            val helper = destination.resolve("src/cms/www/action/actions/api/test/BasicTestSetup.java")
            assertTrue(helper.isFile, "production helper must be exported")
            runCatching { helper.appendText("// repair edit") }.getOrThrow()

            assumeFalse(runningAsRoot(), "POSIX read-only assertions cannot hold for root")
            val exported = destination.resolve("test/cms/controller/persistence/AssignmentControllerImplTest.java")
            assertTrue(exported.isFile, "test must be exported")
            val writeFailed = runCatching { exported.appendText("// tamper") }.isFailure
            assertTrue(writeFailed, "cmsx test/ must be read-only in the repair workspace")

            val exportedRegression = destination.resolve("regression/src/utils/Assertions.java")
            assertTrue(exportedRegression.isFile, "regression test utility must be exported")
            val regressionWriteFailed = runCatching { exportedRegression.appendText("// tamper") }.isFailure
            assertTrue(regressionWriteFailed, "regression/src must be read-only in the repair workspace")

            assertTrue(FileOps.isWithin(destination.resolve("src/x"), destination))
            assertFalse(FileOps.isWithin(destination.resolve("../repo/build/x"), destination))
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `sanitized export can exclude cmsx test roots without dropping api-test helpers`() {
        val root = Files.createTempDirectory("cmsx-export-exclude").toFile()
        try {
            val source = File(root, "repo").apply { mkdirs() }
            File(source, "src/cms/www/action/actions/api/test").mkdirs()
            File(source, "src/cms/www/action/actions/api/test/BasicTestSetup.java")
                .writeText("class BasicTestSetup {}")
            File(source, "test/cms").mkdirs()
            File(source, "test/cms/FooTest.java").writeText("class FooTest {}")
            File(source, "regression/src/utils").mkdirs()
            File(source, "regression/src/utils/Assertions.java").writeText("class Assertions {}")

            val destination = File(root, "workspace")
            FileOps.exportSanitized(
                source, destination, excludeTests = true, testRoots = cmsxTest
            )

            assertTrue(
                destination.resolve("src/cms/www/action/actions/api/test/BasicTestSetup.java").isFile,
                "production helper under an inner test segment must be exported"
            )
            assertFalse(destination.resolve("test").exists(), "cmsx test/ must be excluded")
            assertFalse(destination.resolve("regression/src").exists(), "regression test sources must be excluded")
        } finally {
            FileOps.deleteRecursively(root)
        }
    }

    @Test
    fun `makeTestsReadOnly protects a multi-module test root`() {
        assumeFalse(runningAsRoot(), "POSIX read-only assertions cannot hold for root")
        val root = worktree("cmsx-readonly-multimodule")
        val direct = File(root, "module/src/test/direct.txt").apply {
            parentFile.mkdirs()
            writeText("direct")
        }
        val nested = File(root, "module/src/test/java/FooTest.java").apply {
            parentFile.mkdirs()
            writeText("class FooTest {}")
        }
        val production = File(root, "module/src/main/java/Foo.java").apply {
            parentFile.mkdirs()
            writeText("class Foo {}")
        }

        FileOps.makeTestsReadOnly(root)

        assertFalse(direct.canWrite(), "a file directly inside module/src/test must be read-only")
        assertFalse(nested.canWrite(), "nested test files must be read-only")
        assertTrue(production.canWrite(), "production sources must stay writable")
    }

    // --- Java 21 syntax support (CMSX compiles with release 21) ---

    @Test
    fun `guards parse java21 record patterns`() {
        val text = """
            public class Shapes {
                record Point(int x, int y) {}
                public int sum(Object o) {
                    if (o instanceof Point(int x, int y)) {
                        return x + y;
                    }
                    return 0;
                }
            }
        """.trimIndent()
        val cu = runCatching {
            StaticJavaParser.getParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.JAVA_21
            )
            StaticJavaParser.parse(text)
        }.getOrNull()
        assertTrue(cu != null, "record patterns (Java 21) must parse at the configured language level")
    }

    @Test
    fun `application ast guard parses java21 record patterns`() {
        val dir = worktree("cmsx-astguard21")
        val prod = "src/cms/model/Shapes.java"
        val baseline = """
            package cms.model;

            public class Shapes {
                record Point(int x, int y) {}
                public int sum(Object o) {
                    if (o instanceof Point(int x, int y)) {
                        return x + y;
                    }
                    return 0;
                }
            }
        """.trimIndent()
        GitRepos.init(dir, mapOf(prod to baseline))
        val lattice = SynthesizedLattice(
            customQualsJar = File(dir, "custom-quals.jar"),
            placementGuideFile = File(dir, "placement.md"),
            customQualsSrcDir = File(dir, "custom-quals-src"),
            qualifierClassNames = emptyList(),
            qualifiersCount = 0,
            processor = "com.example.PlacementProcessor",
            checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY
        )

        // Simulate a stale global parser level: AstGuard must set JAVA_21 itself.
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
        try {
            val result = AstGuard.validate(dir, listOf(ChangedPath("M", prod)), lattice)
            assertTrue(
                result.valid,
                "AstGuard must parse Java 21 syntax instead of reporting a parse failure: ${result.violations}"
            )
        } finally {
            StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        }
    }
}
