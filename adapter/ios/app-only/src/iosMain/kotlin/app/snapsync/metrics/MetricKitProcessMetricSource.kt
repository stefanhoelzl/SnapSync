package app.snapsync.metrics

import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import app.snapsync.model.ProcessMetricReport
import app.snapsync.model.flattenToDottedKeys
import app.snapsync.ports.ProcessMetricSource
import co.touchlab.kermit.Logger
import platform.MetricKit.MXDiagnosticPayload
import platform.MetricKit.MXMetricManager
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricPayload
import platform.darwin.NSObject

/**
 * The MetricKit binding of [ProcessMetricSource] (capability `crash-reporting`).
 *
 * Seated in `:adapter:ios:app-only` by linkage: MetricKit is app-process-only, and the
 * background-upload extension must not link it. That is not a tidiness point — a subscriber in the
 * extension could never work, because reports are handed out roughly daily while that process exists
 * only for the length of one invocation.
 *
 * **Transcription only.** Every decision — what a crossing is, what a line says, which branches are
 * dropped — lives in `:domain:model` and is tested there. This class converts the platform's own
 * serialization into a report and hands it over.
 *
 * ⚠️ **Registering commits us to handling.** Delivery is one-shot: MetricKit holds a report
 * indefinitely while nobody subscribes, and hands it over exactly once thereafter (measured — reports
 * survived a full day of a non-subscribing build and arrived when a subscriber returned). So
 * [observe] must only be called where the handler is already live; registering and then dropping what
 * arrives is strictly worse than never registering.
 *
 * ⏰ **Expiry**: this whole `MX*` surface is deprecated at iOS 27 in favour of a Swift-only successor
 * that Kotlin/Native cannot call. When that bites, this class is what gets replaced — behind
 * [ProcessMetricSource], with the rule, the thresholds and the channels untouched.
 */
class MetricKitProcessMetricSource(
    private val log: Logger = Logger.withTag("processMetrics"),
) : ProcessMetricSource {

    /**
     * Retained for the process lifetime.
     *
     * `addSubscriber` is not documented to keep a strong reference, and the sibling
     * `PhotoSelectionObserver` measured exactly that hazard with `PHPhotoLibrary`. A collected
     * subscriber would fail the way this capability least tolerates: silently, and only on the
     * devices that had something to report.
     */
    private var subscriber: Subscriber? = null

    override fun observe(onReport: (ProcessMetricReport) -> Unit) {
        val seat = Subscriber(log, onReport)
        subscriber = seat
        // The `shared` touch is itself load-bearing: MetricKit accumulates NOTHING for an app until
        // this is first called, and never retroactively. A launch that does not reach here is
        // attribution nobody gets back.
        MXMetricManager.sharedManager.addSubscriber(seat)
        log.i { "process metrics: observing" }
    }

    /**
     * The two OS callbacks, kept private so nothing but [observe] can seat them.
     *
     * Both payload families become the same kind of report, which is what the open vocabulary buys:
     * a daily aggregate and a per-incident diagnostic differ only in which keys they carry, so one
     * rule reads both and neither needs a type of its own.
     */
    private class Subscriber(
        private val log: Logger,
        private val onReport: (ProcessMetricReport) -> Unit,
    ) : NSObject(), MXMetricManagerSubscriberProtocol {

        @PlatformEntry
        override fun didReceiveMetricPayloads(payloads: List<*>) =
            log.invocation("didReceiveMetricPayloads", params = "count=${payloads.size}") {
                payloads.forEach { payload ->
                    (payload as? MXMetricPayload)?.let { deliver(it.dictionaryRepresentation()) }
                }
            }

        @PlatformEntry
        override fun didReceiveDiagnosticPayloads(payloads: List<*>) =
            log.invocation("didReceiveDiagnosticPayloads", params = "count=${payloads.size}") {
                payloads.forEach { payload ->
                    (payload as? MXDiagnosticPayload)?.let { deliver(it.dictionaryRepresentation()) }
                }
            }

        /**
         * Convert and hand over, **inline** — before the callback returns.
         *
         * Not hopped to another lane, deliberately and against the observer convention this module
         * otherwise follows. The work is small (call-stack branches are dropped by
         * [flattenToDottedKeys] before anything is rendered), and a process woken briefly in the
         * background may be killed before deferred work runs — which for a one-shot delivery means
         * losing the report permanently.
         */
        private fun deliver(raw: Map<Any?, *>) {
            val nested = raw.entries.associate { (key, value) -> key.toString() to value }
            onReport(ProcessMetricReport(flattenToDottedKeys(nested)))
        }
    }
}
