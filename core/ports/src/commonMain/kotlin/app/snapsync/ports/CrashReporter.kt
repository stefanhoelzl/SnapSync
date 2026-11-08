package app.snapsync.ports

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.Crumb
import app.snapsync.domain.report.DumpHandOff
import app.snapsync.domain.report.DumpResult

/**
 * The process's crash-reporting channel — ONE external system: the reporting SDK and the ingest behind it. Named for
 * the need: any platform that can report failures off-device seats it.
 *
 * **Thin.** It decides nothing. Whether this build reports at all, what a report may leave as, what a log line becomes
 * — all of that is the domain's (`Reporting`, [CrashShaping], `LineReport`), over the data layer's `CrashChannel`. The
 * adapter translates between those values and the SDK's.
 *
 * Contract (`CrashReporterContract`, live over the real SDK):
 * - Nothing runs until [start]; before it, [capture], [breadcrumb] and [setContext] change nothing and [sendDump]
 *   answers [DumpResult.NotSent].
 * - [start] is **idempotent process-wide**: the channel is one per process, and a second start that re-initialised it
 *   would lose its scope.
 * - Every event and breadcrumb leaves as the [CrashShaping] it was started with shapes it — applied in the SDK's own
 *   hooks, so the SDK's automatic breadcrumbs pass it too.
 * - [setContext] **replaces** the named context, and it rides every later event — including a crash captured in this
 *   process and delivered on a later launch.
 * - [sendDump] queues one event; returning does not mean it left the device, and no caller may claim it has.
 */
interface CrashReporter : Port {
    fun start(options: CrashOptions, shaping: CrashShaping)
    fun capture(event: CrashEvent)
    fun breadcrumb(crumb: Crumb)
    fun setContext(name: String, fields: Map<String, String>)
    suspend fun sendDump(dump: CrashEvent): DumpHandOff
}
