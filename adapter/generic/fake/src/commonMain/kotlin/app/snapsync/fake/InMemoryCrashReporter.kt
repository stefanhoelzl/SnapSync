package app.snapsync.fake

import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DumpResult
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [CrashReporter]: flips a constructor-injected cell on [start] and appends every dump [sendDump]
 * takes — shaped by the registered handler, as the real channel shapes what leaves — to a constructor-injected list.
 * Whoever owns the cells observes that the process started reporting and what it transmitted; the fake itself exposes
 * only the port (the honesty gate). Repeated starts are the contract's no-op — the cell just stays `true`.
 *
 * Licensed by `CrashReporterContract` (`:test:contracts`) up to its `STARTED` state. What leaves a device — automatic
 * capture, breadcrumbs riding the next event, contexts, the SDK's install id — is the SDK's and the adapter's, and
 * this fake does not model it: [capture], [breadcrumb] and [setContext] transmit nothing here.
 */
internal class InMemoryCrashReporter(
    private val started: MutableStateFlow<Boolean>,
    private val dumps: MutableStateFlow<List<CrashEvent>>,
) : CrashReporter {

    constructor() : this(MutableStateFlow(false), MutableStateFlow(emptyList()))

    private var handlers: CrashHandlers? = null

    override fun listen(handlers: CrashHandlers) {
        this.handlers = handlers
    }

    override fun start(options: CrashOptions) {
        started.value = true
    }

    override fun capture(event: CrashEvent) = Unit

    override fun breadcrumb(crumb: Crumb) = Unit

    override fun setContext(name: String, fields: Map<String, String>) = Unit

    override suspend fun sendDump(dump: CrashEvent): DumpResult {
        if (!started.value) return DumpResult.NotSent("the channel is not running")
        // Nothing unshaped leaves: no handler, or a handler's null, sends nothing — as on the real channel.
        handlers?.onEvent?.invoke(dump)?.let { shaped -> dumps.value = dumps.value + shaped }
        return DumpResult.Queued
    }
}
