package pipeline.stages

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationPromptTest {

    @Test
    fun `non-triviality requirement is stated only when the ablation flag is set`() {
        val stated = nonTrivialityRequirement(true)
        assertTrue(stated.contains("must report at least one"))
        assertTrue(stated.contains("vacuous"))
        assertTrue(nonTrivialityRequirement(false).isEmpty())
    }

    @Test
    fun `rendered application prompt states the requirement only in the true arm`() {
        val template = PromptTemplates.load("application.md")
        val common = mapOf(
            "placement_guide" to "test placement guide",
            "checker_mode_note" to "test checker mode",
            "qualifier_names" to "@A, @B",
            "qualifier_import_prefixes" to "example.quals",
            "custom_quals_jar_path" to ".agent-libs/custom-quals.jar",
            "production_globs" to "src/**",
            "test_globs" to "test/**",
        )
        val renderedTrue = PromptTemplates.render(
            template,
            common + ("non_triviality_requirement" to nonTrivialityRequirement(true)),
        )
        val renderedFalse = PromptTemplates.render(
            template,
            common + ("non_triviality_requirement" to nonTrivialityRequirement(false)),
        )

        assertTrue(renderedTrue.contains("must report at least one"))
        assertTrue(renderedTrue.contains("vacuous"))
        assertFalse(renderedFalse.contains("must report at least one"))
        assertFalse(renderedFalse.contains("vacuous"))
        assertFalse(renderedTrue.contains("Non-triviality hint enabled"))
        assertFalse(renderedFalse.contains("Non-triviality hint enabled"))
        assertFalse(renderedTrue.contains("{{"))
        assertFalse(renderedFalse.contains("{{"))
    }
}
