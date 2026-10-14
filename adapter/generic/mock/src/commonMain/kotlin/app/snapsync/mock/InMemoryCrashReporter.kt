package app.snapsync.mock

import app.snapsync.levers.WorldEvent
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DumpHandOff
import app.snapsync.model.DumpResult
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * An honest in-memory [CrashReporter]: flips a constructor-injected cell on [start], and records what its backend
 * receives — every automatic event [capture] takes and every dump [sendDump] takes, each shaped by the registered
 * handler as the real channel shapes what leaves, and each carrying the contexts standing when it was sent (the latest
 * [setContext] of each name; an event's own context of the same name wins, as on the capture's own scope). Whoever owns
 * the records observes that the process started reporting and what it transmitted; the fake itself exposes only the
 * port (the honesty gate). Repeated starts are the contract's no-op — the cell just stays `true`.
 *
 * Licensed by `CrashReporterContract` (`:test:contracts`) up to its `RECEIVED` state — one capture is one event, its
 * tags and the latest context ride it, a dropped event leaves nothing, a dump arrives as shaped. What only the SDK and
 * the adapter do on the way out (`ON_THE_WIRE`) — breadcrumbs riding the next event, the SDK's install id, the build
 * facts, the size caps — this fake does not model: [breadcrumb] transmits nothing, though a running channel shapes
 * each crumb through the registered handler, as the real one does.
 */
internal class InMemoryCrashReporter(
    private val started: MutableStateFlow<Boolean>,
    private val dumps: ReceivedDumps,
    private val events: ReceivedEvents = ReceivedEvents(),
) : CrashReporter {

    constructor() : this(MutableStateFlow(false), ReceivedDumps())

    /** The contexts standing on the channel, by name — each replaced whole by the next [setContext] of its name. */
    private val standing = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    @Volatile private var handlers: CrashHandlers? = null

    override fun listen(handlers: CrashHandlers) {
        this.handlers = handlers
    }

    override fun start(options: CrashOptions) {
        started.value = true
    }

    override fun capture(event: CrashEvent) {
        if (!started.value) return
        // Nothing unshaped leaves: no handler, or a handler's null, sends nothing — as on the real channel.
        handlers?.onEvent?.invoke(carrying(event))?.let(events::receive)
    }

    override fun breadcrumb(crumb: Crumb) {
        if (started.value) handlers?.onBreadcrumb?.invoke(crumb)
    }

    override fun setContext(name: String, fields: Map<String, String>) {
        if (started.value) standing.update { it + (name to fields) }
    }

    override suspend fun sendDump(dump: CrashEvent): DumpHandOff {
        if (!started.value) return DumpResult.NotSent("the channel is not running")
        // Nothing unshaped leaves: no handler, or a handler's null, sends nothing — as on the real channel.
        handlers?.onEvent?.invoke(carrying(dump))?.let(dumps::receive)
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
