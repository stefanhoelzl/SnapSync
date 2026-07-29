package app.snapsync.ports

import app.snapsync.model.DiagnosticDump

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
}
