package pipeline.validators

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import pipeline.cas.ChangedPath
import pipeline.stages.GuardrailViolationCategory
import pipeline.stages.LocalAnnotationPolicy

class DiffAwareAstGuardTest {

    private val fooPath = "src/main/java/com/example/Foo.java"
    private val testPath = "src/test/java/com/example/FooTest.java"

    private val baselineFoo = """
        package com.example;

        public class Foo {
            public String greet(String name) {
                return "hello " + name;
            }

            public Object identity(Object y) {
                Object x = y;
                return x;
            }
        }
    """.trimIndent()

    private val baselineTest = """
        package com.example;

        public class FooTest {
            public void testGreet() {
            }
        }
    """.trimIndent()

    private fun repo(): File {
        val dir = GitRepos.tempDir("diffaware")
        GitRepos.init(dir, mapOf(fooPath to baselineFoo, testPath to baselineTest))
        return dir
    }

    private fun modify(dir: File, relPath: String, text: String) {
        dir.resolve(relPath).writeText(text)
    }

    private fun mainChange(dir: File) = listOf(ChangedPath("M", fooPath))

    private fun withGreetBody(dir: File, body: String) {
        modify(dir, fooPath, """
            package com.example;

            public class Foo {
                public String greet(String name) {
                    $body
                }

                public Object identity(Object y) {
                    Object x = y;
                    return x;
                }
            }
        """.trimIndent())
    }

    @Test
    fun `body change passes`() {
        val dir = repo()
        withGreetBody(dir, """return "hi " + name;""")

        val result = DiffAwareAstGuard.validate(
            dir, mainChange(dir), "HEAD", LocalAnnotationPolicy.FROZEN
        )

        assertTrue(result.valid, "pure body change must pass, got: ${result.violations}")
        assertTrue(result.auditJson.contains("\"valid\":true"), "audit JSON must record validity")
    }

    @Test
    fun `signature change is rejected`() {
        val dir = repo()
        modify(dir, fooPath, """
            package com.example;

            public class Foo {
                public String greet(String name, int times) {
                    return "hello " + name;
                }

                public Object identity(Object y) {
                    Object x = y;
                    return x;
                }
            }
        """.trimIndent())

        val result = DiffAwareAstGuard.validate(
            dir, mainChange(dir), "HEAD", LocalAnnotationPolicy.FROZEN
        )

        assertTrue(!result.valid, "signature change must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.SIGNATURE_TAMPERING },
            "expected SIGNATURE_TAMPERING, got: ${result.violations}"
        )
    }

    @Test
    fun `added SuppressWarnings is rejected`() {
        val dir = repo()
        modify(dir, fooPath, """
            package com.example;

            public class Foo {
                @SuppressWarnings("all")
                public String greet(String name) {
                    return "hello " + name;
                }

                public Object identity(Object y) {
                    Object x = y;
                    return x;
                }
            }
        """.trimIndent())

        val result = DiffAwareAstGuard.validate(
            dir, mainChange(dir), "HEAD", LocalAnnotationPolicy.FROZEN
        )

        assertTrue(!result.valid, "added suppression must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.SUPPRESSION },
            "expected SUPPRESSION, got: ${result.violations}"
        )
    }

    @Test
    fun `added castNonNull is rejected`() {
        val dir = repo()
        modify(dir, fooPath, """
            package com.example;

            public class Foo {
                public String greet(String name) {
                    return "hello " + name;
                }

                public Object identity(Object y) {
                    Object x = NullnessUtil.castNonNull(y);
                    return x;
                }
            }
        """.trimIndent())

        val result = DiffAwareAstGuard.validate(
            dir, mainChange(dir), "HEAD", LocalAnnotationPolicy.FROZEN
        )

        assertTrue(!result.valid, "added castNonNull must be rejected")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.CAST },
            "expected CAST, got: ${result.violations}"
        )
    }

    @Test
    fun `local annotation frozen rejects but allowed accepts`() {
        val annotated = """
            package com.example;

            public class Foo {
                public String greet(String name) {
                    return "hello " + name;
                }

                public Object identity(Object y) {
                    @Positive Object x = y;
                    return x;
                }
            }
        """.trimIndent()

        val frozenDir = repo()
        modify(frozenDir, fooPath, annotated)
        val frozen = DiffAwareAstGuard.validate(
            frozenDir, listOf(ChangedPath("M", fooPath)), "HEAD", LocalAnnotationPolicy.FROZEN
        )
        assertTrue(!frozen.valid, "frozen local annotation change must be rejected")
        assertTrue(
            frozen.violations.any { it.category == GuardrailViolationCategory.LOCAL_ANNOTATION },
            "expected LOCAL_ANNOTATION, got: ${frozen.violations}"
        )

        val allowedDir = repo()
        modify(allowedDir, fooPath, annotated)
        val allowed = DiffAwareAstGuard.validate(
            allowedDir, listOf(ChangedPath("M", fooPath)), "HEAD", LocalAnnotationPolicy.ALLOWED
        )
        assertTrue(allowed.valid, "allowed local annotation change must pass, got: ${allowed.violations}")
    }

    @Test
    fun `test file modification is rejected`() {
        val dir = repo()
        modify(dir, testPath, baselineTest.replace("public void testGreet() {", "public void testGreet() {\n        int x = 1;"))

        val result = DiffAwareAstGuard.validate(
            dir, listOf(ChangedPath("M", testPath)), "HEAD", LocalAnnotationPolicy.FROZEN
        )

        assertTrue(!result.valid, "test modification must be rejected in repair")
        assertTrue(
            result.violations.any { it.category == GuardrailViolationCategory.TEST_TAMPERING },
            "expected TEST_TAMPERING, got: ${result.violations}"
        )
    }
}
