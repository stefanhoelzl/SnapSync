package app.snapsync.model

/** The backend's push kind for Apple's push service — what the push port's `kind` states on iOS. */
const val PUSH_KIND_APNS: String = "apns"

/** The backend's push kind for Firebase Cloud Messaging — what the push port's `kind` states on Android. */
const val PUSH_KIND_FCM: String = "fcm"
