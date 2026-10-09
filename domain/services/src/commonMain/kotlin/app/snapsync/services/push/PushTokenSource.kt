package app.snapsync.services.push

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current-push-token source. The token is **OS-push-delivered**,
 * not pulled: the composition calls [deliver] from the `PushNotifications` port's `onToken` (on iOS the
 * AppDelegate's `didRegisterForRemoteNotificationsWithDeviceToken`, on Android FCM's `onNewToken`); tests call
 * [deliver] directly (it is its own settable fake — one implementation suffices). [kind] is the push service the
 * adapter speaks (`PushNotifications.kind`); [env] is the build's push environment, injected at **compile time**
 * (iOS: `Config.xcconfig`'s `APNS_ENV`; Android: the resolved deployment's Firebase project), never detected at
 * runtime.
 *
 * The app asks the OS for the token at every app entry, so the OS answers with the SAME token many times per
 * process. Each answer is a [deliveries] emission — an unchanged token included — because it is the registration's
 * trigger to compare against what the backend last accepted: a publish that failed at one entry is re-sent at the
 * next one, with the same token. A rotation is simply a delivery whose token differs.
 */
class PushTokenSource(val kind: String, val env: String) {
    private val _token = MutableStateFlow<String?>(null)

    // Every answer, equal or not (a StateFlow would conflate an unchanged re-delivery away). One replayed, so a
    // registration installed after the OS answered still sees the answer.
    private val _deliveries = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The latest OS-delivered device token, or `null` before the OS delivers one. */
    val token: StateFlow<String?> = _token.asStateFlow()

    /** Every OS delivery, in order — a re-delivery of an unchanged token included; the latest one replayed. */
    val deliveries: Flow<String> = _deliveries.asSharedFlow()

    /** Deliver an OS-provided device token (the answer to an app entry's request: unchanged, first, or rotated). */
    fun deliver(token: String) {
        _token.value = token
        _deliveries.tryEmit(token)
    }
}
