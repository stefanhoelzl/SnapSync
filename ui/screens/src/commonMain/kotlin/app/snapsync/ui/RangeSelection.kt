package app.snapsync.ui

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import androidx.compose.runtime.Composable
import app.snapsync.model.Direction
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.JoinPhase
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.FromChoice
import app.snapsync.ui.components.UntilChoice
import app.snapsync.model.DeletesAt
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.EventConfig
import app.snapsync.model.CaptureDate
import app.snapsync.ui.components.RangeWindow

// The join gate's pure derivation (capability `join-event`): what the guest has chosen, resolved against
// the event's window. Separated from the screens that render it because it decides WHAT would be
// committed, not what is drawn — and because a resolution rule is far easier to find, and to argue with,
// when it is not surrounded by layout.

/**
 * What a member has chosen, resolved against the event's window — the answer for BOTH surfaces that pick
 * a capture range: the join gate and the in-place reconfigure.
 *
 * Pure derivation, lifted out of the screens because it answers a different question from the rendering
 * below it: this is WHAT would be committed, while the `when` beneath decides what is DRAWN. Keeping them
 * together made one function responsible for both, and made the bound-clamping rules hard to find among
 * layout.
 *
 * The two surfaces reach it by different roads — one reads the window off a [JoinPhase], the other off a
 * persisted [EventConfig] — and those roads genuinely differ (see [rememberReconfigureSelection]). What
 * they produce is the same ten values, which is why this is one type and not two: `minPhotoDate` and
 * `saveEnabled` were `chosenFrom` and `commitEnabled` under other names.
 *
 * Every bound is coerced into the event window on every composition, and `until` is resolved FIRST so
 * that `from`'s ceiling can be floored to it — which is what makes an inverted range unrepresentable
 * rather than merely unlikely.
 */
internal class RangeSelection(
    val windowStart: LocalDateTime,
    val windowEnd: LocalDateTime,
    val nowLocal: LocalDateTime,
    /** "Now" is offered only while the present is INSIDE the event window. */
    val nowAvailable: Boolean,
    val fromResolved: LocalDateTime,
    val untilResolved: LocalDateTime,
    val chosenFrom: CaptureCutoff,
    val chosenUntil: CaptureCeiling,
    val chosenDirection: Direction,
    val commitEnabled: Boolean,
) {
    /** The window as the design system wants it — the same three values, in the shape the picker takes. */
    val window: RangeWindow get() = RangeWindow(windowStart, windowEnd, nowAvailable)
}

@Composable
internal fun rememberJoinSelection(
    phase: JoinPhase,
    cutoff: CutoffFormatter,
    participation: Participation,
): RangeSelection {
    val startStr = phase.startsAt()
    val endStr = phase.endsAt()

    // The join gate's window comes off the PHASE, and either bound may be missing: a screen mounted at
    // `Loading` has no details yet. A missing start falls back to now, and a missing end to a far-future
    // sentinel — the widest safe reading, since the bounds only ever narrow from here.
    val windowStart: LocalDateTime = startStr?.let { cutoff.toLocal(it.at) } ?: cutoff.nowLocal()
    val windowEnd: LocalDateTime = endStr?.let { cutoff.toLocal(it.at) }
        ?: LocalDateTime(windowStart.year + NO_CEILING_YEARS, 1, 1, 0, 0)

    return rangeOver(
        cutoff = cutoff,
        windowStart = windowStart,
        windowEnd = windowEnd,
        nowAvailable = nowWithinWindow(cutoff.nowCutoff(), startStr?.at, endStr?.at),
        participation = participation,
    )
}

/**
 * The same derivation for the **reconfigure** surface, which reaches the window by a different road: the
 * membership is already persisted, so both bounds exist and neither can be missing.
 *
 * The one deliberate divergence from the join gate is the upper bound. A legacy membership (pre-backfill)
 * may carry no event `endsAt`, but it always carries its OWN ceiling now (capability `join-event`), so the
 * picker bounds against that rather than the far-future sentinel the join gate falls back to. "Event end"
 * then resolves to the member's current upper bound, which makes a no-edit Save idempotent rather than
 * silently widening it — the sentinel would widen every membership that saved without touching the range.
 */
@Composable
internal fun rememberReconfigureSelection(
    membership: EventConfig,
    cutoff: CutoffFormatter,
    participation: Participation,
): RangeSelection {
    val windowStart: LocalDateTime = cutoff.toLocal(membership.startsAt.at) ?: cutoff.nowLocal()
    val windowEnd: LocalDateTime =
        cutoff.toLocal(membership.endsAt?.at ?: membership.maxPhotoDate.at) ?: windowStart

    return rangeOver(
        cutoff = cutoff,
        windowStart = windowStart,
        windowEnd = windowEnd,
        nowAvailable = nowWithinWindow(cutoff.nowCutoff(), membership.startsAt.at, membership.endsAt?.at),
        participation = participation,
    )
}

/**
 * "Now" is offered only while the present is INSIDE the event window (`startsAt <= now <= endsAt`).
 *
 * Compared in the canonical cutoff-string domain — fixed-width UTC, so lexicographic IS chronological.
 * An absent start means the window is not known yet, which is not the same as "now qualifies"; an absent
 * end means no upper bound, which is.
 */
internal fun nowWithinWindow(now: CaptureDate, startsAt: CaptureDate?, endsAt: CaptureDate?): Boolean =
    startsAt != null && now >= startsAt && (endsAt == null || now <= endsAt)

/**
 * Everything both surfaces derive once their window is known. The window derivation itself stays with each
 * caller, because that is the part that genuinely differs; from here down the rules are identical, and a
 * clamping rule that held on one surface and not the other would be a bug nobody would ever see.
 */
@Composable
private fun rangeOver(
    cutoff: CutoffFormatter,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
    nowAvailable: Boolean,
    participation: Participation,
): RangeSelection {
    val nowLocal: LocalDateTime = cutoff.nowLocal()

    // Resolve until first (independent of from), then floor `from`'s ceiling to it so the range can never
    // invert. Every bound is coerced into the event window on every composition.
    val choices = participation.choices
    val untilResolved: LocalDateTime =
        resolveUntil(choices.untilPreset, choices.untilCustom, windowStart, windowEnd)
    val fromResolved: LocalDateTime =
        resolveFrom(choices.fromPreset, choices.fromCustom, windowStart, nowLocal, untilResolved)

    return RangeSelection(
        windowStart = windowStart,
        windowEnd = windowEnd,
        nowLocal = nowLocal,
        nowAvailable = nowAvailable,
        fromResolved = fromResolved,
        untilResolved = untilResolved,
        chosenFrom = CaptureCutoff(cutoff.toCutoff(fromResolved)),
        chosenUntil = CaptureCeiling(cutoff.toCutoff(untilResolved)),
        // Direction is derived from the switches. The dead (both-off) case never reaches a commit — the
        // commit button is disabled there — so its value is inert.
        chosenDirection = directionOf(participation.shareOn, participation.receiveOn),
        commitEnabled = participation.shareOn || participation.receiveOn,
    )
}

/**
 * The four preset seeds the reconfigure surface starts from, RECONSTRUCTED from the persisted timestamps.
 *
 * Lossy by construction: the join UI's presets are not persisted, only the resulting instants, so
 * `minPhotoDate == startsAt` seeds **Event start** and anything above it seeds **Custom** — the original
 * "Now" pick is unrecoverable (design decision "cutoff pre-fill reconstruction"). The same reading applies
 * at the ceiling, where a legacy config carrying no event end counts as at-the-ceiling.
 */
internal class ReconfigureSeeds(
    val fromPreset: FromChoice,
    val fromCustom: LocalDateTime?,
    val untilPreset: UntilChoice,
    val untilCustom: LocalDateTime?,
)

internal fun reconfigureSeeds(membership: EventConfig, cutoff: CutoffFormatter): ReconfigureSeeds {
    val fromAtFloor = membership.minPhotoDate.at == membership.startsAt.at
    val untilAtCeiling = membership.endsAt == null || membership.maxPhotoDate.at == membership.endsAt?.at
    return ReconfigureSeeds(
        fromPreset = if (fromAtFloor) FromChoice.EVENT_START else FromChoice.CUSTOM,
        fromCustom = if (fromAtFloor) null else cutoff.toLocal(membership.minPhotoDate.at),
        untilPreset = if (untilAtCeiling) UntilChoice.EVENT_END else UntilChoice.CUSTOM,
        untilCustom = if (untilAtCeiling) null else cutoff.toLocal(membership.maxPhotoDate.at),
    )
}


/**
 * The upper bound of the shared range. Resolved BEFORE the lower one so that `from`'s ceiling can be
 * floored to it — which is what makes an inverted range unrepresentable rather than merely unlikely.
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

/** The lower bound, coerced into `[windowStart, until]` on every composition. */
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
 * The dead both-off case never reaches a commit — Join is disabled there — so its value is inert, and
 * `DownloadOnly` is an arbitrary safe placeholder rather than a meaningful default.
 */
internal fun directionOf(shareOn: Boolean, receiveOn: Boolean): Direction = when {
    shareOn && receiveOn -> Direction.Both
    shareOn -> Direction.UploadOnly
    else -> Direction.DownloadOnly
}

