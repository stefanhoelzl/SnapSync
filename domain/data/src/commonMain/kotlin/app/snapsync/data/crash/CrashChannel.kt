package app.snapsync.data.crash

import app.snapsync.domain.crash.CrashSections
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.LineReport
import app.snapsync.domain.crash.ReportedIds
import app.snapsync.domain.crash.Reporting
import app.snapsync.domain.crash.ReportingBuild
import app.snapsync.domain.process.CrossingReason
import app.snapsync.domain.process.ProcessMetricReport
import app.snapsync.domain.process.ProcessMetricRules
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpHandOff
import app.snapsync.domain.report.ReportDestination
import app.snapsync.inbound.LogWriter
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.ThreadLabel

/**
 * The process's crash-reporting channel, over the thin [CrashReporter] port — ONE per process, built by its process
 * composition. It translates; what it decides is the domain's: whether and how this build reports ([Reporting]), what a
 * report may leave as ([CrashShaping]), what a line becomes ([LineReport]) and how a crossing is filed
 * ([ProcessMetricRules]).
 */
class CrashChannel(
    private val reporter: CrashReporter,
    build: BuildInfo,
    /** The trigger the calling thread is labelled with, which rides a crossing as its `entry_point` tag. */
    private val label: ThreadLabel,
    private val shaping: CrashShaping,
) {
    private val reporting = ReportingBuild(
        build.dsn,
        build.appVersion,
        build.reporterEnvironment,
        build.platform,
        build.buildNumber,
        build.processId,
    ).reporting()

    /** Where a bug report goes on this build: sent where it reports, else kept on the device. */
    val destination: ReportDestination get() = reporting.destination

    /**
     * The log's way into the channel, or `null` for a build that reports nowhere — so such a build never constructs
     * one. Every line becomes a breadcrumb, and an `Error` line an event too ([LineReport]).
     */
    val lines: LogWriter? = when (reporting) {
        is Reporting.To -> CrashLines(this, label)
        Reporting.Nowhere -> null
    }

    /**
     * Start the channel — the process's first act. A complete no-op on a build that reports nowhere, and idempotent
     * where it reports (the port's promise). It reads nothing but the build's own configuration, so it is safe on a
     * locked background launch.
     */
    fun start() = when (reporting) {
        is Reporting.To -> reporter.start(reporting.options, shaping)
        Reporting.Nowhere -> Unit
    }

    /** One line, as the domain decided it leaves: always a breadcrumb, and its event where it has one. */
    fun report(line: LineReport) {
        reporter.breadcrumb(line.crumb)
        line.event?.let(reporter::capture)
    }

    /** The ids SnapSync knows, standing on every report this process sends afterwards ([CrashSections.SNAPSYNC]). */
    fun describe(ids: ReportedIds) {
        start()
        reporter.setContext(CrashSections.SNAPSYNC, ids.section())
    }

    /**
     * The standing account of how this process has been behaving, so that **every** event it reports afterwards carries
     * it — including a crash captured here and delivered on a later launch. Replaces rather than accumulates.
     *
     * It starts the channel first, rather than trusting the caller to have: this is reachable from a path that can
     * outrun whatever normally starts it, and the first real attribution event once went out with **no `process` tag**
     * because it reached the channel before start had run (`SNAPSYNC-38`).
     */
    fun describeProcess(fields: Map<String, String>) {
        start()
        reporter.setContext(CrashSections.PROCESS_METRICS, fields)
    }

    /** The event for one crossing [reason] of [report], filed under the build the report describes. Starts first. */
    fun reportCrossing(reason: CrossingReason, report: ProcessMetricReport) {
        start()
        reporter.capture(ProcessMetricRules.crossingEvent(reason, report, label.current()))
    }

    /**
     * Hand one bug report to the channel. Delivery is the channel's business: a [DumpHandOff] that says queued does not
     * mean the report has left the device.
     */
    suspend fun send(dump: DiagnosticDump): DumpHandOff {
        start()
        return reporter.sendDump(dump.event())
    }
}
