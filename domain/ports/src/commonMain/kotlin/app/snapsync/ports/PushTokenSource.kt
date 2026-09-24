package app.snapsync.ports

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current-APNs-token source (capability `push-registration`). The token is **OS-push-delivered**,
 * not pulled: the iOS app-shell wiring calls [deliver] from the AppDelegate's
 * `didRegisterForRemoteNotificationsWithDeviceToken`; tests call [deliver] directly (it is its own
 * settable fake — one implementation suffices). [env] is the build's APNs environment, injected at
 * **compile time** (from `Config.xcconfig`'s `APNS_ENV`), never detected at runtime.
 *
 * The app asks the OS for the token at every app entry, so the OS answers with the SAME token many times per
 * process. Each answer is a [deliveries] emission — an unchanged token included — because it is the registration's
 * trigger to compare against what the backend last accepted: a publish that failed at one entry is re-sent at the
 * next one, with the same token. A rotation is simply a delivery whose token differs.
 */
class PushTokenSource(val env: String) {
    private val _token = MutableStateFlow<String?>(null)

    // Every answer, equal or not (a StateFlow would conflate an unchanged re-delivery away). One replayed, so a
    // registration installed after the OS answered still sees the answer.
    private val _deliveries = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The latest OS-delivered device token (hex), or `null` before the OS delivers one. */
    val token: StateFlow<String?> = _token.asStateFlow()

    /** Every OS delivery, in order — a re-delivery of an unchanged token included; the latest one replayed. */
    val deliveries: Flow<String> = _deliveries.asSharedFlow()

    /** Deliver an OS-provided device token (the answer to an app entry's request: unchanged, first, or rotated). */
    fun deliver(hexToken: String) {
        _token.value = hexToken
        _deliveries.tryEmit(hexToken)
    }
}
