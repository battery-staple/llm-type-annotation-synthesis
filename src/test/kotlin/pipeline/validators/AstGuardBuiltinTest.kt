package pipeline.validators

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import pipeline.cas.ChangedPath
import pipeline.stages.BuiltinCheckerCatalog
import pipeline.stages.CheckerCategory
import pipeline.stages.Fqcn
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.SynthesizedLattice

class AstGuardBuiltinTest {
    private val sourcePath = "src/main/java/demo/Foo.java"
    private val baseline = """
        package demo;

        public class Foo {
            String value;
        }
    """.trimIndent()

    @Test
    fun `builtin nullness annotations are accepted on types`() {
        withRepo { dir, artifacts ->
            dir.resolve(sourcePath).writeText("""
                package demo;

                import org.checkerframework.checker.nullness.qual.Nullable;

                public class Foo {
                    @Nullable String value;
                }
            """.trimIndent())

            val result = AstGuard.validate(
                dir, listOf(ChangedPath("M", sourcePath)), builtinLattice(artifacts)
            )

            assertTrue(result.valid, "expected no violations, got: ${result.violations}")
            assertTrue(
                result.placements.any { it.qualifier == "@Nullable" },
                "expected Nullable placement, got: ${result.placements}"
            )
        }
    }

    @Test
    fun `non-builtin annotations are still rejected under a builtin lattice`() {
        withRepo { dir, artifacts ->
            dir.resolve(sourcePath).writeText("""
                package demo;

                public class Foo {
                    @Deprecated String value;
                }
            """.trimIndent())

            val result = AstGuard.validate(
                dir, listOf(ChangedPath("M", sourcePath)), builtinLattice(artifacts)
            )

            assertTrue(!result.valid, "unrelated annotation must be rejected")
            assertTrue(
                result.violations.any { it.category == GuardrailViolationCategory.EXECUTABLE_EDIT },
                "expected an AST violation, got: ${result.violations}"
            )
        }
    }

    @Test
    fun `custom lattice behavior is unchanged`() {
        withRepo { dir, artifacts ->
            dir.resolve(sourcePath).writeText("""
                package demo;

                public class Foo {
                    @demo.qual.Child String value;
                }
            """.trimIndent())

            val result = AstGuard.validate(
                dir,
                listOf(ChangedPath("M", sourcePath)),
                SynthesizedLattice(
                    customQualsJar = artifacts.resolve("custom.jar"),
                    placementGuideFile = artifacts.resolve("guide.md"),
                    customQualsSrcDir = artifacts.resolve("src"),
                    qualifierClassNames = listOf(
                        Fqcn("demo.qual.Root"), Fqcn("demo.qual.Child")
                    ),
                    qualifiersCount = 2,
                    processor = BuiltinCheckerCatalog.nullness.processor,
                    checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY
                )
            )

            assertTrue(result.valid, "expected custom qualifier to pass, got: ${result.violations}")
        }
    }

    private fun builtinLattice(artifacts: File) = SynthesizedLattice(
        customQualsJar = artifacts.resolve("custom.jar"),
        placementGuideFile = artifacts.resolve("guide.md"),
        customQualsSrcDir = artifacts.resolve("src"),
        qualifierClassNames = emptyList(),
        qualifiersCount = 0,
        processor = BuiltinCheckerCatalog.nullness.processor,
        checkerCategory = CheckerCategory.BUILTIN_CHECKER
    )

    private fun withRepo(block: (File, File) -> Unit) {
        val dir = GitRepos.tempDir("astguard-builtin")
        try {
            GitRepos.init(dir, mapOf(sourcePath to baseline))
            val artifacts = Files.createTempDirectory("astguard-artifacts").toFile()
            try {
                artifacts.resolve("custom.jar").writeBytes(byteArrayOf())
                artifacts.resolve("guide.md").writeText("")
                artifacts.resolve("src").mkdirs()
                block(dir, artifacts)
            } finally {
                artifacts.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
