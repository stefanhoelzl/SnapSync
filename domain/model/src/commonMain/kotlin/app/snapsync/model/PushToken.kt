package app.snapsync.model

/**
 * An APNs device token and the APNs environment it belongs to — the `pushToken` persisted in
 * `devices/<deviceId>.json` (capability `push-registration`). `env` is `"sandbox"` (dev/sideloaded builds)
 * or `"production"` (TestFlight/App Store).
 */
data class ApnsPushToken(val token: String, val env: String)
