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
 * ⚠️ **The subscriber is held in a field that is READ — do not make it write-only again.**
 * MetricKit is not documented to keep a strong reference to a subscriber, and the sibling
 * `PhotoSelectionObserver` measured exactly that hazard with `PHPhotoLibrary`. An earlier shape here
 * nested a private `Subscriber` and held it in a field that was **assigned and never read**. It armed
 * cleanly — the `observing` line was written on every launch — and then **no callback ever fired**,
 * across four days and a dozen launches, where the probe that preceded it received seventeen payloads.
 * Whether that field was elided or merely unreliable was never settled; what is settled is that it is
 * the only structural difference between the shape that worked and the shape that did not.
 *
 * So [subscriber] is a `val` initialised at construction and read on every [observe], and the
 * subscriber reads its own callback field on every delivery. Nothing here is write-only.
 *
 * The subscriber cannot simply BE this class: Kotlin/Native refuses to mix Kotlin and Objective-C
 * supertypes, so a class conforming to `MXMetricManagerSubscriberProtocol` may not also implement the
 * Kotlin [ProcessMetricSource] interface. (That error surfaces only in the **native** compile —
 * `compileIosMainKotlinMetadata` accepts it, which is the law "a platform-capability claim is settled
 * by a compile" showing its teeth.)
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

    /** The ObjC subscriber this retains. A `val`, constructed here, read on every [observe]. */
    private val subscriber = MetricKitSubscriber(log)

    override fun observe(onReport: (ProcessMetricReport) -> Unit) {
        subscriber.onReport = onReport
        val manager = MXMetricManager.sharedManager
        // The `shared` touch is itself load-bearing: MetricKit accumulates NOTHING for an app until
        // this is first called, and never retroactively. A launch that does not reach here is
        // attribution nobody gets back.
        manager.addSubscriber(subscriber)
        // The two `past…` counts say something about the QUEUE rather than only about us, which is the
        // one thing four days of silence could not distinguish: an empty queue reads the same as a
        // subscriber that cannot be reached. Measured to be 0 on a fresh process even moments before a
        // delivery, so a non-zero reading here would be news.
        log.i {
            "process metrics: observing (pastPayloads=${manager.pastPayloads.size} " +
                "pastDiagnosticPayloads=${manager.pastDiagnosticPayloads.size})"
        }
    }
}

/**
 * The ObjC end of the subscription — an `NSObject` conforming to MetricKit's subscriber protocol, and
 * nothing else.
 *
 * Separate from [MetricKitProcessMetricSource] because Kotlin/Native refuses to mix Kotlin and ObjC
 * supertypes, so the class ObjC is handed cannot also be the class `:domain` sees. Internal rather
 * than private: a private nested class was the previous shape, and keeping this one visible to the
 * module is a small nudge against quietly nesting it again.
 */
internal class MetricKitSubscriber(
    private val log: Logger,
) : NSObject(), MXMetricManagerSubscriberProtocol {

    /** Set by [MetricKitProcessMetricSource.observe]; read on every delivery below. */
    var onReport: ((ProcessMetricReport) -> Unit)? = null

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
     * otherwise follows. The work is small (call-stack branches are dropped by [flattenToDottedKeys]
     * before anything is rendered), and a process woken briefly in the background may be killed before
     * deferred work runs — which for a one-shot delivery means losing the report permanently.
     */
    private fun deliver(raw: Map<Any?, *>) {
        val nested = raw.entries.associate { (key, value) -> key.toString() to value }
        onReport?.invoke(ProcessMetricReport(flattenToDottedKeys(nested)))
    }
}
