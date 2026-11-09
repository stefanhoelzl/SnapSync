package app.snapsync.mock

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.Crumb
import app.snapsync.domain.crash.EventBounds
import app.snapsync.domain.report.DumpHandOff
import app.snapsync.domain.report.DumpResult
import app.snapsync.levers.WorldEvent
import app.snapsync.ports.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * An honest in-memory [CrashReporter]: flips a constructor-injected cell on [start], and records what its backend
 * receives — every automatic event [capture] takes and every dump [sendDump] takes, each shaped by the shaping it was
 * started with as the real channel shapes what leaves, and each carrying the contexts standing when it was sent (the
 * latest [setContext] of each name; an event's own context of the same name wins, as on the capture's own scope).
 * Whoever owns the records observes that the process started reporting and what it transmitted; the fake itself exposes
 * only the port (the honesty gate). Repeated starts are the contract's no-op — the cell just stays `true`.
 *
 * Licensed by `CrashReporterContract` (`:test:contracts`) up to its `RECEIVED` state — one capture is one event, its
 * tags, the latest context and the breadcrumbs handed over before it ride it, a dump arrives as shaped. What only the
 * SDK and the adapter do on the way out (`ON_THE_WIRE`) — the SDK's install id, the build facts, the size caps — this
 * fake does not model.
 */
internal class InMemoryCrashReporter(
    private val started: MutableStateFlow<Boolean>,
    private val dumps: ReceivedDumps,
    private val events: ReceivedEvents = ReceivedEvents(),
) : CrashReporter {

    constructor() : this(MutableStateFlow(false), ReceivedDumps())

    /** The contexts standing on the channel, by name — each replaced whole by the next [setContext] of its name. */
    private val standing = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    @Volatile private var shaping: CrashShaping? = null

    /** The breadcrumbs the SDK keeps on its scope — the latest [EventBounds.MAX_BREADCRUMBS] — riding every event. */
    private val crumbs = MutableStateFlow<List<Crumb>>(emptyList())

    override fun start(options: CrashOptions, shaping: CrashShaping) {
        this.shaping = shaping
        started.value = true
    }

    override fun capture(event: CrashEvent) {
        if (!started.value) return
        shaping?.event(carrying(event).copy(breadcrumbs = crumbs.value + event.breadcrumbs))?.let(events::receive)
    }

    override fun breadcrumb(crumb: Crumb) {
        if (started.value) crumbs.update { (it + crumb).takeLast(EventBounds.MAX_BREADCRUMBS) }
    }

    override fun setContext(name: String, fields: Map<String, String>) {
        if (started.value) standing.update { it + (name to fields) }
    }

    override suspend fun sendDump(dump: CrashEvent): DumpHandOff {
        if (!started.value) return DumpResult.NotSent("the channel is not running")
        shaping?.event(carrying(dump))?.let(dumps::receive)
        return DumpResult.Queued
    }

    /** [event] with the standing contexts it rides with; its own of the same name win, as on the capture's scope. */
    private fun carrying(event: CrashEvent): CrashEvent = event.copy(contexts = standing.value + event.contexts)
}

/**
 * What the reporter's backend received, in order, in the caller's [cell] — and, where the control channel watches, each
 * dump told to it as it arrives (`docs/testing.md`, "How a test waits").
 */
class ReceivedDumps(internal val cell: MutableStateFlow<List<CrashEvent>> = MutableStateFlow(emptyList())) {
    @Volatile private var watcher: WorldWatcher = WorldWatcher {}

    /** Tells [listener] each dump as it arrives. */
    fun watch(listener: WorldWatcher) {
        watcher = listener
    }

    internal fun receive(dump: CrashEvent) {
        cell.value = cell.value + dump
        watcher.told(WorldEvent.DumpReceived(received(dump)))
    }
}

/**
 * The automatic events the reporter's backend received, in order, in the caller's [cell] — and, where the control
 * channel watches, each told to it as it arrives (`docs/testing.md`, "How a test waits").
 */
class ReceivedEvents(internal val cell: MutableStateFlow<List<CrashEvent>> = MutableStateFlow(emptyList())) {
    @Volatile private var watcher: WorldWatcher = WorldWatcher {}

    /** Tells [listener] each event as it arrives. */
    fun watch(listener: WorldWatcher) {
        watcher = listener
    }

    internal fun receive(event: CrashEvent) {
        cell.value = cell.value + event
        watcher.told(WorldEvent.EventReceived(receivedEvent(event)))
    }
}
