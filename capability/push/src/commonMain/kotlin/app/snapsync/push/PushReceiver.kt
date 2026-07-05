package app.snapsync.push

import co.touchlab.kermit.Logger

/**
 * The seam invoked when the app receives a silent (`content-available`) push (capability
 * `push-registration`), carrying the pushed **`eventId`** (the push payload's top-level `eventId` key,
 * capability `apns-push-sender`). The iOS app-shell wiring forwards the OS's remote-notification
 * callback here. It is **suspending** so the app-shell can **await** the receiver's synchronous work
 * (the union read + download enqueue) before signalling the OS background-fetch completion handler —
 * keeping the app alive through the push's execution window. A later change substitutes a real handler
 * (e.g. guarded download discovery) without touching the app-shell receive wiring.
 */
fun interface PushReceiver {
    suspend fun onSilentPush(eventId: String)
}

/**
 * A receiver that only **logs** receipt (via Kermit) — useful to observe the delivery pipe end-to-end
 * on device (a `POST /events/<id>/notify` produces a log line visible in `idevicesyslog`) without
 * triggering any use-case behavior. The real wiring substitutes a guarded download-discovery receiver
 * (capability `photo-download`); this remains handy for diagnostics.
 */
class LoggingPushReceiver(private val log: Logger = Logger.withTag("PushReceiver")) : PushReceiver {
    override suspend fun onSilentPush(eventId: String) {
        log.i { "silent push received for event $eventId" }
    }
}
