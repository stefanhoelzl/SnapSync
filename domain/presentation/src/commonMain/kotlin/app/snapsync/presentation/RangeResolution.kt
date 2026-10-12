package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ShareCount
import kotlinx.datetime.LocalDateTime

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
    val from = resolveFrom(preset, customFrom, windowStart, windowEnd, nowLocal, until)
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
        today = nowLocal.date,
        shareCount = shareCount,
    )
}

/**
 * The upper bound. Resolved BEFORE the lower one so that `from`'s ceiling can be floored to it — which is
 * what makes an inverted range unrepresentable rather than merely unlikely.
 *
 * A custom end is taken as picked: the calendar held it to the share-range rule, so it MAY lie after the event's end.
 * It is only kept from ending before the event's start, which no pick reaches.
 */
internal fun resolveUntil(
    preset: RangeChoice,
    custom: LocalDateTime?,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
): LocalDateTime = when (preset) {
    RangeChoice.EVENT_START, RangeChoice.FROM_NOW -> windowEnd
    RangeChoice.CUSTOM -> (custom ?: windowEnd).coerceAtLeast(windowStart)
}

/**
 * The lower bound, never after `until` nor after the event's end. A custom start is taken as picked, so it MAY lie
 * before the event's start; from now never does, since it is offered only inside the event's window.
 */
internal fun resolveFrom(
    preset: RangeChoice,
    custom: LocalDateTime?,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    nowLocal: LocalDateTime,
    untilResolved: LocalDateTime,
): LocalDateTime = when (preset) {
    RangeChoice.EVENT_START -> windowStart
    RangeChoice.FROM_NOW -> nowLocal.coerceAtLeast(windowStart)
    RangeChoice.CUSTOM -> (custom ?: windowStart)
}.coerceAtMost(minOf(untilResolved, windowEnd))

/**
 * Participation, derived from the two switches. Both off is [Direction.Neither]: the event's settings may apply it,
 * while the join gate never commits it — its confirm is disabled there.
 */
internal fun directionOf(shareOn: Boolean, receiveOn: Boolean): Direction = when {
    shareOn && receiveOn -> Direction.Both
    shareOn -> Direction.UploadOnly
    receiveOn -> Direction.DownloadOnly
    else -> Direction.Neither
}

/**
 * "Now" is offered only while the present is INSIDE the event window (`startsAt <= now <= endsAt`).
 *
 * Compared as instants. An absent start means the window is not known yet, which is not the same as "now qualifies"; an absent end
 * means no upper bound, which is.
 */
internal fun nowWithinWindow(now: CaptureDate, startsAt: CaptureDate?, endsAt: CaptureDate?): Boolean =
    startsAt != null && now >= startsAt && (endsAt == null || now <= endsAt)

/**
 * The seeds the RECONFIGURE surface starts from, reconstructed from the persisted timestamps.
 *
 * Lossy by construction: the preset is not persisted, only the resulting instants, so a range equal to the
 * event's window seeds **From event start** and any other seeds **Custom** with its bounds — an original
 * "From now" pick is unrecoverable, and is just a custom start once "now" has moved on
 * (decision record `simplify-join-screen`, D1).
 */
internal fun reconfigureForm(membership: EventConfig, toLocal: (CaptureDate) -> LocalDateTime): RangeForm {
    val fromAtFloor = membership.minPhotoDate.at == membership.startsAt.at
    val untilAtCeiling = membership.maxPhotoDate.at == membership.endsAt.at
    val whole = fromAtFloor && untilAtCeiling
    return RangeForm(
        shareOn = membership.direction.includesUpload,
        receiveOn = membership.direction.includesDownload,
        saveToAlbum = membership.saveToAlbum,
        preset = if (whole) RangeChoice.EVENT_START else RangeChoice.CUSTOM,
        customFrom = if (whole || fromAtFloor) null else toLocal(membership.minPhotoDate.at),
        customUntil = if (whole || untilAtCeiling) null else toLocal(membership.maxPhotoDate.at),
    )
}
