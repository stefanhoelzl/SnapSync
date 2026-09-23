package app.snapsync.fake

import app.snapsync.model.DiagnosticDump
import app.snapsync.model.ProcessMetricReport
import app.snapsync.ports.DiagnosticsReporter

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [DiagnosticsReporter]: flips a constructor-injected cell on [start] and appends every
 * [send] to a constructor-injected list. Whoever owns the cells observes that the composition started
 * reporting and what it transmitted; the fake itself exposes only the port (the honesty gate). Repeated
 * starts are the contract's no-op — the cell just stays `true`.
 *
 * [isConfigured] models the build's reporting configuration: `false` is every dev/sideload build, where the
 * port is inert — nothing starts, nothing is sent — and the dump affordance must not exist at all.
 *
 * Licensed by `DiagnosticsReporterContract` (`:test:contracts`) up to its `CONFIGURED` state. What leaves a
 * device — automatic capture, the scrub, the process account riding later events — is the SDK's and the
 * adapter's, and this fake does not model it.
 */
internal class InMemoryDiagnosticsReporter(
    private val started: MutableStateFlow<Boolean>,
    private val sent: MutableStateFlow<List<DiagnosticDump>>,
    override val isConfigured: Boolean,
) : DiagnosticsReporter {

    constructor() : this(MutableStateFlow(false), MutableStateFlow(emptyList()), isConfigured = true)

    override fun start() {
        // The contract's no-op: an unconfigured build starts nothing, as the real adapter starts no SDK.
        if (!isConfigured) return
        started.value = true
    }

    override fun send(dump: DiagnosticDump) {
        // The contract's no-op: an unconfigured build transmits nothing.
        if (!isConfigured) return
        sent.value = sent.value + dump
    }

    override fun describeProcess(report: ProcessMetricReport) {
        // The port's guarantee: describing implies started. The account itself is not held: it rides what
        // the real channel transmits, which this fake does not model.
        start()
    }
}
