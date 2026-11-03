package app.snapsync.scheduling

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The work inline deliveries started and did not await, by entry point, since each was last awaited — what a
 * later delivery answers for: a session's drain report waits for what its finished transfers' deliveries persisted
 * only partly before they returned. Deliveries register from the platform's own threads, so it is one atomic cell.
 */
internal class UnawaitedWork {
    private val held = MutableStateFlow<Map<String, List<Job>>>(emptyMap())

    /** [job], started by an inline delivery of [entry], counts among what [await] waits for. */
    fun track(entry: String, job: Job): Job {
        held.update { now -> now + (entry to (now[entry].orEmpty().filterNot { it.isCompleted } + job)) }
        return job
    }

    /** Awaits what the inline deliveries of [entry] started since the last call, forgetting it. */
    suspend fun await(entry: String) = held.getAndUpdate { it - entry }[entry].orEmpty().forEach { it.join() }
}
