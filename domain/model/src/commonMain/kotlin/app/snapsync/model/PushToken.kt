package app.snapsync.model

/**
 * Where the backend sends this device's silent pushes — the device's registered push token, as the backend stores it.
 * [kind] names the push service ([PUSH_KIND_APNS], [PUSH_KIND_FCM]), stated by the `PushNotifications` adapter; [env]
 * is the build's, never the delivery's: APNs' `"sandbox"` (dev/sideloaded builds) or `"production"` (TestFlight/App
 * Store), and on FCM the Firebase project the token belongs to.
 */
data class PushEndpoint(val kind: String, val token: String, val env: String)

/** The backend's push kind for Apple's push service. */
const val PUSH_KIND_APNS: String = "apns"

/** The backend's push kind for Firebase Cloud Messaging. */
const val PUSH_KIND_FCM: String = "fcm"

/**
 * The device token the platform's push service issued (or rotated) — what the `PushNotifications` port hands the
 * core: lowercase hex on APNs, FCM's own opaque string on Android. Which service and environment it belongs to is
 * the build's, not the delivery's: the composition pairs it into a [PushEndpoint].
 */
data class PushToken(val value: String)

/**
 * A silent push as it arrived, its [payload] kept **whole**: the `model/` codec ([pushEventId]) is the one place that
 * knows the payload's shape, so an adapter forwards what the platform handed it and reads nothing out of it.
 */
class PushMessage(val payload: Map<Any?, *>)
