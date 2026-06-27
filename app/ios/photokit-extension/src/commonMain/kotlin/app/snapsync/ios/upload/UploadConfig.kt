package app.snapsync.ios.upload

/** The assembled inputs for the edge upload provider: the compile-time host and the joined event. */
class UploadConfig(
    val host: String,
    val eventId: String,
)

/**
 * Combine the two edge-URL inputs — the runtime [eventId] (Keychain payload) and the compile-time
 * [host] (`BackgroundUploadURLBase`) — into an [UploadConfig]. Returns `null` — meaning "skip this
 * cycle, there is nothing to do" — when either input is absent: a `null` `eventId` (not joined yet)
 * or a missing/blank host (a build misconfiguration). Pure and platform-free, so the
 * assemble-or-skip decision is unit-tested off-device while the iOS root stays trivial glue.
 */
fun buildUploadConfig(eventId: String?, host: String?): UploadConfig? {
    if (eventId.isNullOrEmpty() || host.isNullOrEmpty()) return null
    return UploadConfig(host = host, eventId = eventId)
}
