package app.snapsync.metrics

import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.MetricKit.MXDiagnosticPayload
import platform.MetricKit.MXMetricManager
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricPayload
import platform.darwin.NSObject

/**
 * ⚠️ **PROBE — temporary measurement code, not a design.** Delete it with the change that replaces
 * it. It exists to answer questions no amount of reading settles, before any port, model type or
 * feature is committed to (capability `crash-reporting`, exit attribution):
 *
 *  1. **Does a development-signed build receive MetricKit payloads at all?** Every dev/sideload
 *     build here is dev-signed with no baked DSN, and `MXMetaData.isTestFlightApp` proves MetricKit
 *     knows about distribution channel — so "it only delivers to TestFlight/App Store builds" is a
 *     live possibility that would move all verification onto a merge-to-`main` loop.
 *  2. **Does `addSubscriber` work from Kotlin, or is it refused?** The `setUploadJobExtensionEnabled`
 *     lesson (`PHPhotosErrorAccessUserDenied` 3311, measured) is that a registration which plausibly
 *     succeeds may not.
 *  3. **Does `NSObject() + MXMetricManagerSubscriberProtocol` compile *and link* on device?** The law
 *     is that a platform-capability claim is settled by a compile, not by a symbol table.
 *  4. **What is actually in a payload?** Every formatting and severity decision downstream is a
 *     function of content nobody here has seen.
 *  5. **Does a crash diagnostic arrive, and how fast?** Apple says diagnostics arrive "immediately in
 *     iOS 15 and later", which cannot mean immediately for a process that has been killed.
 *
 * It reads nothing, decides nothing, and persists nothing: both callbacks dump the payload's **own**
 * `JSONRepresentation()` verbatim into `debug.log` (capability `diagnostic-logging` — the un-redacted
 * channel that exists on every build, DSN or not). Dumping the platform's own serialization rather
 * than named properties is deliberate even here: a counter Apple adds shows up without this file
 * knowing it exists.
 *
 * Seated in `:adapter:ios:app-only` because MetricKit is app-process-only and the extension must not
 * link it — the same placement-by-linkage rule that puts `PhotoLibraryPermission` here.
 */
class MetricKitProbe(
    private val log: Logger = Logger.withTag("metricKitProbe"),
) : NSObject(), MXMetricManagerSubscriberProtocol {

    /**
     * Arm MetricKit and report what it already holds.
     *
     * The `shared` touch is the load-bearing half: *"MetricKit starts accumulating reports for your
     * app after calling `shared` for the first time."* Nothing in this app has ever called it, so
     * accumulation starts here and the first daily report is ~24 h away. The two `past…` counts are
     * logged to settle what those accessors actually return on a fresh process — the current
     * documentation says "since the last allocation of the shared manager instance", not the 7-day
     * archive they are widely believed to be.
     */
    fun register() {
        val manager = MXMetricManager.sharedManager
        manager.addSubscriber(this)
        log.i {
            "[metrickit] armed; pastPayloads=${manager.pastPayloads.size} " +
                "pastDiagnosticPayloads=${manager.pastDiagnosticPayloads.size}"
        }
    }

    @PlatformEntry
    override fun didReceiveMetricPayloads(payloads: List<*>) =
        log.invocation("didReceiveMetricPayloads", params = "count=${payloads.size}") {
            payloads.forEachIndexed { index, payload ->
                log.i { "[metrickit] metric[$index] ${json((payload as? MXMetricPayload)?.JSONRepresentation())}" }
            }
        }

    @PlatformEntry
    override fun didReceiveDiagnosticPayloads(payloads: List<*>) =
        log.invocation("didReceiveDiagnosticPayloads", params = "count=${payloads.size}") {
            payloads.forEachIndexed { index, payload ->
                log.i {
                    "[metrickit] diagnostic[$index] " +
                        json((payload as? MXDiagnosticPayload)?.JSONRepresentation())
                }
            }
        }

    /** The payload's own JSON, or a marker naming which of the two absences happened. */
    @OptIn(BetaInteropApi::class)
    private fun json(data: NSData?): String =
        when {
            data == null -> "<not the expected payload type>"
            else -> NSString.create(data, NSUTF8StringEncoding)?.toString() ?: "<undecodable utf8>"
        }
}
