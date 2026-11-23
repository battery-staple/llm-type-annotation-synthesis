package pipeline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pipeline.stages.EvalVerdict
import pipeline.stages.RunId
import pipeline.stages.StageName
import pipeline.stages.VerificationResult

class StopAfterTest {

    @Test
    fun `no stop request runs every stage`() {
        StageName.entries.forEach { completed ->
            assertFalse(
                PipelineOrchestrator.stopsAfter(completed, null),
                "expected $completed to continue when no stop point is set"
            )
        }
    }

    @Test
    fun `each stage stops exactly at its own boundary`() {
        StageName.entries.filter { it != StageName.VERIFICATION }.forEach { stopAfter ->
            StageName.entries.forEach { completed ->
                assertEquals(
                    completed == stopAfter,
                    PipelineOrchestrator.stopsAfter(completed, stopAfter),
                    "stop-after=$stopAfter, completed=$completed"
                )
            }
        }
    }

    @Test
    fun `stop after verification is the full pipeline`() {
        StageName.entries.forEach { completed ->
            assertFalse(
                PipelineOrchestrator.stopsAfter(completed, StageName.VERIFICATION),
                "stopping after verification must behave like an unflagged run"
            )
        }
    }

    @Test
    fun `stop after triage never triggers upstream`() {
        assertFalse(PipelineOrchestrator.stopsAfter(StageName.INGESTION, StageName.TRIAGE))
        assertTrue(PipelineOrchestrator.stopsAfter(StageName.TRIAGE, StageName.TRIAGE))
        assertFalse(PipelineOrchestrator.stopsAfter(StageName.SYNTHESIS, StageName.TRIAGE))
    }

    @Test
    fun `parses every canonical stage identifier`() {
        StageName.entries.forEach { stage ->
            assertEquals(stage, parseStopAfter(stage.identifier))
        }
    }

    @Test
    fun `parses identifiers case-insensitively and trims`() {
        assertEquals(StageName.TRIAGE, parseStopAfter("  TRIAGE "))
        assertEquals(StageName.APPLICATION, parseStopAfter("Application"))
    }

    @Test
    fun `rejects unknown stages and lists the valid ones`() {
        val error = assertFailsWith<IllegalArgumentException> { parseStopAfter("triagee") }

        val message = assertNotNull(error.message)
        assertTrue(message.contains("triagee"), message)
        StageName.entries.forEach { assertTrue(message.contains(it.identifier), message) }
    }

    @Test
    fun `rejects an empty stage name`() {
        assertFailsWith<IllegalArgumentException> { parseStopAfter("   ") }
    }

    @Test
    fun `each stop point is recorded in the run status`() {
        StageName.entries.forEach { stage ->
            assertEquals("stopped_after_${stage.identifier}", PipelineOrchestrator.stoppedRunStatus(stage))
        }
    }

    @Test
    fun `only triage-verified outcomes consume the crawler budget`() {
        assertTrue(PipelineOrchestrator.countsAsVerified(outcome()))
        assertTrue(PipelineOrchestrator.countsAsVerified(stoppedOutcome(StageName.TRIAGE)))
        assertTrue(PipelineOrchestrator.countsAsVerified(stoppedOutcome(StageName.SYNTHESIS)))
        assertFalse(PipelineOrchestrator.countsAsVerified(stoppedOutcome(StageName.INGESTION)))
        assertFalse(PipelineOrchestrator.countsAsVerified(stoppedOutcome(StageName.PREFLIGHT)))
        assertFalse(PipelineOrchestrator.countsAsVerified(outcome(unsupportedReason = "module_mode")))
        assertFalse(PipelineOrchestrator.countsAsVerified(outcome(discarded = true, haltMessage = "bad test patch")))
    }

    @Test
    fun `stopped run reports the stopped lifecycle, not success or failure`() {
        val outcome = stoppedOutcome(StageName.TRIAGE)

        assertEquals("stopped", lifecycleStatus(outcome))
        assertEquals(StageName.TRIAGE, outcome.stoppedAfter)
        assertFalse(outcome.discarded)
    }

    @Test
    fun `limit-skipped verified run has triage-only lifecycle`() {
        val outcome = outcome(limitSkipped = true)
        assertEquals("triage_only", lifecycleStatus(outcome))
        assertTrue(outcome.limitSkipped)
        assertEquals(EvalVerdict.VERIFICATION_MISSING, outcome.result.verdict)
    }

    @Test
    fun `stopped run skips verdict evaluation`() {
        val outcome = stoppedOutcome(StageName.TRIAGE)

        // A stopped run has no evaluation: the summary must not claim success.
        assertEquals(EvalVerdict.VERIFICATION_MISSING, outcome.result.verdict)
    }

    @Test
    fun `preflight runs between ingestion and triage`() {
        val order = StageName.entries
        assertEquals(order.indexOf(StageName.INGESTION) + 1, order.indexOf(StageName.PREFLIGHT))
        assertEquals(order.indexOf(StageName.PREFLIGHT) + 1, order.indexOf(StageName.TRIAGE))
        assertEquals(StageName.PREFLIGHT, parseStopAfter("preflight"))
    }

    @Test
    fun `without preflight the six stages keep their original step numbers`() {
        val stages = StageName.entries.filter { it != StageName.PREFLIGHT }
        assertEquals((1..6).toList(), stages.map { PipelineOrchestrator.stepNumber(it, preflightEnabled = false) })
        assertEquals((1..7).toList(), StageName.entries.map { PipelineOrchestrator.stepNumber(it, preflightEnabled = true) })
        assertEquals(2, PipelineOrchestrator.stepNumber(StageName.PREFLIGHT, preflightEnabled = true))
        assertEquals(2, PipelineOrchestrator.stepNumber(StageName.TRIAGE, preflightEnabled = false))
    }

    @Test
    fun `stopping after preflight requires preflight to run`() {
        assertFailsWith<com.github.ajalt.clikt.core.UsageError> {
            requirePreflightForStop(StageName.PREFLIGHT, preflightEnabled = false)
        }
        requirePreflightForStop(StageName.PREFLIGHT, preflightEnabled = true)
        requirePreflightForStop(StageName.TRIAGE, preflightEnabled = false)
        requirePreflightForStop(null, preflightEnabled = false)
    }

    @Test
    fun `preflight is off unless enabled`() {
        assertFalse(pipeline.config.ProjectConfig().preflight.enabled)
    }

    @Test
    fun `unsupported repository has its own lifecycle and no verdict`() {
        val outcome = outcome(unsupportedReason = "suite_timeout")
        assertEquals("unsupported", lifecycleStatus(outcome))
        assertEquals(PipelineOrchestrator.UNSUPPORTED_STATUS, lifecycleStatus(outcome))
        assertEquals(EvalVerdict.VERIFICATION_MISSING, outcome.result.verdict)
    }

    @Test
    fun `discarded and failed runs keep their lifecycle labels`() {
        assertEquals("discarded", lifecycleStatus(outcome(discarded = true, haltMessage = "bad test patch")))
        assertEquals("failed", lifecycleStatus(outcome(haltMessage = "stage halted")))
        assertEquals("success", lifecycleStatus(outcome()))
    }

    private fun stoppedOutcome(stage: StageName) = outcome(stoppedAfter = stage)

    private fun outcome(
        discarded: Boolean = false,
        haltMessage: String? = null,
        stoppedAfter: StageName? = null,
        limitSkipped: Boolean = false,
        unsupportedReason: String? = null
    ) = PipelineRunOutcome(
        runId = RunId("run-stop-after-test"),
        result = VerificationResult(
            verdict = EvalVerdict.VERIFICATION_MISSING,
            regressionTestPassed = null,
            originalTestsPassed = null,
            remainingCheckerErrors = null,
            testResultsXml = null,
            suiteResultsXml = null,
            verificationSummaryFile = File("incomplete_summary.json")
        ),
        discarded = discarded,
        haltMessage = haltMessage,
        stoppedAfter = stoppedAfter,
        limitSkipped = limitSkipped,
        unsupportedReason = unsupportedReason
    )
}
