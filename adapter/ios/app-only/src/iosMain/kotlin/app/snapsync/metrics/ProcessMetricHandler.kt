package app.snapsync.metrics

import app.snapsync.model.ProcessMetricReport
import app.snapsync.model.processMetricEmissions
import app.snapsync.ports.DiagnosticsReporter
import co.touchlab.kermit.Logger

/**
 * What happens to a delivered report (capability `crash-reporting`): the three channels, and nothing
 * else.
 *
 * ```
 *   report ──┬─► device log        one line per report, always, on every build
 *            ├─► reporting context the standing account, riding every later event AND any crash
 *            └─► reporting event   only when the rule says it crossed
 * ```
 *
 * **This is wiring, and it is wiring on purpose.** It holds no decision: the rule returns emissions
 * — severity, text, reasons — and this loops over them. There is no `if` here about what is worth
 * reporting, which is what lets every such judgement live in `:domain:model` where tests reach it
 * (spec `module-architecture`: the wiring graph is smoke-tested end to end, never unit-tested).
 *
 * The context is attached **before** the emissions are logged. A crossing is logged at `Error`, which
 * the reporting channel turns into an event through the logging seam — so the context has to be
 * standing by the time that happens, or the very event this exists to explain would arrive without
 * its explanation.
 */
class ProcessMetricHandler(
    private val reporter: DiagnosticsReporter,
    private val log: Logger = Logger.withTag("processMetrics"),
) {

    fun handle(report: ProcessMetricReport) {
        // No `start()` here on purpose. This path CAN outrun the composition that normally starts the
        // reporter — arming happens at process start so a cold background wake is covered — and the
        // first real attribution event went out with no `process` tag because of exactly that. The
        // guarantee lives in `describeProcess`'s contract instead, so no caller has to remember it,
        // and this stays what it claims to be: wiring with no decision in it.
        val emissions = processMetricEmissions(report)
        // Why it crossed, carried WITH the report rather than as transport tags. The vocabulary is
        // open precisely so a derived fact can ride alongside the measured ones, and it keeps the
        // crossing's message fixed — which is what makes every occurrence group into one issue.
        val reasons = emissions.flatMap { it.reasons }.distinct()
        reporter.describeProcess(
            if (reasons.isEmpty()) report else ProcessMetricReport(report.fields + (REASONS_KEY to reasons.joinToString(","))),
        )
        // No branch: each emission carries Kermit's own severity, so this renders without deciding.
        // An `Error` here is what `crash-reporting` carries onward as the event.
        emissions.forEach { emission -> log.log(emission.severity, log.tag, null, emission.message) }
    }

    private companion object {
        const val REASONS_KEY = "crossing.reasons"
    }
}
