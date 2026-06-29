package app.snapsync.ios.upload

import app.snapsync.engine.JOINED_EVENT_KEY
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.rejoin.JoinedEventMarker
import platform.Foundation.NSUserDefaults

/**
 * The App-Group-backed [JoinedEventMarker]: the last reconciled `eventId` lives in the shared
 * `NSUserDefaults` suite (the same suite the discovery cursor uses) so it survives the extension's
 * per-cycle process death — the property that makes it, not ledger-emptiness, the join signal.
 */
class IosJoinedEventMarker(
    suiteName: String = LEDGER_APP_GROUP,
) : JoinedEventMarker {

    private val defaults = NSUserDefaults(suiteName = suiteName)

    override fun read(): String? = defaults.stringForKey(JOINED_EVENT_KEY)

    override fun set(eventId: String) = defaults.setObject(eventId, forKey = JOINED_EVENT_KEY)

    override fun clear() = defaults.removeObjectForKey(JOINED_EVENT_KEY)
}
