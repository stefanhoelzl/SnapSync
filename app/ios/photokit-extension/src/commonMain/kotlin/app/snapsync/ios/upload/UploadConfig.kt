package app.snapsync.ios.upload

/** The assembled inputs for the edge upload provider: the compile-time host, the joined event, and this device. */
class UploadConfig(
    val host: String,
    val eventId: String,
    val deviceId: String,
)

/**
 * Combine the three edge-URL inputs — the runtime [eventId] (Keychain payload), the compile-time
 * [host] (`BackgroundUploadURLBase`), and the App-Group [deviceId] — into an [UploadConfig]. Returns
 * `null` — meaning "skip this cycle, there is nothing to do" — when any input is absent: a `null`
 * `eventId` (not joined yet), a missing/blank host (a build misconfiguration), or an unavailable
 * device id. Pure and platform-free, so the assemble-or-skip decision is unit-tested off-device
 * while the iOS root stays trivial glue.
 */
fun buildUploadConfig(eventId: String?, host: String?, deviceId: String?): UploadConfig? {
    if (eventId.isNullOrEmpty() || host.isNullOrEmpty() || deviceId.isNullOrEmpty()) return null
    return UploadConfig(host = host, eventId = eventId, deviceId = deviceId)
}
