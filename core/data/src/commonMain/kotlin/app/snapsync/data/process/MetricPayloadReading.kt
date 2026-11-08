package app.snapsync.data.process

import app.snapsync.domain.process.ProcessMetricReport
import app.snapsync.domain.process.ProcessMetricRules
import app.snapsync.format.ReportText
import app.snapsync.ports.MetricNode
import app.snapsync.ports.ProcessMetricPayload

/** A delivered report, read: its fields, and the text the device log keeps of them. */
class ReadMetrics(val report: ProcessMetricReport, val text: String)

/**
 * A delivered payload as the dotted keys a [ProcessMetricReport] carries: a branch's key joins its parent's with a dot,
 * a list's items are keyed by position — as histogram buckets already key themselves — and a leaf is its text, so the
 * vocabulary stays open. A branch named in [ProcessMetricRules.CALL_STACK_SEGMENTS] is skipped at any depth, the root
 * included, **without being read**: a call-stack tree is never converted, let alone carried (measured: twelve queued
 * payloads with call stacks serialized to 15.1 MB and blocked 18 s). A slot that holds nothing contributes no key.
 */
class MetricPayloadReading {
    fun read(payload: ProcessMetricPayload): ReadMetrics {
        val out = LinkedHashMap<String, String>()
        walk(payload.root, "", out)
        val report = ProcessMetricReport(out)
        return ReadMetrics(report, ReportText.metrics(report))
    }

    private fun walk(node: MetricNode?, prefix: String, out: MutableMap<String, String>): Unit = when (node) {
        null -> Unit
        is MetricNode.Leaf -> { out[prefix] = node.text }
        is MetricNode.Branch -> node.keys.filterNot { it in ProcessMetricRules.CALL_STACK_SEGMENTS }
            .forEach { key -> walk(node.child(key), join(prefix, key), out) }
        is MetricNode.Items -> repeat(node.size) { index -> walk(node.item(index), join(prefix, "$index"), out) }
    }

    private fun join(prefix: String, segment: String): String =
        listOf(prefix, segment).filter(String::isNotEmpty).joinToString(".")
}
