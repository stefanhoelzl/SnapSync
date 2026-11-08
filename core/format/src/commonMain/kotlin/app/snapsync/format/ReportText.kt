package app.snapsync.format

import app.snapsync.domain.process.ProcessMetricReport
import app.snapsync.domain.report.DiagnosticDump
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a report is written as where it is kept as text: a kept bug report, and a process-metric report in the log. */
object ReportText {
    /**
     * The bug report a build that reports nowhere keeps on the device: the same sections a sent one carries, as one
     * JSON object, identifiers intact — so a saved report reads like a received one.
     */
    fun saved(dump: DiagnosticDump): String = JsonObject(
        dump.sections().mapValues { (_, fields) ->
            JsonObject(
                fields.mapValues { (_, value) -> JsonPrimitive(value) },
            )
        },
    ).toString()

    /**
     * A process-metric report's **every field** as ONE JSON object, keys sorted, so two reports read comparably — and a
     * report that does NOT cross, which reaches no other channel, still exists in full somewhere: the device log. Not
     * `key=value` pairs: platform values contain spaces (`118417 kB`), so a space-joined line did not split back into
     * fields — measured by parsing one and getting it wrong. JSON is unambiguous, and `jq` reads it straight out of a
     * pulled log.
     */
    fun metrics(report: ProcessMetricReport): String =
        JsonObject(report.fields.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) }).toString()
}
