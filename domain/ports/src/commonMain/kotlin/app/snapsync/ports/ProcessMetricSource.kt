package app.snapsync.ports

import app.snapsync.model.ProcessMetricReport

/**
 * Where reports about **this process's own behaviour** come from (capability `crash-reporting`):
 * how its previous runs ended, how responsive it was, what it peaked at. Named for the need — any
 * platform that can account for its own processes can seat this.
 *
 * **Generic over providers on purpose, and that is a forcing property rather than a preference.**
 * The platform surface this is bound to today is deprecated by its vendor in favour of a successor
 * that is Swift-only, so unreachable from Kotlin/Native. This port is the seam across which that
 * replacement happens: when it does, only the adapter changes — the report vocabulary, the rule that
 * reads it, the thresholds and the three delivery channels are untouched. A second provider (a test
 * fake, a non-Apple platform) seats the same way.
 *
 * **Push, not pull.** Reports arrive when the platform decides, typically shortly after a launch and
 * describing periods that ended long before. There is deliberately no "read the current metrics"
 * operation: the underlying accessor for already-stored reports was measured to return nothing on a
 * fresh process, so a pull surface would promise what no provider can deliver.
 *
 * ⚠️ **Observing is a commitment, not a query.** Delivery may be **one-shot**: a provider may hold a
 * report indefinitely while nobody observes, and hand it over exactly once thereafter. So a caller
 * that registers [observe] and cannot handle what arrives is strictly worse off than one that never
 * observed — it consumes a report the platform was holding safely and drops it. Register only where
 * the handler is already live, and handle before returning.
 */
interface ProcessMetricSource {

    /**
     * Begin observing, delivering each report to [onReport].
     *
     * [onReport] is invoked on whatever thread the provider chooses, and is expected to complete its
     * work **before returning** — see the one-shot warning above. Implementations retain whatever the
     * platform requires for the process lifetime; there is no unregister, because nothing in this app
     * ever stops caring how its own process is ending.
     */
    fun observe(onReport: (ProcessMetricReport) -> Unit)
}
