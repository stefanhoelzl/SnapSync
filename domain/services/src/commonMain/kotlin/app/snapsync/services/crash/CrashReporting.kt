package app.snapsync.services.crash

import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.DumpResult
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.PLATFORM_TAG
import app.snapsync.model.PROCESS_METRIC_CONTEXT
import app.snapsync.model.PROCESS_TAG
import app.snapsync.model.ProcessMetricReport
import app.snapsync.model.SAVED_DIAGNOSTIC_REPORT_PATH
import app.snapsync.model.crashDist
import app.snapsync.model.diagnosticDumpEvent
import app.snapsync.model.loggedCrash
import app.snapsync.model.savedDiagnosticReport
import app.snapsync.model.scrubbedCrumb
import app.snapsync.model.scrubbedEvent
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.EntryContext
import app.snapsync.ports.Files
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * The process's crash reporting (capability `privacy-security`): every decision about the channel, over the thin
 * [CrashReporter] port. ONE per process, built by `snapSyncProcess` — which is what makes the idempotence below a
 * plain flag rather than a process-wide promise four independently constructed adapters had to keep.
 *
 * - **Whether this build reports at all** is the build's DSN: `null` on every dev, sideload, emulator and simulator
 *   build, and then nothing starts, nothing is sent, and no connection is ever opened to the reporting host.
 * - **What the build is** — release, environment, build number, platform and process — is read from [build] and
 *   handed to the adapter as [CrashOptions], so the adapter reads nothing of the platform itself.
 * - **What leaves** is the pure rules in `model/Crash.kt`: [shapeEvent] and [shapeCrumb] are what the composition
 *   registers as the port's handlers.
 * - **What a log line becomes** is [loggedCrash], through [logWriter] — which the ROOT installs, because Kermit's
 *   writer list is a process global and only a root owns the process (a JVM world composes many in one).
 */
class CrashReporting(
    private val reporter: CrashReporter,
    /** What the running build is: where it reports to (`null` for nowhere) and the facts every report carries. */
    private val build: BuildInfo,
    /** The ambient entry point a log line belongs to, which rides an event as its `entry_point` tag. */
    private val entry: EntryContext,
    /** The process's files: where a build that reports nowhere keeps the latest report. */
    private val files: Files,
) {

    private val dsn: String? = build.dsn

    /** Whether this build carries a reporting destination. Constant for the process. */
    val isConfigured: Boolean get() = dsn != null

    private var started = false

    /**
     * The logging seam into the channel, or `null` for a build that reports nowhere — so such a build never constructs
     * one. Every line becomes a breadcrumb, and an `Error`/`Assert` line an event too.
     */
    val logWriter: LogWriter? = if (dsn != null) CrashLogWriter(reporter, entry) else null

    /**
     * Start the channel — the process's first act. Idempotent, and a complete no-op without a destination. Reads
     * nothing but the build's own configuration, so it is safe on a locked background launch.
     */
    fun start() {
        if (started) return
        val dsn = dsn ?: return
        started = true
        reporter.start(options(dsn))
    }

    private fun options(dsn: String): CrashOptions = CrashOptions(
        dsn = dsn,
        release = build.appVersion.takeIf { it.isNotBlank() },
        environment = build.diagnostics.reporterEnvironment,
        dist = crashDist(build.platform, build.diagnostics.buildNumber),
        tags = buildMap {
            put(PLATFORM_TAG, build.platform.tag)
            build.processId?.takeIf { it.isNotBlank() }?.let { put(PROCESS_TAG, it) }
        },
    )

    /** What an event may leave as — the port's `onEvent` handler. */
    fun shapeEvent(event: CrashEvent): CrashEvent? = scrubbedEvent(event)

    /** What a breadcrumb may leave as — the port's `onBreadcrumb` handler. */
    fun shapeCrumb(crumb: Crumb): Crumb? = scrubbedCrumb(crumb)

    /**
     * Attach [report] as the standing account of how this process has been behaving, so that **every** event this
     * process reports afterwards carries it — including a crash captured here and delivered on a later launch.
     * Replaces rather than accumulates.
     *
     * It starts the channel first, rather than trusting the caller to have: this is reachable from a path that can
     * outrun whatever normally starts it, and the first real attribution event once went out with **no `process`
     * tag** because it reached the channel before start had run (`SNAPSYNC-38`).
     */
    fun describeProcess(report: ProcessMetricReport) {
        start()
        if (!isConfigured) return
        reporter.setContext(PROCESS_METRIC_CONTEXT, report.fields)
    }

    /**
     * Transmit one operator-initiated diagnostic dump — verbatim, identifiers included ([diagnosticDumpEvent]
     * declares it exempt from the scrub). Delivery is the channel's business: [DumpResult.Queued] does not mean the
     * dump has left the device.
     *
     * A build that reports nowhere keeps it instead (capability `privacy-security`): the same sections, written to
     * [SAVED_DIAGNOSTIC_REPORT_PATH] in the app's own files, replacing the report saved before it. It never leaves
     * the phone; a refused write answers [DumpResult.NotSent] with the reason.
     */
    suspend fun sendDump(dump: DiagnosticDump): DumpResult {
        if (!isConfigured) {
            val bytes = savedDiagnosticReport(dump).encodeToByteArray()
            return when (val written = files.write(FileArea.PRIVATE, SAVED_DIAGNOSTIC_REPORT_PATH, bytes)) {
                is FileResult.Ok -> DumpResult.Saved(SAVED_DIAGNOSTIC_REPORT_PATH)
                else -> DumpResult.NotSent("the report could not be saved: $written")
            }
        }
        start()
        return reporter.sendDump(diagnosticDumpEvent(dump))
    }
}

/** The logging seam onto the channel: [loggedCrash] decides what each line becomes. */
internal class CrashLogWriter(private val reporter: CrashReporter, private val entry: EntryContext) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val logged = loggedCrash(severity, message, tag, throwable, entry.current())
        reporter.breadcrumb(logged.crumb)
        logged.event?.let(reporter::capture)
    }
}
