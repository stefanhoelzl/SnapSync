package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.RangeChoice
import kotlinx.datetime.LocalDateTime
import app.snapsync.model.RangeForm
import app.snapsync.model.ResolvedRange
import app.snapsync.model.ShareCount

/**
 * Resolve [form] against the event window `[windowStart, windowEnd]`.
 *
 * `until` is resolved FIRST so that `from`'s ceiling can be floored to it — which is what makes an
 * inverted range unrepresentable rather than merely unlikely.
 */
internal fun RangeForm.resolve(
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    nowLocal: LocalDateTime,
    nowAvailable: Boolean,
    toCutoff: (LocalDateTime) -> CaptureDate,
    shareCount: ShareCount = ShareCount.Counting,
): ResolvedRange {
    val until = resolveUntil(preset, customUntil, windowStart, windowEnd)
    val from = resolveFrom(preset, customFrom, windowStart, nowLocal, until)
    return ResolvedRange(
        windowStart = windowStart,
        windowEnd = windowEnd,
        from = from,
        until = until,
        chosenFrom = CaptureCutoff(toCutoff(from)),
        chosenUntil = CaptureCeiling(toCutoff(until)),
        direction = directionOf(shareOn, receiveOn),
        commitEnabled = shareOn || receiveOn,
        nowAvailable = nowAvailable,
        shareCount = shareCount,
    )
}

/**
 * The upper bound. Resolved BEFORE the lower one so that `from`'s ceiling can be floored to it — which is
 * what makes an inverted range unrepresentable rather than merely unlikely.
 */
internal fun resolveUntil(
    preset: RangeChoice,
    custom: LocalDateTime?,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
): LocalDateTime = when (preset) {
    RangeChoice.WHOLE_EVENT, RangeChoice.FROM_NOW -> windowEnd
    RangeChoice.CUSTOM -> (custom ?: windowEnd).coerceIn(windowStart, windowEnd)
}

/** The lower bound, coerced into `[windowStart, until]` on every resolution. */
internal fun resolveFrom(
    preset: RangeChoice,
    custom: LocalDateTime?,
    windowStart: LocalDateTime,
    nowLocal: LocalDateTime,
    untilResolved: LocalDateTime,
): LocalDateTime = when (preset) {
    RangeChoice.WHOLE_EVENT -> windowStart
    RangeChoice.FROM_NOW -> nowLocal
    RangeChoice.CUSTOM -> (custom ?: windowStart)
}.coerceIn(windowStart, untilResolved)

/**
 * Participation, derived from the two switches.
 *
 * The dead both-off case never reaches a commit — the commit action is disabled there — so its value is
 * inert, and `DownloadOnly` is an arbitrary safe placeholder rather than a meaningful default.
 */
internal fun directionOf(shareOn: Boolean, receiveOn: Boolean): Direction = when {
    shareOn && receiveOn -> Direction.Both
    shareOn -> Direction.UploadOnly
    else -> Direction.DownloadOnly
}

/**
 * "Now" is offered only while the present is INSIDE the event window (`startsAt <= now <= endsAt`).
 *
 * Compared in the canonical cutoff-string domain — fixed-width UTC, so lexicographic IS chronological. An
 * absent start means the window is not known yet, which is not the same as "now qualifies"; an absent end
 * means no upper bound, which is.
 */
internal fun nowWithinWindow(now: CaptureDate, startsAt: CaptureDate?, endsAt: CaptureDate?): Boolean =
    startsAt != null && now >= startsAt && (endsAt == null || now <= endsAt)

/**
 * The seeds the RECONFIGURE surface starts from, reconstructed from the persisted timestamps.
 *
 * Lossy by construction: the preset is not persisted, only the resulting instants, so a range spanning the
 * whole window seeds **Whole event** and anything narrower seeds **Custom** with its bounds — an original
 * "From now" pick is unrecoverable, and is just a custom start once "now" has moved on (`manage-membership`;
 * decision record `simplify-join-screen`, D1). A legacy config carrying no event end counts as at-the-ceiling.
 */
internal fun reconfigureForm(membership: EventConfig, toLocal: (CaptureDate) -> LocalDateTime?): RangeForm {
    val fromAtFloor = membership.minPhotoDate.at == membership.startsAt.at
    val untilAtCeiling = membership.endsAt == null || membership.maxPhotoDate.at == membership.endsAt?.at
    val whole = fromAtFloor && untilAtCeiling
    return RangeForm(
        shareOn = membership.direction.includesUpload,
        receiveOn = membership.direction.includesDownload,
        saveToAlbum = membership.saveToAlbum,
        preset = if (whole) RangeChoice.WHOLE_EVENT else RangeChoice.CUSTOM,
        customFrom = if (whole || fromAtFloor) null else toLocal(membership.minPhotoDate.at),
        customUntil = if (whole || untilAtCeiling) null else toLocal(membership.maxPhotoDate.at),
    )
}

/**
 * How far ahead the "no ceiling known yet" sentinel sits.
 *
 * Only the join gate reaches it, and only on a phase whose details have not loaded — a window it renders
 * no range row against. A membership always carries its own ceiling, so the sentinel never bounds a real
 * commit; it exists so the resolution is TOTAL rather than optional.
 */
internal const val NO_CEILING_YEARS = 100

/**
 * [this] form on a phone that [albumOffered] an event album or not (capability `event-album`): where none can be held,
 * the choice is not offered and nothing commits one, whatever was stored or tapped.
 */
internal fun RangeForm.offering(albumOffered: Boolean): RangeForm =
    if (albumOffered) this else copy(albumOffered = false, saveToAlbum = false)
