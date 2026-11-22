package pipeline.stages

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pipeline.ports.TestOutcome
import pipeline.ports.TestRunResult
import pipeline.ports.TestStatus

class VerificationVerdictTest {

    private fun outcome(status: TestStatus = TestStatus.FAILED): TestOutcome =
        TestOutcome(
            testClass = Fqcn("com.example.FooTest"),
            testMethod = "testBar",
            status = status,
            durationMs = 10L,
            failureMessage = if (status == TestStatus.PASSED) null else "expected true"
        )

    private fun runResult(
        xmlFile: File?,
        outcomes: List<TestOutcome>,
        success: Boolean,
        exitCode: Int = if (success) 0 else 1,
        compileFailed: Boolean = false,
        timedOut: Boolean = false
    ): TestRunResult = TestRunResult(
        success = success,
        exitCode = exitCode,
        outcomes = outcomes,
        xmlPaths = if (xmlFile == null) emptyList() else listOf(xmlFile),
        compileFailed = compileFailed,
        testsFound = outcomes.size,
        output = "",
        timedOut = timedOut
    )

    private fun tempXml(): File {
        val dir = Files.createTempDirectory("verdict-xml").toFile()
        dir.deleteOnExit()
        val xml = File(dir, "TEST-foo.xml")
        xml.writeText("<testsuite></testsuite>")
        xml.deleteOnExit()
        return xml
    }

    @Test
    fun `test patch additions are extracted from both flat and legacy test layouts`() {
        val patch = Files.createTempDirectory("test-patch").resolve("test_patch.diff").toFile()
        patch.deleteOnExit()
        patch.writeText(
            """
            diff --git a/test/cms/controller/persistence/FooTest.java b/test/cms/controller/persistence/FooTest.java
            --- a/test/cms/controller/persistence/FooTest.java
            +++ b/test/cms/controller/persistence/FooTest.java
            @@ -1,2 +1,12 @@
            +    @Test
            +    public void addedOne() {
            +        assertThat(true).isTrue();
            +    }
            +
            +    @Test
            +    @DisplayName("two")
            +    public void addedTwo() {
            +    }
            +
            +    public void preExisting() {
            diff --git a/src/test/java/com/example/BarTest.java b/src/test/java/com/example/BarTest.java
            --- a/src/test/java/com/example/BarTest.java
            +++ b/src/test/java/com/example/BarTest.java
            @@ -1,2 +1,6 @@
            +    @Test
            +    void addedThree() {
            +    }
            """.trimIndent()
        )

        assertEquals(
            setOf(
                TestCoordinate(Fqcn("cms.controller.persistence.FooTest"), "addedOne"),
                TestCoordinate(Fqcn("cms.controller.persistence.FooTest"), "addedTwo"),
                TestCoordinate(Fqcn("com.example.BarTest"), "addedThree")
            ),
            VerificationStage.testsAddedByPatch(patch)
        )
    }

    @Test
    fun `failed regression with valid JUnit evidence is substantive not missing`() {
        val xml = tempXml()
        val result = runResult(xml, listOf(outcome(TestStatus.FAILED)), success = false, exitCode = 1)
        assertFalse(VerificationStage.regressionEvidenceMissing(result))
        val verdict = VerificationStage.decideVerdict(
            checkerMissing = false,
            regEvidenceMissing = VerificationStage.regressionEvidenceMissing(result),
            suiteReportMissing = false,
            remainingCheckerErrors = 0,
            suiteFailed = 0,
            suiteExecuted = 0,
            regPassed = false,
            scope = VerificationScope.REGRESSION_ONLY
        )
        assertEquals(EvalVerdict.TYPE_SUCCESS_RUNTIME_FAIL, verdict)
    }

    @Test
    fun `full suite failure with valid evidence is suite failure not missing`() {
        val regXml = tempXml()
        val suiteXml = tempXml()
        val reg = runResult(regXml, listOf(outcome(TestStatus.PASSED)), success = true)
        val suite = runResult(
            suiteXml,
            listOf(outcome(TestStatus.PASSED), outcome(TestStatus.FAILED)),
            success = false,
            exitCode = 1
        )
        assertFalse(VerificationStage.regressionEvidenceMissing(reg))
        assertFalse(VerificationStage.suiteEvidenceMissing(suite, filteredExecuted = 2))
        val verdict = VerificationStage.decideVerdict(
            checkerMissing = false,
            regEvidenceMissing = false,
            suiteReportMissing = false,
            remainingCheckerErrors = 0,
            suiteFailed = 1,
            suiteExecuted = 2,
            regPassed = true,
            scope = VerificationScope.FULL_SUITE
        )
        assertEquals(EvalVerdict.SUITE_FAILURE, verdict)
    }

    @Test
    fun `absent report is verification missing`() {
        val empty = runResult(null, emptyList(), success = false)
        assertTrue(VerificationStage.regressionEvidenceMissing(empty))
        assertTrue(VerificationStage.suiteEvidenceMissing(empty, filteredExecuted = 0))
        val verdict = VerificationStage.decideVerdict(
            checkerMissing = false,
            regEvidenceMissing = true,
            suiteReportMissing = false,
            remainingCheckerErrors = 0,
            suiteFailed = 0,
            suiteExecuted = 0,
            regPassed = false,
            scope = VerificationScope.REGRESSION_ONLY
        )
        assertEquals(EvalVerdict.VERIFICATION_MISSING, verdict)
    }

    @Test
    fun `skipped-only report is verification missing`() {
        val xml = tempXml()
        val result = runResult(xml, listOf(outcome(TestStatus.SKIPPED)), success = false, exitCode = 1)
        assertTrue(VerificationStage.regressionEvidenceMissing(result))
    }

    @Test
    fun `unusable xml path is verification missing`() {
        val missing = File("/nonexistent/TEST-missing.xml")
        val result = runResult(missing, listOf(outcome(TestStatus.FAILED)), success = false)
        assertTrue(VerificationStage.regressionEvidenceMissing(result))
    }

    @Test
    fun `nonzero task with only passing report rows is missing evidence`() {
        val result = runResult(
            tempXml(),
            listOf(outcome(TestStatus.PASSED)),
            success = false,
            exitCode = 1
        )
        assertTrue(VerificationStage.regressionEvidenceMissing(result))
    }

    @Test
    fun `compile failure makes a report unusable even when it contains failures`() {
        val result = runResult(
            tempXml(),
            listOf(outcome(TestStatus.FAILED)),
            success = false,
            exitCode = 1,
            compileFailed = true
        )
        assertTrue(VerificationStage.regressionEvidenceMissing(result))
    }

    @Test
    fun `timeout makes partial failure report incomplete`() {
        val result = runResult(
            tempXml(),
            listOf(outcome(TestStatus.FAILED)),
            success = false,
            exitCode = 124,
            timedOut = true
        )
        assertTrue(VerificationStage.regressionEvidenceMissing(result))
    }

    @Test
    fun `suite task failing without reported test failures is missing evidence`() {
        val suite = runResult(
            tempXml(),
            listOf(outcome(TestStatus.PASSED)),
            success = false,
            exitCode = 1
        )
        assertTrue(VerificationStage.suiteEvidenceMissing(suite, filteredExecuted = 1))
    }

    @Test
    fun `suite task failing only because regression test failed retains suite evidence`() {
        val suite = runResult(
            tempXml(),
            listOf(outcome(TestStatus.PASSED), outcome(TestStatus.FAILED)),
            success = false,
            exitCode = 1
        )
        assertFalse(
            VerificationStage.suiteEvidenceMissing(
                suite,
                filteredExecuted = 1,
                regressionTestFailed = true
            )
        )
    }

    @Test
    fun `successful verification stays full success`() {
        val verdict = VerificationStage.decideVerdict(
            checkerMissing = false,
            regEvidenceMissing = false,
            suiteReportMissing = false,
            remainingCheckerErrors = 0,
            suiteFailed = 0,
            suiteExecuted = 3,
            regPassed = true,
            scope = VerificationScope.FULL_SUITE
        )
        assertEquals(EvalVerdict.FULL_SUCCESS, verdict)
    }

    @Test
    fun `checker errors outrank test failures`() {
        val verdict = VerificationStage.decideVerdict(
            checkerMissing = false,
            regEvidenceMissing = false,
            suiteReportMissing = false,
            remainingCheckerErrors = 2,
            suiteFailed = 1,
            suiteExecuted = 2,
            regPassed = false,
            scope = VerificationScope.FULL_SUITE
        )
        assertEquals(EvalVerdict.FIXER_INCOMPLETE, verdict)
    }

    @Test
    fun `never emits legacy regression introduced label`() {
        val verdict = VerificationStage.decideVerdict(
            checkerMissing = false,
            regEvidenceMissing = false,
            suiteReportMissing = false,
            remainingCheckerErrors = 0,
            suiteFailed = 1,
            suiteExecuted = 1,
            regPassed = true,
            scope = VerificationScope.FULL_SUITE
        )
        assertEquals(EvalVerdict.SUITE_FAILURE, verdict)
    }
}
