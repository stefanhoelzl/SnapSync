package app.snapsync.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current-APNs-token source (capability `push-registration`). The token is **OS-push-delivered**,
 * not pulled: the iOS app-shell wiring calls [deliver] from the AppDelegate's
 * `didRegisterForRemoteNotificationsWithDeviceToken`; tests call [deliver] directly (it is its own
 * settable fake — one implementation suffices). [env] is the build's APNs environment, injected at
 * **compile time** (from `Config.xcconfig`'s `APNS_ENV`), never detected at runtime. Delivering a new
 * token models a rotation, which [PushRegistration.run] re-registers.
 */
class PushTokenSource(val env: String) {
    private val _token = MutableStateFlow<String?>(null)

    /** The latest OS-delivered device token (hex), or `null` before the OS delivers one. */
    val token: StateFlow<String?> = _token.asStateFlow()

    /** Deliver an OS-provided device token (initial acquisition or a rotation). */
    fun deliver(hexToken: String) {
        _token.value = hexToken
    }
}
