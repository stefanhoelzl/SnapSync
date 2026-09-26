package app.snapsync.compose

import app.snapsync.model.ReportDestination
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Files
import app.snapsync.ports.LogSink
import app.snapsync.ports.EntryContext
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import app.snapsync.services.crash.CrashReporting
import app.snapsync.services.crash.ProcessAccount
import app.snapsync.services.logs.SinkLogWriter
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
    /** Where this process's log lines are written — the device-log file and the platform's log on iOS. */
    val logSinks: List<LogSink>,
    /** This process's files, by area. One instance: every file-backed service is built over it. */
    val files: Files,
    /** Wall-clock time and the device's zone. One instance: the core and the screen's formatter read it. */
    val clock: Clock,
    /** The ambient entry-point seam the device-log lines and the crash channel's `entry_point` tag read. */
    val entryContext: EntryContext,
    /** Where this build reports to, or `null` for a build that reports nowhere — a constant of the build. */
    val dsn: String?,
    /**
     * The process's boot banner — what the process is and which build (capability `privacy-security`), so a reader
     * who concatenates the app's and the extension's logs can tell runs apart. Logged first, before anything else
     * in the process can log.
     */
    val bootLines: List<String>,
    /**
     * Whether this composition owns the process's global logger configuration. A root does, and [snapSyncProcess]
     * then installs the process's log writers. A JVM world does not: it is one of many "processes" composed in one
     * JVM, and Kermit's writer list is JVM-global.
     */
    val ownsGlobalLogger: Boolean,
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
    /** The process's one [Clock]. */
    val clock: Clock,
    /** The process's one entry-point seam. */
    val entryContext: EntryContext,
    /**
     * The process's Kermit writers: every line to the [ProcessPorts.logSinks], and — where the build reports —
     * into the crash channel. Installed by [snapSyncProcess] where the process owns the global logger; the control
     * channel re-installs them after pointing Kermit elsewhere for a while.
     */
    val logWriters: List<LogWriter>,
) {
    /** Where a bug report goes on this build (capability `privacy-security`): sent where it reports, else kept here. */
    val reportDestination: ReportDestination
        get() = if (crash.isConfigured) ReportDestination.DEVELOPER else ReportDestination.THIS_DEVICE
}

/**
 * Set up what is per-process — **called first by every root**, before it composes anything else, and exactly once.
 *
 * In order, each for a reason:
 * 1. The log writers, where this process owns the global logger — so nothing below logs into the void — and the
 *    boot banner, so it is the process's first line.
 * 2. Crash reporting starts, as the process's first act that can fail: it must be live before any other wiring can
 *    fail, and it reads only this build's own configuration, so it is safe on a locked background launch.
 * 3. The process metrics are listened to — only now, because listening is a commitment: a provider may hand a held
 *    report over exactly once, so the handler must already be live (it is: [ProcessAccount] over the started
 *    channel, whose log writer is already installed).
 */
fun snapSyncProcess(ports: ProcessPorts): ProcessServices {
    val crash = CrashReporting(ports.crashReporter, ports.dsn, ports.entryContext, ports.files)
    ports.crashReporter.listen(CrashHandlers(onEvent = crash::shapeEvent, onBreadcrumb = crash::shapeCrumb))
    val writers = listOfNotNull(SinkLogWriter(ports.logSinks, ports.entryContext), crash.logWriter)
    if (ports.ownsGlobalLogger) Logger.setLogWriters(writers)
    val boot = Logger.withTag("process")
    ports.bootLines.forEach { line -> boot.i { line } }
    crash.start()
    val account = ProcessAccount(crash)
    ports.processMetrics.listen(MetricHandlers(onReport = account::handle))
    return ProcessServices(crash, account, ports.files, ports.clock, ports.entryContext, writers)
}
