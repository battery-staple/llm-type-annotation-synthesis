package pipeline.util

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvTest {
    @AfterTest
    fun reset() = Env.resetForTests()

    @Test
    fun `parses dotenv syntax`() {
        val values = Env.parse(
            """
            # full-line comment
            FOO=bar
            export BAZ="quoted value"
            SINGLE='literal # hash'
            TRAILING=value # inline comment
            EMPTY=
            HAS_EQUALS=a=b
            INVALID-KEY=nope
            """.trimIndent()
        )

        assertEquals("bar", values["FOO"])
        assertEquals("quoted value", values["BAZ"])
        assertEquals("literal # hash", values["SINGLE"])
        assertEquals("value", values["TRAILING"])
        assertEquals("", values["EMPTY"])
        assertEquals("a=b", values["HAS_EQUALS"])
        assertNull(values["INVALID-KEY"])
    }

    @Test
    fun `decodes escaped double quoted values`() {
        val values = Env.parse("MULTILINE=\"first\\nsecond\\tcolumn\\\"quote\\\"\"")

        assertEquals("first\nsecond\tcolumn\"quote\"", values["MULTILINE"])
    }

    @Test
    fun `process environment wins over dotenv and child receives only missing keys`() {
        val dir = Files.createTempDirectory("pipeline-env").toFile()
        try {
            val file = dir.resolve(".env")
            file.writeText(
                """
                PATH=/bogus/path
                PIPELINE_ENV_TEST_ONLY=from-dotenv
                """.trimIndent()
            )

            Env.load(file)

            assertEquals(System.getenv("PATH"), Env.get("PATH"))
            assertEquals("from-dotenv", Env.get("PIPELINE_ENV_TEST_ONLY"))

            val child = Env.forChild()
            assertTrue("PIPELINE_ENV_TEST_ONLY" in child)
            assertTrue("PATH" !in child)

            val explicit = mutableMapOf("PATH" to "explicit-value")
            Env.applyTo(explicit)
            assertEquals("explicit-value", explicit["PATH"])
            assertEquals("from-dotenv", explicit["PIPELINE_ENV_TEST_ONLY"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `loads dotenv next to the config file`() {
        val dir = Files.createTempDirectory("pipeline-env-config").toFile()
        try {
            val config = dir.resolve("project_config.yaml")
            config.writeText("triage:\n  mode: manifest\n")
            dir.resolve(".env").writeText("PIPELINE_ENV_TEST_NEAR_CONFIG=yes\n")

            Env.loadFor(config, explicitEnvFile = null)

            assertEquals("yes", Env.get("PIPELINE_ENV_TEST_NEAR_CONFIG"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
