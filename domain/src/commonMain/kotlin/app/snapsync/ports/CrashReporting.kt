package app.snapsync.ports

/**
 * Crash/error reporting for the operator (capability `crash-reporting`): starts the process's
 * reporting channel, if this build carries one. Named for the need — any platform that can report
 * failures off-device can seat this.
 *
 * Contract:
 * - **Configured by the build, not the caller**: a build without reporting configuration (every
 *   dev/sideload/simulator build) makes [start] a complete no-op — no SDK, no connection.
 * - **Idempotent**: the app process composes both `snapSyncApp` and (on the app-driven tier)
 *   `uploadCore`, and each starts the port as its first act; the second call must change nothing.
 * - Capture itself does NOT cross this port: errors reach the channel through the logging seam
 *   (the reporting adapter registers a log writer when it starts), so features stay free of
 *   per-call-site instrumentation.
 */
interface CrashReporting {
    fun start()
}
