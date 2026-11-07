package app.snapsync.domain.crash

/**
 * What a report may leave as — handed to `CrashReporter.start` and applied by the adapter in the SDK's own send and
 * breadcrumb hooks, so it shapes the SDK's automatic breadcrumbs too. Pure: it may run on any thread the channel
 * chooses, and must neither block nor log (a line from here would re-enter the channel).
 *
 * It shapes no identifier and no key away: ids ride every report in full, and an event's key never reaches text a
 * report carries — a key is a secret type (`EventKey`, `InviteText`), rendered masked wherever it is printed.
 */
class CrashShaping private constructor(private val eventTextBytes: Int, private val crumbTextBytes: Int) {

    /**
     * An event as it may leave: filed under the build it describes ([CrashTags.DESCRIBED_RELEASE]), grouped under what
     * it was captured with ([CrashTags.FINGERPRINT]), its message capped at [EventBounds.EVENT_TEXT_BYTES] — one
     * runaway string must not make an event the ingest refuses, which would block the queue behind it — and every
     * breadcrumb it carries shaped as [crumb] shapes one.
     */
    fun event(event: CrashEvent): CrashEvent = filed(event).copy(
        message = event.message?.let(::capped),
        formatted = event.formatted?.let(::capped),
        breadcrumbs = event.breadcrumbs.map(::crumb),
        tags = event.tags - CrashTags.DESCRIBED_RELEASE - CrashTags.DESCRIBED_BUILD - CrashTags.FINGERPRINT,
    )

    /** [event] filed under the build it describes, and grouped under what it was captured with. */
    private fun filed(event: CrashEvent): CrashEvent {
        val group = event.tags[CrashTags.FINGERPRINT]
        return event.copy(
            release = event.tags[CrashTags.DESCRIBED_RELEASE] ?: event.release,
            dist = event.tags[CrashTags.DESCRIBED_BUILD] ?: event.dist,
            fingerprint = if (group != null) listOf(group) else event.fingerprint,
        )
    }

    private fun capped(text: String): String = EventBounds.cap(text, eventTextBytes)

    /**
     * A breadcrumb as it may leave: its message and string data values bounded **together** by
     * [EventBounds.BREADCRUMB_TEXT_BYTES], message first — the breadcrumb row of the whole-event sum. The device log
     * keeps the line in full.
     */
    fun crumb(crumb: Crumb): Crumb {
        val capped = EventBounds.capAll(listOfNotNull(crumb.message) + crumb.data.values, crumbTextBytes)
        val data = capped.drop(capped.size - crumb.data.size)
        return crumb.copy(
            message = crumb.message?.let { capped.firstOrNull() },
            data = crumb.data.keys.zip(data).toMap(),
        )
    }

    companion object {
        /** The one shaping a process's channel starts with. */
        val STANDARD: CrashShaping = CrashShaping(EventBounds.EVENT_TEXT_BYTES, EventBounds.BREADCRUMB_TEXT_BYTES)
    }
}
