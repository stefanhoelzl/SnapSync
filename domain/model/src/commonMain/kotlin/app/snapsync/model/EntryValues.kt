package app.snapsync.model

/**
 * A link the platform delivered (capability `join-event`), as the `Links` port hands it over — raw and undecided:
 * the pure [forwardEventLink] filter decides whether it is an event link to open.
 *
 * [hook] names the platform path that delivered it (a cold Universal Link, a warm continuation, an opened URL), for
 * the log line only. [isWebLink] is the platform's own answer to "was this delivery a web link?" — the one fact a
 * platform constant decides, answered by the adapter so no platform constant reaches the core. [url] is the whole
 * link, fragment included, or `null` when the delivery carried none.
 */
data class LinkDelivery(
    val hook: String,
    val isWebLink: Boolean,
    val activityType: String?,
    val url: String?,
)

/** What the platform said went wrong, as it said it — a description to log, never a decision input. */
data class PlatformError(val description: String)
