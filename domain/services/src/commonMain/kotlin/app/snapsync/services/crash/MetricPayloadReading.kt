package app.snapsync.services.crash

import app.snapsync.model.CALL_STACK_SEGMENTS
import app.snapsync.model.ProcessMetricReport
import app.snapsync.ports.MetricNode
import app.snapsync.ports.ProcessMetricPayload

/**
 * A delivered payload as the dotted keys a [ProcessMetricReport] carries: a branch's key joins its parent's with a dot,
 * a list's items are keyed by position — as histogram buckets already key themselves — and a leaf is its text, so the
 * vocabulary stays open. A branch named in [CALL_STACK_SEGMENTS] is skipped at any depth, the root included, **without
 * being read**: a call-stack tree is never converted, let alone carried (measured: twelve queued payloads with call
 * stacks serialized to 15.1 MB and blocked 18 s). A slot that holds nothing contributes no key.
 */
fun ProcessMetricPayload.report(): ProcessMetricReport {
    val out = LinkedHashMap<String, String>()
    walk(root, "", out)
    return ProcessMetricReport(out)
}

private fun walk(node: MetricNode?, prefix: String, out: MutableMap<String, String>) {
    when (node) {
        null -> Unit
        is MetricNode.Leaf -> out[prefix] = node.text
        is MetricNode.Branch -> node.keys.filterNot { it in CALL_STACK_SEGMENTS }
            .forEach { key -> walk(node.child(key), join(prefix, key), out) }
        is MetricNode.Items -> repeat(
            node.size,
        ) { index -> walk(node.item(index), join(prefix, index.toString()), out) }
    }
}

private fun join(prefix: String, segment: String): String = if (prefix.isEmpty()) segment else "$prefix.$segment"
