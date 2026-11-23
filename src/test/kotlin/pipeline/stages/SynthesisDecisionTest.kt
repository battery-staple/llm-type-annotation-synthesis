package pipeline.stages

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SynthesisDecisionTest {
    private val customProcessor = "example.CustomChecker"
    private val builtinProcessor = BuiltinCheckerCatalog.nullness.processor
    private val policy
        get() = SynthesisDecisionPolicy(AllowedCheckers.ALL, customProcessor, builtinProcessor)

    @Test
    fun `parser reads valid custom decision`() = withTempDir { dir ->
        val file = dir.resolve(SynthesisDecision.FILE_NAME)
        file.writeText("""{"checker_category":"custom_qualifier_hierarchy","processor":"$customProcessor","rationale":"Domain-specific types"}""")
        assertEquals(
            SynthesisDecision(CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY, customProcessor, "Domain-specific types"),
            SynthesisDecisionParser.parse(file)
        )
    }

    @Test
    fun `parser reads valid builtin decision`() = withTempDir { dir ->
        val file = dir.resolve(SynthesisDecision.FILE_NAME)
        file.writeText("""{"checker_category":"builtin_checker","processor":"$builtinProcessor","rationale":"Use nullness"}""")
        assertEquals(
            SynthesisDecision(CheckerCategory.BUILTIN_CHECKER, builtinProcessor, "Use nullness"),
            SynthesisDecisionParser.parse(file)
        )
    }

    @Test
    fun `parser rejects missing file`() = withTempDir { dir ->
        val error = assertFailsWith<IllegalArgumentException> {
            SynthesisDecisionParser.parse(dir.resolve(SynthesisDecision.FILE_NAME))
        }
        assertTrue(error.message.orEmpty().contains("synthesis_decision.json is missing"))
    }

    @Test
    fun `parser rejects malformed JSON`() = withTempDir { dir ->
        val file = dir.resolve(SynthesisDecision.FILE_NAME)
        file.writeText("{")
        val error = assertFailsWith<IllegalArgumentException> { SynthesisDecisionParser.parse(file) }
        assertTrue(error.message.orEmpty().contains("parse error"))
    }

    @Test
    fun `parser rejects unknown checker category`() = withTempDir { dir ->
        val file = dir.resolve(SynthesisDecision.FILE_NAME)
        file.writeText("""{"checker_category":"other","processor":"x","rationale":"reason"}""")
        assertFailsWith<IllegalArgumentException> { SynthesisDecisionParser.parse(file) }
    }

    @Test
    fun `validator accepts matching custom decision in custom only mode`() {
        val p = policy.copy(allowedCheckers = AllowedCheckers.CUSTOM_ONLY)
        assertTrue(SynthesisDecisionValidator.validate(custom(customProcessor), p).isEmpty())
    }

    @Test
    fun `validator rejects builtin decision in custom only mode`() {
        val problems = SynthesisDecisionValidator.validate(builtin(builtinProcessor), policy.copy(allowedCheckers = AllowedCheckers.CUSTOM_ONLY))
        assertTrue(problems.any { it.contains("not allowed") && it.contains("custom_only") })
    }

    @Test
    fun `validator rejects custom decision in builtin only mode`() {
        val problems = SynthesisDecisionValidator.validate(custom(customProcessor), policy.copy(allowedCheckers = AllowedCheckers.BUILTIN_ONLY))
        assertTrue(problems.any { it.contains("not allowed") && it.contains("builtin_only") })
    }

    @Test
    fun `validator accepts configured builtin decision`() {
        assertTrue(SynthesisDecisionValidator.validate(builtin(builtinProcessor), policy.copy(allowedCheckers = AllowedCheckers.BUILTIN_ONLY)).isEmpty())
        assertTrue(SynthesisDecisionValidator.validate(builtin(builtinProcessor), policy).isEmpty())
    }

    @Test
    fun `validator rejects a different supported builtin`() {
        val problems = SynthesisDecisionValidator.validate(builtin(BuiltinCheckerCatalog.signedness.processor), policy)
        assertTrue(problems.any { it.contains("configured built-in processor") })
    }

    @Test
    fun `validator rejects unsupported builtin processor`() {
        val problems = SynthesisDecisionValidator.validate(builtin("example.Unsupported"), policy)
        assertTrue(problems.any { it.contains("unsupported") })
    }

    @Test
    fun `validator rejects wrong custom processor`() {
        assertTrue(SynthesisDecisionValidator.validate(custom("example.Other"), policy).isNotEmpty())
    }

    @Test
    fun `validator rejects a builtin processor configured for custom hierarchies`() {
        val p = SynthesisDecisionPolicy(AllowedCheckers.ALL, builtinProcessor, builtinProcessor)
        val problems = SynthesisDecisionValidator.validate(custom(builtinProcessor), p)
        assertTrue(problems.any { it.contains("cannot run a custom_qualifier_hierarchy") })
    }

    @Test
    fun `validator rejects blank rationale`() {
        val problems = SynthesisDecisionValidator.validate(custom(customProcessor, "  "), policy)
        assertTrue(problems.any { it.contains("rationale") })
    }

    @Test
    fun `lattice validator accepts valid two qualifier hierarchy`() = withTempDir { dir ->
        writeQualifier(dir, "Root", """
            @DefaultQualifierInHierarchy
            @Target(ElementType.TYPE_USE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Root {}
        """)
        writeQualifier(dir, "Child", """
            @SubtypeOf(Root.class)
            @Target(ElementType.TYPE_USE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Child {}
        """)
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).isEmpty())
    }

    @Test
    fun `lattice validator reports missing TYPE_USE target`() = withTempDir { dir ->
        writeQualifier(dir, "Root", """
            @DefaultQualifierInHierarchy
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Root {}
        """)
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).any { it.contains("TYPE_USE") })
    }

    @Test
    fun `lattice validator reports multiple roots`() = withTempDir { dir ->
        writeQualifier(dir, "RootA", root("RootA"))
        writeQualifier(dir, "RootB", root("RootB"))
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).any { it.contains("multiple root") })
    }

    @Test
    fun `lattice validator reports multiple bottoms`() = withTempDir { dir ->
        writeQualifier(dir, "Root", root("Root"))
        writeQualifier(dir, "ChildA", child("ChildA", "Root"))
        writeQualifier(dir, "ChildB", child("ChildB", "Root"))
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).any { it.contains("multiple bottom") })
    }

    @Test
    fun `lattice validator reports undeclared parent`() = withTempDir { dir ->
        writeQualifier(dir, "Root", root("Root"))
        writeQualifier(dir, "Child", child("Child", "Missing"))
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).any { it.contains("not a declared qualifier") })
    }

    @Test
    fun `lattice validator reports disconnected component`() = withTempDir { dir ->
        writeQualifier(dir, "Root", root("Root"))
        writeQualifier(dir, "Child", child("Child", "Root"))
        writeQualifier(dir, "Orphan", """
            @SubtypeOf(Orphan.class)
            @Target(ElementType.TYPE_USE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Orphan {}
        """)
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).any { it.contains("does not reach the root") })
    }

    @Test
    fun `lattice validator reports @SubtypeOf cycles`() = withTempDir { dir ->
        writeQualifier(dir, "Root", root("Root"))
        writeQualifier(dir, "A", """
            @SubtypeOf({Root.class, B.class})
            @Target(ElementType.TYPE_USE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface A {}
        """)
        writeQualifier(dir, "B", child("B", "A"))
        assertTrue(QualifierValidator.validateTypeUseLattice(dir).any { it.contains("cycle") })
    }

    @Test
    fun `lattice validator reports no annotations`() = withTempDir { dir ->
        val problems = QualifierValidator.validateTypeUseLattice(dir)
        assertTrue(problems.any { it.contains("no annotation declarations") || it.contains("no TYPE_USE") })
    }

    private fun custom(processor: String, rationale: String = "reason") =
        SynthesisDecision(CheckerCategory.CUSTOM_QUALIFIER_HIERARCHY, processor, rationale)

    private fun builtin(processor: String, rationale: String = "reason") =
        SynthesisDecision(CheckerCategory.BUILTIN_CHECKER, processor, rationale)

    private fun root(name: String) = """
        @DefaultQualifierInHierarchy
        @Target(ElementType.TYPE_USE)
        @Retention(RetentionPolicy.RUNTIME)
        public @interface $name {}
    """

    private fun child(name: String, parent: String) = """
        @SubtypeOf($parent.class)
        @Target(ElementType.TYPE_USE)
        @Retention(RetentionPolicy.RUNTIME)
        public @interface $name {}
    """

    private fun writeQualifier(dir: java.io.File, name: String, declaration: String) {
        dir.resolve("$name.java").writeText(
            """
            import org.checkerframework.framework.qual.*;
            import java.lang.annotation.*;
            $declaration
            """.trimIndent()
        )
    }

    private inline fun withTempDir(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("synthesis-decision-test").toFile()
        try {
            block(dir)
        } finally {
            pipeline.util.FileOps.deleteRecursively(dir)
        }
    }
}
