package app.snapsync.data.push

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.push.PushRegistrationKey
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.PushToken
import kotlin.concurrent.Volatile

/**
 * The push token the platform last delivered in this process, and the registration it would make. The token is
 * **delivered**, never pulled: the app asks at every entry and the OS answers every time — mostly with the token it
 * gave last time — and each answer is stored here. [kind] is the push service the adapter speaks, [env] the build's
 * push environment (compile-time: APNs' `sandbox` or `production`, on FCM the Firebase project), never the delivery's.
 */
class PushTokens(
    private val push: PushNotifications,
    build: BuildInfo,
    private val identity: DeviceIdentity,
) {
    private val kind: String = push.kind
    private val env: String = build.pushEnvironment

    @Volatile
    private var latest: Delivered = Delivered.None

    /**
     * Ask the platform for the token — cheap and idempotent, and the only way to learn a rotated one, so the app asks
     * at every launch and at every foreground entry. The answer arrives as a delivery on the push port.
     */
    fun request() = push.register()

    /** The token the OS delivered — unchanged, first, or rotated. */
    fun store(token: PushToken) {
        latest = Delivered.Token(token)
    }

    /**
     * The registration this device would make now. The device id is read per call: it is a Keychain read, unreadable
     * before the first unlock since a restart — and part of the registration, since a changed id is one the backend
     * has never seen.
     */
    fun current(): RegistrationRead = when (val delivered = latest) {
        is Delivered.Token -> identified(PushEndpoint(kind, delivered.token.value, env))
        Delivered.None -> RegistrationRead.NoToken
    }

    private fun identified(endpoint: PushEndpoint): RegistrationRead = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> RegistrationRead.Held(endpoint, endpoint.keyFor(id.value))
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> RegistrationRead.NoIdentity
    }

    /** What the OS has delivered in this process. */
    private sealed interface Delivered {
        data object None : Delivered

        data class Token(val token: PushToken) : Delivered
    }
}

/** The registration this device would make now: what to publish and what it records as, or why there is none. */
sealed interface RegistrationRead {
    data class Held(val endpoint: PushEndpoint, val key: PushRegistrationKey) : RegistrationRead

    /** The OS has delivered no token yet in this process. */
    data object NoToken : RegistrationRead

    /** The device id cannot be read now — before the first unlock — so nothing can address this device. */
    data object NoIdentity : RegistrationRead
}

/**
 * Where the backend sends this device's silent pushes, as the backend stores it: [kind] names the push service
 * (`PUSH_KIND_APNS`, `PUSH_KIND_FCM`), [token] the device token, [env] the build's environment.
 */
data class PushEndpoint(val kind: String, val token: String, val env: String) {
    /**
     * The one string a registration is recorded as: the four facts the backend's registration depends on — runtime
     * identity, since a device's record was written in it. Newline-separated because none of them can contain one. The
     * kind leads, so an install whose push service changed never reads as registered; a record from before the kind
     * was part of it (three lines) matches nothing and publishes once — an idempotent `PUT`.
     */
    fun keyFor(device: DeviceId): PushRegistrationKey =
        PushRegistrationKey(listOf(kind, env, device.value, token).joinToString("\n"))
}
