package app.snapsync.ports

import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken

/**
 * **The platform's push service** (capability `receiving-photos`): ask it for this device's token, and hear the
 * token, a failure to get one, and every silent push. On iOS the APNs registration through `UIApplication` and the
 * application delegate's three callbacks; on Android Firebase Cloud Messaging.
 *
 * One external system, deciding nothing: whether a delivered token is published is the push registration's
 * comparison against the last one the backend accepted, and what a push runs is the composition's handler.
 *
 * An event port: [listen] registers the composition's handlers once per adapter, as the graph is composed — a token
 * or a push arriving in a background wake must find them.
 */
interface PushNotifications : Listenable<PushHandlers> {
    /**
     * Ask the platform for this device's token. Cheap and idempotent: asking is the only way to learn a rotated
     * token, so the composition asks at every launch and at every foreground entry, and the answer arrives through
     * [PushHandlers.onToken] or [PushHandlers.onTokenFailure].
     */
    fun register()
}

/** What the push service tells the core. Built only by a composition (law "Commands cross one door"). */
class PushHandlers(
    /** The platform issued (or re-delivered, or rotated) this device's token. */
    val onToken: (PushToken) -> Unit,
    /** The platform could not issue a token — no silent push will arrive until it can. */
    val onTokenFailure: (PlatformError?) -> Unit,
    /**
     * A silent push arrived; [completion] is released once the push's own work is done, or at once when the
     * process's background time is up — always, including for a payload no receiver can use.
     */
    val onMessage: (PushMessage, Completion) -> Unit,
)
