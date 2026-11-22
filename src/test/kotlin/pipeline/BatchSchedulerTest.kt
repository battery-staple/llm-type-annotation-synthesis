package pipeline

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pipeline.util.CommitMetadata

class BatchSchedulerTest {
    @Test
    fun `limit gate admits exactly limit concurrent calls`() = runBlocking {
        val gate = LimitedDownstreamGate(7)
        val admissions = withContext(Dispatchers.Default) {
            (1..100).map { async { gate.admit() } }.awaitAll()
        }
        assertEquals(7, admissions.count { it })
        assertFalse(gate.admit())
        assertTrue(DownstreamGate.limited(null).admit())
        assertFalse(LimitedDownstreamGate(0).admit())
    }

    @Test
    fun `scheduler bounds execution isolates failures and preserves input order`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val results = BatchScheduler(3).run((0 until 12).toList()) { value ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try {
                delay(10)
                if (value == 4) error("isolated")
                value * 2
            } finally {
                active.decrementAndGet()
            }
        }
        assertTrue(maximum.get() <= 3)
        assertEquals((0 until 12).toList(), results.mapIndexed { index, result ->
            when (result) {
                is BatchItem.Completed -> result.value / 2
                is BatchItem.Failed -> index
            }
        })
        assertTrue(results[4] is BatchItem.Failed)
        assertEquals(12, results.size)
    }

    @Test
    fun `crawler keeps every non-root commit for triage while gate caps downstream`() = runBlocking {
        val root = CommitMetadata("root", emptyList(), "root", "")
        val commits = listOf(root) + (1..20).map {
            CommitMetadata("commit$it", listOf("parent$it"), "commit", "")
        }
        val candidates = crawlerTriageCandidates(commits)
        val gate = LimitedDownstreamGate(3)
        val triaged = AtomicInteger()
        val downstream = AtomicInteger()
        BatchScheduler(5).run(candidates) {
            triaged.incrementAndGet()
            gate.admit().also { if (it) downstream.incrementAndGet() }
        }
        assertEquals(20, triaged.get())
        assertEquals(3, downstream.get())
    }

    @Test
    fun `scheduler propagates cancellation`() = runBlocking {
        val task = async {
            BatchScheduler(1).run(listOf(1)) { awaitCancellation() }
        }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
    }
}
