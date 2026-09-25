package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
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
    deletesLocal: LocalDateTime? = null,
): ResolvedRange {
    val until = resolveUntil(untilPreset, untilCustom, windowStart, windowEnd)
    val from = resolveFrom(fromPreset, fromCustom, windowStart, nowLocal, until)
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
        deletesLocal = deletesLocal,
    )
}

/**
 * The upper bound. Resolved BEFORE the lower one so that `from`'s ceiling can be floored to it — which is
 * what makes an inverted range unrepresentable rather than merely unlikely.
 */
internal fun resolveUntil(
    preset: UntilChoice,
    custom: LocalDateTime?,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
): LocalDateTime = when (preset) {
    UntilChoice.EVENT_END -> windowEnd
    UntilChoice.CUSTOM -> (custom ?: windowEnd).coerceIn(windowStart, windowEnd)
}

/** The lower bound, coerced into `[windowStart, until]` on every resolution. */
internal fun resolveFrom(
    preset: FromChoice,
    custom: LocalDateTime?,
    windowStart: LocalDateTime,
    nowLocal: LocalDateTime,
    untilResolved: LocalDateTime,
): LocalDateTime = when (preset) {
    FromChoice.EVENT_START -> windowStart
    FromChoice.NOW -> nowLocal
    FromChoice.CUSTOM -> (custom ?: windowStart)
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
 * Lossy by construction: the presets are not persisted, only the resulting instants, so
 * `minPhotoDate == startsAt` seeds **Event start** and anything above it seeds **Custom** — the original
 * "Now" pick is unrecoverable (`manage-membership` decision "cutoff pre-fill reconstruction"). The
 * same reading applies at the ceiling, where a legacy config carrying no event end counts as at-the-ceiling.
 */
internal fun reconfigureForm(membership: EventConfig, toLocal: (CaptureDate) -> LocalDateTime?): RangeForm {
    val fromAtFloor = membership.minPhotoDate.at == membership.startsAt.at
    val untilAtCeiling = membership.endsAt == null || membership.maxPhotoDate.at == membership.endsAt?.at
    return RangeForm(
        shareOn = membership.direction.includesUpload,
        receiveOn = membership.direction.includesDownload,
        saveToAlbum = membership.saveToAlbum,
        fromPreset = if (fromAtFloor) FromChoice.EVENT_START else FromChoice.CUSTOM,
        fromCustom = if (fromAtFloor) null else toLocal(membership.minPhotoDate.at),
        untilPreset = if (untilAtCeiling) UntilChoice.EVENT_END else UntilChoice.CUSTOM,
        untilCustom = if (untilAtCeiling) null else toLocal(membership.maxPhotoDate.at),
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
