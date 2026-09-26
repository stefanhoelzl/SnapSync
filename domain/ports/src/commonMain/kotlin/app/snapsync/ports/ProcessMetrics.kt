package app.snapsync.ports

import app.snapsync.model.ProcessMetricReport

/** Where each process-metric report goes. Handled INLINE, before [onReport] returns — see [ProcessMetrics]. */
class MetricHandlers(val onReport: (ProcessMetricReport) -> Unit)

/**
 * Reports about **this process's own behaviour** (capability `privacy-security`): how its previous runs ended, how
 * responsive it was, what it peaked at. Named for the need — any platform that can account for its own processes
 * seats it.
 *
 * **Generic over providers on purpose, and that is a forcing property rather than a preference.** The platform
 * surface this is bound to today is deprecated by its vendor in favour of a successor that is Swift-only, so
 * unreachable from Kotlin/Native. This port is the seam across which that replacement happens: only the adapter
 * changes — the report vocabulary, the rule that reads it, the thresholds and the three delivery channels do not.
 *
 * **Push, not pull.** Reports arrive when the platform decides, typically shortly after a launch and describing
 * periods that ended long before. There is deliberately no "read the current metrics" operation: the platform's
 * accessor for already-stored reports was measured to return nothing on a fresh process.
 *
 * **Always present.** A process with no provider — the upload extension, which exists only for one invocation while
 * reports are handed out roughly daily, and every JVM composition — binds [None], which never delivers.
 *
 * ⚠️ **Listening is a commitment, not a query.** Delivery may be **one-shot**: a provider may hold a report
 * indefinitely while nobody listens, and hand it over exactly once thereafter. So the handlers must already be live
 * when [listen] is called, and [MetricHandlers.onReport] completes its work before returning — on whatever thread
 * the provider chose (the lane law's MetricKit exception, `docs/architecture.md`).
 */
interface ProcessMetrics : Listenable<MetricHandlers> {

    companion object {
        /** Delivers nothing, ever: the binding for a process with no provider. */
        val None: ProcessMetrics = object : ProcessMetrics {
            override fun listen(handlers: MetricHandlers) = Unit
        }
    }
}
