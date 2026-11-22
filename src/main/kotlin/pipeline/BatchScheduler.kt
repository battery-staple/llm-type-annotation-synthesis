package pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import pipeline.util.CommitMetadata

sealed interface BatchItem<out T> {
    data class Completed<T>(val value: T) : BatchItem<T>
    data class Failed(val error: Throwable) : BatchItem<Nothing>
}

/** Runs one task coroutine per input while limiting active runners to [jobs]. */
class BatchScheduler(private val jobs: Int) {
    init { require(jobs >= 1) { "jobs must be at least 1" } }

    suspend fun <I, O> run(inputs: List<I>, runner: suspend (I) -> O): List<BatchItem<O>> = coroutineScope {
        val permits = Semaphore(jobs)
        inputs.map { input ->
            async {
                try {
                    BatchItem.Completed(permits.withPermit {
                        withContext(Dispatchers.IO) { runner(input) }
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    BatchItem.Failed(failure)
                }
            }
        }.awaitAll()
    }
}

interface DownstreamGate {
    fun admit(): Boolean

    companion object {
        val AllowAll: DownstreamGate = object : DownstreamGate { override fun admit() = true }
        fun limited(limit: Int?): DownstreamGate = if (limit == null) AllowAll else LimitedDownstreamGate(limit)
    }
}

/** Atomic reservation gate; create a new instance for each batch. */
class LimitedDownstreamGate(private val limit: Int) : DownstreamGate {
    private val admitted = AtomicInteger()
    init { require(limit >= 0) { "limit must be non-negative" } }

    override fun admit(): Boolean {
        while (true) {
            val current = admitted.get()
            if (current >= limit) return false
            if (admitted.compareAndSet(current, current + 1)) return true
        }
    }
}

/** Root commits have no parent to use as C-1; all others remain triage candidates. */
internal fun crawlerTriageCandidates(commits: List<CommitMetadata>): List<CommitMetadata> =
    commits.filter { it.parents.isNotEmpty() }
