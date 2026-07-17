package app.snapsync.membership

import app.snapsync.ports.JoinedEventMarker

import app.snapsync.engine.JOINED_EVENT_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import platform.Foundation.NSUserDefaults

/**
 * The App-Group-backed [JoinedEventMarker]: the last reconciled `eventId` lives in the shared
 * `NSUserDefaults` suite (the same suite the discovery cursor uses) so it survives the extension's
 * per-cycle process death — the property that makes it, not ledger-emptiness, the join signal.
 *
 * It lives here, beside the [JoinedEventMarker] interface, rather than in either upload tier's module,
 * because **both** tiers reconcile (capability `event-rejoin-reconciliation`) and so both need it: the
 * extension on iOS ≥26.1 and the app process on iOS 18–26.0. Parking it in the extension module is what
 * kept it out of reach of the app-driven tier, which shipped with no reconciliation at all.
 *
 * The App-Group suite is deliberate: the marker is written by whichever process holds the
 * `LedgerWriter`, and both processes must agree on it across an OS-version-driven tier switch.
 */
class IosJoinedEventMarker(
    suiteName: String = LEDGER_APP_GROUP,
) : JoinedEventMarker {

    private val defaults = NSUserDefaults(suiteName = suiteName)

    override fun read(): String? = defaults.stringForKey(JOINED_EVENT_KEY)

    override fun set(eventId: String) = defaults.setObject(eventId, forKey = JOINED_EVENT_KEY)

    override fun clear() = defaults.removeObjectForKey(JOINED_EVENT_KEY)
}
