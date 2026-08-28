package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * A member's **uncommitted choices** on a decision surface — the join gate and the in-place reconfigure
 * ask for the same seven (capability `photo-selection-policy`, `join-event`, `reconfigure-membership`).
 *
 * These used to be seven `mutableStateOf`s held by each screen, declared twice with different seeds. That
 * gave them Compose's lifetime rather than the surface's, which is the wrong one: the join gate advances
 * `Ready → Committing → CommitFailed` beneath them, and a value tied to composition has already caused a
 * seeding bug there. Reduced state gives them a lifetime the type states.
 *
 * What is remembered is the PRESETS, and the picked wall-clock value behind a `CUSTOM` pick — never a
 * resolved instant. Resolution happens against the event window on every reduction ([resolve]), so a
 * window that arrives late (the details fetch resolving after the surface mounts) is picked up rather
 * than baked in.
 */
@Serializable
data class RangeForm(
    val shareOn: Boolean = true,
    val receiveOn: Boolean = true,
    val saveToAlbum: Boolean = false,
    val fromPreset: FromChoice = FromChoice.EVENT_START,
    val fromCustom: LocalDateTime? = null,
    val untilPreset: UntilChoice = UntilChoice.EVENT_END,
    val untilCustom: LocalDateTime? = null,
)

/**
 * What a [RangeForm] resolves to once the event window is known: what would be committed, and what the
 * surface renders.
 *
 * Wall-clock values rather than formatted strings — the design system owns how a date reads (capability
 * `design-system`), and the reduction owns what the date IS. The reduction applies the device's zone when
 * producing these, so a consumer rendering a transported state shows the device's own wall clock.
 */
@Serializable
data class ResolvedRange(
    val windowStart: LocalDateTime,
    val windowEnd: LocalDateTime,
    val from: LocalDateTime,
    val until: LocalDateTime,
    /** The same bounds in the canonical `…Z` domain — what a commit actually carries. */
    val chosenFrom: CaptureCutoff,
    val chosenUntil: CaptureCeiling,
    val direction: Direction,
    /** Both switches off is representable and does nothing, so the commit action is disabled with a reason. */
    val commitEnabled: Boolean,
    /** "Now" is offered only while the present is inside the event window. */
    val nowAvailable: Boolean,
    /**
     * How many of the member's own photos the chosen range would share (capability `join-share-count`),
     * or `null` when no count is available — a grant that permits none, or a count not yet computed.
     * Absent and zero mean different things: `0` says the chosen range admits none of their photos.
     */
    val shareableCount: Int? = null,
    /**
     * The event's retention deadline in wall-clock terms (capability `event-limits`), or `null` when the
     * surface has no event to state one for. Converted here for the same reason the bounds are: the
     * reduction holds the zone, and the design system formats what it is given.
     */
    val deletesLocal: LocalDateTime? = null,
)

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
    shareableCount: Int? = null,
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
        shareableCount = shareableCount,
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
 * "Now" pick is unrecoverable (`reconfigure-membership` decision "cutoff pre-fill reconstruction"). The
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
