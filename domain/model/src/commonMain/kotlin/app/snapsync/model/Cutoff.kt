package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Capture-date helpers: the one formatter of a capture date's canonical text, the local-pick conversion, the
 * share-range rule and the deletion witness.
 */

/**
 * The canonical text of [instant]: UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`, second precision, no offset, no fractional second —
 * the one shape the wire and the persisted config carry.
 */
internal fun canonicalText(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.UTC)
    return buildCutoff(dt.year, dt.month.ordinal + 1, dt.day, dt.hour, dt.minute, dt.second)
}

/** A picked **local** wall-clock date-time in the device [zone], as the capture date it names. */
fun localToCutoff(local: LocalDateTime, zone: TimeZone): CaptureDate = CaptureDate.of(local.toInstant(zone))

/** Why a chosen capture range cannot be a membership's — see [shareRangeFault]. */
enum class ShareRangeFault {
    /** Its end lies before its start. */
    INVERTED,

    /** It is longer than an event may be ([EVENT_WINDOW_MAX_SECONDS]). */
    TOO_LONG,

    /** It does not overlap the event's own range `[startsAt, endsAt]`. */
    OUTSIDE_EVENT,
}

/**
 * The one rule a membership's capture range obeys, or `null` when `[from, until]` obeys it:
 * no longer than an event may be, and overlapping the event's own range — it MAY start before [startsAt] and end
 * after [endsAt], as far as the member chose. Both ends are inclusive, so a range touching the event at one instant
 * overlaps it.
 *
 * It REFUSES, it never clamps. Every entry path funnels through the join and the reconfigure, which call it, and in a
 * shipped build none can produce a failing range: the calendar greys out what breaks it, the presets obey it by
 * construction, and an invite link's range hints are dropped. A failing range is therefore a bug or a rig caller's
 * mistake, and quietly moving it would join a range nobody chose.
 *
 * The length is measured in INSTANTS, as the create screen measures an event's ([EVENT_WINDOW_MAX_SECONDS]): "30
 * local days" across a daylight-saving change is an hour more or less.
 *
 * "Nothing is uploaded before the event starts" does NOT follow from this rule — a floor before the start is allowed.
 * The membership's policy derivation holds it instead ([selectionRulesFor]).
 */
fun shareRangeFault(
    from: CaptureCutoff,
    until: CaptureCeiling,
    startsAt: EventStart,
    endsAt: EventEnd,
): ShareRangeFault? = when {
    until.at < from.at -> ShareRangeFault.INVERTED
    until.at.at - from.at.at > EVENT_WINDOW_MAX_SECONDS.seconds -> ShareRangeFault.TOO_LONG
    until.at < startsAt.at || from.at > endsAt.at -> ShareRangeFault.OUTSIDE_EVENT
    else -> null
}

/**
 * Has this membership's own retention deadline passed? [deletesAt] is the server-derived instant persisted on the
 * membership (`EventConfig.deletesAt`); [now] is the current canonical instant.
 *
 * This is the **second witness** of the self-leave. A membership is torn down without user action only
 * when the backend reports the event definitively absent **and** this returns `true` — two independent
 * witnesses, one of which is **offline**. A backend that reports absence alone is disbelieved, because a
 * systemic fault (a deployed configuration once named a storage zone that does not exist) would otherwise
 * 404 every event and destroy every membership in the install base at once, unrecoverably: `EventConfig`
 * is the only record of the join and the invite QR is derived from it.
 *
 * The check is **exact, not heuristic**. Early reclamation deletes an event only once *every* enrolled
 * device has departed, and departing clears the local config before the backend is told — so a device
 * that still holds a membership can only ever be observing a **deadline** deletion, which is precisely
 * what this tests.
 */
fun confirmedGone(deletesAt: DeletesAt, now: CaptureDate): Boolean = now > deletesAt.at

private fun buildCutoff(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String {
    fun p(n: Int, width: Int) = n.toString().padStart(width, '0')
    return "${p(year, 4)}-${p(month, 2)}-${p(day, 2)}T${p(hour, 2)}:${p(minute, 2)}:${p(second, 2)}Z"
}
