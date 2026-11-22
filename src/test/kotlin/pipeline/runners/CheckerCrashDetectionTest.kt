package pipeline.runners

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckerCrashDetectionTest {

    @Test
    fun `malformed qualifier hierarchy is reported as a checker crash`() {
        val output = """
            > Task :checkerPass FAILED
            error: PartyRef is not a key in the directSuperMap.  Does it have a @SubtypeOf annotation?
              Exception: org.checkerframework.javacutil.TypeSystemError: PartyRef is not a key in the directSuperMap.
        """.trimIndent()
        assertTrue(outputShowsCheckerCrash(output))
    }

    @Test
    fun `ordinary type errors are not a checker crash`() {
        val output = """
            src/main/java/A.java:46: error: [argument] incompatible argument for parameter accountId of Repo.debit.
            2 errors
        """.trimIndent()
        assertFalse(outputShowsCheckerCrash(output))
    }
}
