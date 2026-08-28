package app.snapsync.fake

import app.snapsync.model.DiagnosticDump
import app.snapsync.ports.DiagnosticsReporter

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [DiagnosticsReporter]: flips a constructor-injected cell on [start] and
 * appends every [send] to a constructor-injected list. Whoever owns the cells observes that the
 * composition started reporting and what it transmitted; the fake itself exposes only the port (the
 * honesty gate). Repeated starts are the contract's no-op — the cell just stays `true`.
 *
 * [configured] models the build's reporting configuration: `false` is every dev/sideload build, where
 * the port is inert and the dump affordance must not exist at all.
 */
internal class InMemoryDiagnosticsReporter(
    private val started: MutableStateFlow<Boolean>,
    private val sent: MutableStateFlow<List<DiagnosticDump>>,
    override val isConfigured: Boolean,
) : DiagnosticsReporter {

    constructor() : this(MutableStateFlow(false), MutableStateFlow(emptyList()), isConfigured = true)

    override fun start() {
        started.value = true
    }

    override fun send(dump: DiagnosticDump) {
        // The contract's no-op: an unconfigured build transmits nothing.
        if (!isConfigured) return
        sent.value = sent.value + dump
    }
}
