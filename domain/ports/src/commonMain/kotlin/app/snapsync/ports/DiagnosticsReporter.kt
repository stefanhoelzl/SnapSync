package app.snapsync.ports

import app.snapsync.model.DiagnosticDump
import app.snapsync.model.ProcessMetricReport

/**
 * Reporting this process's diagnostics off-device for the operator (capability `crash-reporting`):
 * starts the reporting channel, if this build carries one. Named for the need — any platform that
 * can report failures off-device can seat this.
 *
 * Contract:
 * - **Configured by the build, not the caller**: a build without reporting configuration (every
 *   dev/sideload/simulator build) makes [start] a complete no-op — no SDK, no connection.
 * - **Idempotent**: the app process composes both `snapSyncApp` and (on the app-driven tier)
 *   `uploadCore`, and each starts the port as its first act; the second call must change nothing.
 * - **Automatic** capture does NOT cross this port: errors reach the channel through the logging
 *   seam (the reporting adapter registers a log writer when it starts), so features stay free of
 *   per-call-site instrumentation. Only the deliberate, operator-initiated dump crosses explicitly.
 */
interface DiagnosticsReporter {

    /**
     * Whether this build carries reporting configuration at all.
     *
     * Read by `compose/` to decide whether the operator-initiated dump command exists: a build that
     * could send nothing must offer no affordance that suggests it can (capability
     * `diagnostic-logging`). Constant for the process — it is a property of the build.
     */
    val isConfigured: Boolean

    fun start()

    /**
     * Transmit one operator-initiated diagnostic dump (capability `diagnostic-logging`).
     *
     * A complete no-op when the build is unconfigured, on the same rule as [start]. Delivery is the
     * channel's business: an implementation may queue and retransmit later, so returning does **not**
     * mean the dump has left the device, and no caller may claim it has.
     */
    fun send(dump: DiagnosticDump)

    /**
     * Attach [report] as the standing account of how this process has been behaving (capability
     * `crash-reporting`), so that **every** event this process reports afterwards carries it —
     * including a crash captured here and delivered on a later launch.
     *
     * Named for what it does to the channel, not for the reporting SDK's own vocabulary. It exists on
     * this port and not beside the metric adapter because the SDK is confined to one module, and that
     * module is not the one that reads process metrics.
     *
     * **Replaces rather than accumulates**: a later report supersedes an earlier one, so what rides an
     * event is always the most recent account, never a growing pile. Implementations SHALL make this
     * survive a fatal event — the whole value is that a crash arrives next to the OS's explanation of
     * recent terminations.
     *
     * A complete no-op when the build is unconfigured, on the same rule as [start] and [send].
     */
    fun describeProcess(report: ProcessMetricReport)
}
