package app.snapsync.ports

import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DumpResult

/**
 * The last word on what leaves the process, asked of the reporting channel as each event and breadcrumb is about to
 * be sent. A handler returns what may leave, or `null` for "drop it". Pure: it may run on any thread the channel
 * chooses, and must neither block nor log (a log line from here would re-enter the channel).
 */
class CrashHandlers(
    val onEvent: (CrashEvent) -> CrashEvent?,
    val onBreadcrumb: (Crumb) -> Crumb?,
)

/**
 * The process's crash-reporting channel (capability `privacy-security`) — ONE external system: the reporting SDK
 * and the ingest behind it. Named for the need: any platform that can report failures off-device seats it.
 *
 * **Thin.** It decides nothing. Whether this build reports at all, what is redacted and capped, which event is
 * exempt, what a log line becomes, when the channel starts — all of that is `:domain:services`' `CrashReporting`
 * over the pure rules in `model/Crash.kt`. The adapter translates between those values and the SDK's.
 *
 * Contract (`CrashReporterContract`, live over the real SDK):
 * - Nothing runs until [start]; before it, [capture], [breadcrumb] and [setContext] change nothing and [sendDump]
 *   answers [DumpResult.NotSent].
 * - [start] is **idempotent process-wide**: the channel is one per process, and a second start that re-initialised
 *   it would lose its scope.
 * - Every event and breadcrumb passes the [CrashHandlers] registered by [listen] on its way out — what the handler
 *   returns is what leaves, and `null` leaves nothing.
 * - [setContext] **replaces** the named context, and it rides every later event — including a crash captured in
 *   this process and delivered on a later launch.
 * - [sendDump] queues one event; returning does not mean it left the device, and no caller may claim it has.
 */
interface CrashReporter : Listenable<CrashHandlers> {
    fun start(options: CrashOptions)
    fun capture(event: CrashEvent)
    fun breadcrumb(crumb: Crumb)
    fun setContext(name: String, fields: Map<String, String>)
    suspend fun sendDump(dump: CrashEvent): DumpResult
}
