package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Capture-date helpers: the one formatter of a capture date's canonical text, the local-pick conversion, the clamps and
 * the deletion witness.
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

/**
 * Clamp a [chosen] cutoff up to the event's [startsAt] **floor** — the effective cutoff is
 * `max(chosen, startsAt)`.
 *
 * This is applied ONCE, at join, and the result is what gets persisted as `EventConfig.minPhotoDate`.
 * Because `startsAt` is immutable, the clamped value is stable for the life of the membership — which is
 * what lets the upload cycle keep filtering on a single cutoff and keeps `startsAt` out of the upload
 * path entirely.
 *
 * It is also the load-bearing half of "nothing syncs before the event starts": a photo's capture date
 * cannot lie in the future, so while `minPhotoDate >= startsAt > now` NO asset satisfies
 * `creationDate >= minPhotoDate`. The gate is a theorem, not a branch.
 *
 * The floor can only ever **narrow** a membership's scope. A host who sets a distant-past start lowers
 * the *default* a joiner sees (which the joiner sees and can override upward); a host can never cause a
 * photo taken before `startsAt` to be uploaded, nor raise a member above the member's own choice.
 */
fun clampToFloor(chosen: CaptureCutoff, startsAt: EventStart): CaptureCutoff =
    CaptureCutoff(maxOf(chosen.at, startsAt.at))

/**
 * Clamp a [chosen] upper bound down to the event's [endsAt] **ceiling** — the effective upper bound is
 * `min(chosen, endsAt)`. The mirror of [clampToFloor].
 *
 * Applied ONCE, at join, alongside the floor clamp in the single `JoinEvent` choke point; the result is persisted as
 * `EventConfig.maxPhotoDate`. Because [endsAt] is the host's declared, immutable event window ceiling (creator-chosen
 * at creation), the clamp guarantees the invariant `maxPhotoDate <= endsAt`: the event can only **narrow** a
 * membership's window, never widen it beyond the member's own pick. A photo taken after `endsAt` is not a late event
 * photo but a **non-event** photo, and the window the member is committing to is shown before confirm — so the ceiling
 * is neither coarse nor silent. Every photo `<= endsAt` is still admitted on doubt.
 */
fun clampToCeiling(chosen: CaptureCeiling, endsAt: EventEnd): CaptureCeiling =
    CaptureCeiling(minOf(chosen.at, endsAt.at))

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
