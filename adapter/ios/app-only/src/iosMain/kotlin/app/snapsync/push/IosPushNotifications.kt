@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.push

import app.snapsync.model.PlatformEntry
import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.ports.Completion
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import co.touchlab.kermit.Logger
import platform.UIKit.UIApplication
import platform.UIKit.registerForRemoteNotifications
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The iOS [PushNotifications] (capability `receiving-photos`): APNs, through `UIApplication` and the application
 * delegate's three callbacks, which the Swift shell forwards whole to the `deliver…` methods here.
 *
 * [register] asks for the token — Apple describes asking as cheap, and it is the only way the app learns a rotated
 * one; the answer arrives through `didRegisterForRemoteNotificationsWithDeviceToken` ([deliverToken]) or
 * `didFailToRegisterForRemoteNotificationsWithError` ([deliverTokenFailure]). A silent push arrives through
 * `didReceiveRemoteNotification` ([deliverMessage]) with its fetch handler, which crosses as a [Completion] released
 * once; a push has no expiry signal of its own — its only "time is up" is the process's background time, which the core
 * holds across it.
 */
class IosPushNotifications(private val log: Logger) : PushNotifications {
    private var handlers: PushHandlers? = null

    override fun listen(handlers: PushHandlers) {
        this.handlers = handlers
    }

    override fun register() {
        UIApplication.sharedApplication.registerForRemoteNotifications()
    }

    /** APNs issued this device [hex] — the delegate renders the token's bytes as lowercase hex. */
    @PlatformEntry
    fun deliverToken(hex: String) {
        registered()?.onToken(PushToken(hex))
    }

    /** APNs could not issue a token; [description] is the platform's error, as it said it. */
    @PlatformEntry
    fun deliverTokenFailure(description: String) {
        registered()?.onTokenFailure(PlatformError(description))
    }

    /**
     * A silent push arrived, its `userInfo` forwarded **whole**; [complete] is the fetch handler, called once — after
     * the push's own work, or at once when the process's background time is up, and always, including for a payload
     * no receiver can use.
     */
    @PlatformEntry
    fun deliverMessage(payload: Map<Any?, *>, complete: () -> Unit) {
        val registered = registered()
        if (registered == null) complete() else registered.onMessage(PushMessage(payload), PushCompletion(complete))
    }

    private fun registered(): PushHandlers? {
        val registered = handlers
        if (registered == null) log.e { "a push delivery arrived before the PushNotifications port was listened to" }
        return registered
    }
}

/**
 * A silent push's fetch handler as a [Completion]: the operating system's own block, released once — an adapter-private
 * bridge to the platform, never a callback the composition wired.
 */
private class PushCompletion(private val handler: () -> Unit) : Completion {
    private val released = AtomicBoolean(false)

    override fun complete() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) handler()
    }

    override fun onExpired(action: () -> Unit) = Unit
}
