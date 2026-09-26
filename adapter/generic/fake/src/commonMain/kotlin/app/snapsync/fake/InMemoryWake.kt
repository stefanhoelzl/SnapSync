package app.snapsync.fake

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An honest in-memory [Wake] over the caller's [pending] cell — the system's queue of wake requests, which holds at
 * most one per [WakeId]: a request replaces the pending one (`WakeContract`), and a cancel clears it whether or not
 * one was pending. A wake outside [supported] is answered [ScheduleResult.Unsupported] and queues nothing, as a
 * platform without that kind of wake answers.
 *
 * Nothing in memory ever fires a wake: the registered handlers are kept for whoever plays the operating system.
 */
internal class InMemoryWake(
    private val pending: MutableStateFlow<Map<WakeId, WakeTrigger>>,
    private val supported: Set<WakeId>,
) : Wake {
    private var handlers: WakeHandlers? = null

    override fun listen(handlers: WakeHandlers) {
        this.handlers = handlers
    }

    override fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult {
        if (id !in supported) return ScheduleResult.Unsupported
        pending.update { it + (id to trigger) }
        return ScheduleResult.Scheduled
    }

    override fun cancel(id: WakeId) {
        pending.update { it - id }
    }
}
