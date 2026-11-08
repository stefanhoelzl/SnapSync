package app.snapsync.ports

/** The backend's push kinds — what [PushNotifications.kind] states: the service an adapter's tokens reach. */
enum class PushKind(val value: String) {
    /** Apple's push service — the iOS adapter's kind. */
    APNS("apns"),

    /** Firebase Cloud Messaging — the Android adapter's kind. */
    FCM("fcm"),
}
