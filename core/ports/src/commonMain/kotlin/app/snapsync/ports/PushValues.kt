package app.snapsync.ports

/**
 * The device token the platform's push service issued (or rotated) — what the [PushNotifications] port hands the
 * core: lowercase hex on APNs, FCM's own opaque string on Android. Which service and environment it belongs to is the
 * build's, not the delivery's.
 */
data class PushToken(val value: String)

/**
 * A silent push as it arrived, its [payload] kept **whole**: the payload codec (`pushEventId`) is the one place that
 * knows its shape, so an adapter forwards what the platform handed it and reads nothing out of it.
 */
class PushMessage(val payload: Map<Any?, *>)
