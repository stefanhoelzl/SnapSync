package app.snapsync.model

/**
 * An APNs device token and the APNs environment it belongs to — the `pushToken` persisted in
 * `devices/<deviceId>.json` (capability `receiving-photos`). `env` is `"sandbox"` (dev/sideloaded builds)
 * or `"production"` (TestFlight/App Store).
 */
data class ApnsPushToken(val token: String, val env: String)

/**
 * The device token the platform's push service issued (or rotated), as lowercase hex — what the `PushNotifications`
 * port hands the core. Which APNs environment it belongs to is the build's, not the delivery's: the composition
 * pairs it into an [ApnsPushToken].
 */
data class PushToken(val hex: String)

/**
 * A silent push as it arrived, its [payload] kept **whole** (capability `receiving-photos`): the `model/` codec
 * ([pushEventId]) is the one place that knows the payload's shape, so an adapter forwards what the platform handed
 * it and reads nothing out of it.
 */
class PushMessage(val payload: Map<Any?, *>)
