package app.snapsync.compose

import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Files
import app.snapsync.ports.LogScope
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import app.snapsync.services.crash.CrashReporting
import app.snapsync.services.crash.ProcessAccount
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger

/**
 * The ports every process has exactly one of, whatever it composes afterwards (`docs/architecture.md`, "One
 * shared composition"): each fronts a process-global OS source, so a second adapter would be a second view of the
 * same global — the four crash reporters this replaced were exactly that.
 */
class ProcessPorts(
    /** The crash-reporting channel — one per process. */
    val crashReporter: CrashReporter,
    /** The OS's account of this process; [ProcessMetrics.None] where there is no provider. */
    val processMetrics: ProcessMetrics,
    /** This process's files, by area. One instance: every file-backed service is built over it. */
    val files: Files,
    /** The ambient entry-point seam the device-log lines and the crash channel's `entry_point` tag read. */
    val entryContext: LogScope,
    /** Where this build reports to, or `null` for a build that reports nowhere — a constant of the build. */
    val dsn: String?,
)

/**
 * What [snapSyncProcess] set up, handed to every composition the process builds afterwards — and required by each,
 * so no composition can be built in a process whose crash reporting has not started.
 */
class ProcessServices internal constructor(
    /** The process's crash reporting — started. */
    val crash: CrashReporting,
    /**
     * What a delivered process-metric report does. Exposed for ONE caller besides the port: the control channel,
     * which drives a synthetic report through this very instance, so the path the OS drives is the path a test drives.
     */
    val processAccount: ProcessAccount,
    /** The process's one [Files]. */
    val files: Files,
    /** The process's one entry-point seam. */
    val entryContext: LogScope,
) {
    /**
     * The log writers this process's services add to the root's device-log writers — today the crash channel's,
     * where the build reports.
     */
    val logWriters: List<LogWriter> get() = listOfNotNull(crash.logWriter)

    /**
     * Append [logWriters] to Kermit's writer list — called by a ROOT, never by a composition: the list is a process
     * global, and a root is what owns a process (a JVM world composes many processes in one, and installs none).
     */
    fun installLogWriters() = logWriters.forEach { Logger.addLogWriter(it) }
}

/**
 * Set up what is per-process — **called first by every root**, before it composes anything else, and exactly once.
 *
 * Crash reporting starts HERE, as the process's first act: it must be live before any other wiring can fail, and it
 * reads only this build's own configuration, so it is safe on a locked background launch. Then the process metrics
 * are listened to — only now, because listening is a commitment: a provider may hand a held report over exactly once,
 * so the handler must already be live (it is: [ProcessAccount] over the started channel).
 */
fun snapSyncProcess(ports: ProcessPorts): ProcessServices {
    val crash = CrashReporting(ports.crashReporter, ports.dsn, ports.entryContext)
    ports.crashReporter.listen(CrashHandlers(onEvent = crash::shapeEvent, onBreadcrumb = crash::shapeCrumb))
    crash.start()
    val account = ProcessAccount(crash)
    ports.processMetrics.listen(MetricHandlers(onReport = account::handle))
    return ProcessServices(crash, account, ports.files, ports.entryContext)
}
