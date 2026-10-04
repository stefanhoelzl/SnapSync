package app.snapsync.services.crash

import app.snapsync.model.ProcessMetricReport
import app.snapsync.model.processMetricEmissions
import co.touchlab.kermit.Logger

/**
 * What a delivered process-metric report does (capability `privacy-security`): the three channels, and nothing
 * else.
 *
 * ```
 *   report ──┬─► device log        one line per report, always, on every build
 *            ├─► reporting context the standing account, riding every later event AND any crash
 *            └─► reporting event   one per reason the rule says it crossed on
 * ```
 *
 * The rule itself — what is worth reporting — is `model/`'s [processMetricEmissions], which returns emissions
 * (severity, text, reason); this loops over them.
 *
 * The context is attached **before** the emissions are logged. A crossing is logged at `Error`, which the channel
 * turns into an event through the logging seam — so the context has to be standing by then, or the very event this
 * exists to explain would arrive without its explanation.
 *
 * The context also carries the [footprints] the app recorded before it was last suspended — the ended process's own
 * last readings, which no report states — for a reader telling a footprint that spiked from one that was always high.
 *
 * Runs INLINE on the thread the provider delivers on (`ProcessMetrics`: delivery is one-shot, and a process woken
 * briefly may be killed before deferred work runs). Its only I/O beyond the log is reading that one small file.
 */
class ProcessAccount(
    private val crash: CrashReporting,
    private val footprints: FootprintTrail,
    private val log: Logger = Logger.withTag("processMetrics"),
) {

    fun handle(report: ProcessMetricReport) {
        val emissions = processMetricEmissions(report)
        // Why it crossed, carried WITH the report rather than as transport tags. The vocabulary is open precisely so
        // a derived fact can ride alongside the measured ones. Each crossing's message names its own reason; the
        // context names them all, so every event of one report shows what else crossed that day.
        val reasons = emissions.mapNotNull { it.reason }
        val crossed = if (reasons.isEmpty()) emptyMap() else mapOf(REASONS_KEY to reasons.joinToString(","))
        crash.describeProcess(ProcessMetricReport(report.fields + crossed + footprints.fields()))
        // Each emission carries Kermit's own severity, so this renders without deciding. An `Error` here is what
        // the channel carries onward as the event.
        emissions.forEach { emission -> log.log(emission.severity, log.tag, null, emission.message) }
    }

    private companion object {
        const val REASONS_KEY = "crossing.reasons"
    }
}
