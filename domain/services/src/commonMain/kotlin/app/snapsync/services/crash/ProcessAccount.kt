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
 *            └─► reporting event   only when the rule says it crossed
 * ```
 *
 * The rule itself — what is worth reporting — is `model/`'s [processMetricEmissions], which returns emissions
 * (severity, text, reasons); this loops over them.
 *
 * The context is attached **before** the emissions are logged. A crossing is logged at `Error`, which the channel
 * turns into an event through the logging seam — so the context has to be standing by then, or the very event this
 * exists to explain would arrive without its explanation.
 *
 * Runs INLINE on the thread the provider delivers on (`ProcessMetrics`: delivery is one-shot, and a process woken
 * briefly may be killed before deferred work runs). It does no I/O of its own beyond the log.
 */
class ProcessAccount(
    private val crash: CrashReporting,
    private val log: Logger = Logger.withTag("processMetrics"),
) {

    fun handle(report: ProcessMetricReport) {
        val emissions = processMetricEmissions(report)
        // Why it crossed, carried WITH the report rather than as transport tags. The vocabulary is open precisely so
        // a derived fact can ride alongside the measured ones, and it keeps the crossing's message fixed — which is
        // what makes every occurrence group into one issue.
        val reasons = emissions.flatMap { it.reasons }.distinct()
        crash.describeProcess(
            if (reasons.isEmpty()) report else ProcessMetricReport(report.fields + (REASONS_KEY to reasons.joinToString(","))),
        )
        // Each emission carries Kermit's own severity, so this renders without deciding. An `Error` here is what
        // the channel carries onward as the event.
        emissions.forEach { emission -> log.log(emission.severity, log.tag, null, emission.message) }
    }

    private companion object {
        const val REASONS_KEY = "crossing.reasons"
    }
}
