package pipeline.validators

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pipeline.cas.ChangedPath
import pipeline.stages.CheckerCategory
import pipeline.stages.Fqcn
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.SynthesizedLattice

class AstGuardTest {

    private val fooPath = "src/main/java/com/example/Foo.java"
    private val pkgInfoPath = "src/main/java/com/example/package-info.java"

    private val baselineFoo = """
        package com.example;

        public class Foo {
            public String greet(String name) {
                return "hello " + name;
            }
        }
    """.trimIndent()

    private fun lattice() = SynthesizedLattice(
        customQualsJar = File("custom-quals.jar"),
        placementGuideFile = File("placement_guide.md"),
        customQualsSrcDir = File("custom_quals_src"),
        qualifierClassNames = listOf(Fqcn("com.example.qual.Positive")),
        qualifiersCount = 1,
        processor = "org.checkerframework.checker.nullness.NullnessChecker",
        checkerCategory = CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY
    )

    private fun repoWith(files: Map<String, String>): File {
        val dir = GitRepos.tempDir("astguard")
        GitRepos.init(dir, files)
        return dir
    }

    private fun modify(dir: File, relPath: String, text: String) {
        val target = dir.resolve(relPath)
        target.parentFile.mkdirs()
        target.writeText(text)
    }

    @Test
    fun `adding a type-use qualifier and import on a parameter passes`() {
        val dir = repoWith(mapOf(fooPath to baselineFoo))
        modify(dir, fooPath, """
            package com.example;

            import com.example.qual.Positive;

            public class Foo {
                public String greet(@Positive String name) {
                    return "hello " + name;
                }
            }
        """.trimIndent())

        val result = AstGuard.validate(
            dir, listOf(ChangedPath("M", fooPath)), lattice()
        )

        assertTrue(result.valid, "expected no violations, got: ${result.violations}")
        assertTrue(result.placements.isNotEmpty(), "expected qualifier placements in changed files")
    }

    @Test
    fun `changing a statement is rejected`() {
        val dir = repoWith(mapOf(fooPath to baselineFoo))
        modify(dir, fooPath, """
            package com.example;

            public class Foo {
                public String greet(String name) {
                    return "bye";
                }
            }
        """.trimIndent())

        val result = AstGuard.validate(
            dir, listOf(ChangedPath("M", fooPath)), lattice()
        )

        assertTrue(!result.valid, "statement change must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.EXECUTABLE_EDIT },
            "expected EXECUTABLE_EDIT, got: ${result.violations}"
        )
    }

    @Test
    fun `adding SuppressWarnings is rejected as suppression`() {
        val dir = repoWith(mapOf(fooPath to baselineFoo))
        modify(dir, fooPath, """
            package com.example;

            public class Foo {
                @SuppressWarnings("all")
                public String greet(String name) {
                    return "hello " + name;
                }
            }
        """.trimIndent())

        val result = AstGuard.validate(
            dir, listOf(ChangedPath("M", fooPath)), lattice()
        )

        assertTrue(!result.valid, "SuppressWarnings addition must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.SUPPRESSION },
            "expected SUPPRESSION, got: ${result.violations}"
        )
    }

    @Test
    fun `unauthorized import is rejected`() {
        val dir = repoWith(mapOf(fooPath to baselineFoo))
        modify(dir, fooPath, """
            package com.example;

            import java.util.ArrayList;

            public class Foo {
                public String greet(String name) {
                    return "hello " + name;
                }
            }
        """.trimIndent())

        val result = AstGuard.validate(
            dir, listOf(ChangedPath("M", fooPath)), lattice()
        )

        assertTrue(!result.valid, "unauthorized import must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.EXECUTABLE_EDIT },
            "expected EXECUTABLE_EDIT, got: ${result.violations}"
        )
    }

    @Test
    fun `package-info with only a Checker defaulting annotation passes`() {
        val dir = repoWith(mapOf(fooPath to baselineFoo))
        modify(dir, pkgInfoPath, """
            @org.checkerframework.framework.qual.DefaultQualifier(com.example.qual.Positive.class)
            package com.example;
        """.trimIndent())

        val result = AstGuard.validate(
            dir, listOf(ChangedPath("A", pkgInfoPath)), lattice()
        )

        assertTrue(result.valid, "permitted package-info must pass, got: ${result.violations}")
    }

    @Test
    fun `package-info with a method fails`() {
        val text = """
            package com.example;
            import org.checkerframework.framework.qual.DefaultQualifier;
            @DefaultQualifier(com.example.qual.Positive.class)
            public class Extra {
                public void helper() {
                }
            }
        """.trimIndent()

        assertTrue(AstGuard.validatePackageInfo(text).isNotEmpty(), "gate must report problems")

        val dir = repoWith(mapOf(fooPath to baselineFoo))
        modify(dir, pkgInfoPath, text)
        val result = AstGuard.validate(
            dir, listOf(ChangedPath("A", pkgInfoPath)), lattice()
        )

        assertTrue(!result.valid, "package-info with executable content must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.PACKAGE_INFO_CHANGE },
            "expected PACKAGE_INFO_CHANGE, got: ${result.violations}"
        )
    }

    @Test
    fun `valid package-info text has no problems`() {
        val text = """
            @org.checkerframework.framework.qual.DefaultQualifier(com.example.qual.Positive.class)
            package com.example;
        """.trimIndent()
        assertEquals(emptyList(), AstGuard.validatePackageInfo(text))
    }
}
