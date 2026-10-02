package app.snapsync.mock

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.Completion
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The mocks of the operating system's background execution (`docs/testing.md`, "Mocks"): when it wakes the app, how
// long it keeps it awake, and whether it runs the upload extension. Nothing here fires on its own — the operator plays
// the operating system.

/**
 * The operating system's scheduled wakes: its queue — at most one request per wake — survives the app's process, and
 * the handlers are those of the process that registered last.
 */
class WakeMock(
    /** The wakes this platform has; iOS has only the heartbeat. */
    internal val supported: Set<WakeId> = setOf(WakeId.Heartbeat),
) {
    internal val pending = MutableStateFlow<Map<WakeId, WakeTrigger>>(emptyMap())
    internal var handlers: WakeHandlers? = null
    internal var scheduled = 0

    fun port(): Wake = object : Wake {
        private val queue = InMemoryWake(pending, supported)

        override fun listen(handlers: WakeHandlers) {
            this@WakeMock.handlers = handlers
        }

        override fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult =
            queue.schedule(id, trigger).also { if (id == WakeId.Heartbeat && it == ScheduleResult.Scheduled) scheduled++ }

        override fun cancel(id: WakeId) {
            queue.cancel(id)
        }
    }

    val operator: WakeOperator = WakeOperator(this)
}

class WakeOperator internal constructor(private val mock: WakeMock) {
    /** How many heartbeat requests the operating system accepted. */
    val heartbeatsScheduled: Int get() = mock.scheduled

    /** The requests it holds right now. */
    val pendingWakes: Map<WakeId, WakeTrigger> get() = mock.pending.value

    /** The operating system wakes the app for [id], handing it [completion]. */
    fun fire(id: WakeId, completion: Completion) {
        val handlers = checkNotNull(mock.handlers) { "no process registered for wakes — nothing would receive this one" }
        // Every wake is one-shot: the operating system launching it is what consumes the request.
        mock.pending.value -= id
        handlers.onWake(id, completion)
    }
}

/**
 * The operating system's table of outstanding background-time holds. It survives a process in the sense a real table
 * does not end a dead process's holds: they simply never end.
 */
class BackgroundTimeMock {
    internal val held = MutableStateFlow<List<HeldBackgroundTime>>(emptyList())

    fun port(): BackgroundTime = InMemoryBackgroundTime(held)

    val operator: BackgroundTimeOperator = BackgroundTimeOperator(this)
}

class BackgroundTimeOperator internal constructor(private val mock: BackgroundTimeMock) {
    /** The holds outstanding, as a cell. */
    val holds: StateFlow<List<HeldBackgroundTime>> = mock.held.asStateFlow()

    /** The operating system says every outstanding hold's time is up. */
    fun expireAll() {
        mock.held.value.forEach { it.expire() }
    }
}

/**
 * The operating system's record of the upload extension's registration. [supported] `false` — the JVM, iOS below
 * 26.1 — answers `Unsupported` to everything.
 */
class ExtensionRegistryMock(supported: Boolean = false) {
    internal val record: MutableStateFlow<Boolean>? = if (supported) MutableStateFlow(false) else null

    fun port(): ExtensionRegistry = InMemoryExtensionRegistry(record)

    val operator: ExtensionRegistryOperator = ExtensionRegistryOperator(this)
}

class ExtensionRegistryOperator internal constructor(private val mock: ExtensionRegistryMock) {
    /** Whether a registration exists; `null` on a platform without the extension. */
    val registered: Boolean? get() = mock.record?.value
}
