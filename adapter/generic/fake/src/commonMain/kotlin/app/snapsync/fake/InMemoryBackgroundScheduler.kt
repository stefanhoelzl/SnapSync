package app.snapsync.fake

import app.snapsync.ports.BackgroundScheduler
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [BackgroundScheduler] over the caller's [armed] cell — the system's queue of wakes for one
 * identifier, which holds at most one request: a submission replaces the pending one (`docs/architecture.md`,
 * `BackgroundSchedulerContract`), and a cancel clears it whether or not one was pending.
 */
internal class InMemoryBackgroundScheduler(private val armed: MutableStateFlow<Boolean>) : BackgroundScheduler {
    override fun scheduleNext() {
        armed.value = true
    }

    override fun cancel() {
        armed.value = false
    }
}
