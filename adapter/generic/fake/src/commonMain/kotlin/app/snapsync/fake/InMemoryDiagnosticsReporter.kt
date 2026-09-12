package app.snapsync.fake

import app.snapsync.model.DiagnosticDump
import app.snapsync.model.ProcessMetricReport
import app.snapsync.ports.DiagnosticsReporter

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [DiagnosticsReporter]: flips a constructor-injected cell on [start] and
 * appends every [send] to a constructor-injected list, and holds the latest [describeProcess] in
 * a constructor-injected cell. Whoever owns the cells observes that the
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
    private val described: MutableStateFlow<ProcessMetricReport?> = MutableStateFlow(null),
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

    override fun describeProcess(report: ProcessMetricReport) {
        // The port's guarantee: describing implies started. Modelled here as well as in the real
        // impl, because a fake that skipped it would model a contract nobody has — and this is the
        // half a test can actually observe.
        start()
        // The contract's no-op, and its "replaces rather than accumulates" half: the cell holds the
        // most recent account, never a growing pile.
        if (!isConfigured) return
        described.value = report
    }
}
