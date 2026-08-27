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

// The join gate's pure derivation (capability `join-event`): what the guest has chosen, resolved against
// the event's window. Separated from the screens that render it because it decides WHAT would be
// committed, not what is drawn — and because a resolution rule is far easier to find, and to argue with,
// when it is not surrounded by layout.

/**
 * What the guest has chosen, resolved against the event's window.
 *
 * Pure derivation, lifted out of [JoiningEventScreen] because it answers a different question from the
 * rendering below it: this is WHAT would be committed if Join were pressed, while the phase `when`
 * decides what is DRAWN. Keeping them together made one function responsible for both, and made the
 * bound-clamping rules below hard to find among layout.
 *
 * Every bound is coerced into the event window on every composition, and `until` is resolved FIRST so
 * that `from`'s ceiling can be floored to it — which is what makes an inverted range unrepresentable
 * rather than merely unlikely.
 */
internal class JoinSelection(
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
    val joinEnabled: Boolean,
)

@Composable
internal fun rememberJoinSelection(
    phase: JoinPhase,
    cutoff: CutoffFormatter,
    fromPreset: FromChoice,
    fromCustom: LocalDateTime?,
    untilPreset: UntilChoice,
    untilCustom: LocalDateTime?,
    shareOn: Boolean,
    receiveOn: Boolean,
): JoinSelection {
    val startStr = phase.startsAt()
    val endStr = phase.endsAt()
    val nowStr = cutoff.nowCutoff()

    val windowStart: LocalDateTime = startStr?.let { cutoff.toLocal(it.at) } ?: cutoff.nowLocal()
    val windowEnd: LocalDateTime = endStr?.let { cutoff.toLocal(it.at) }
        ?: LocalDateTime(windowStart.year + NO_CEILING_YEARS, 1, 1, 0, 0)

    // "Now" is offered only while the present is INSIDE the event window (`startsAt <= now <= endsAt`) —
    // compared in the canonical cutoff-string domain (fixed-width UTC → lexicographic IS chronological).
    val nowAvailable: Boolean =
        startStr != null && nowStr >= startStr.at && (endStr == null || nowStr <= endStr.at)
    val nowLocal: LocalDateTime = cutoff.nowLocal()

    // Resolve until first (independent of from), then floor `from`'s ceiling to it so the range can never
    // invert. Every bound is coerced into the event window on every composition.
    val untilResolved: LocalDateTime = resolveUntil(untilPreset, untilCustom, windowStart, windowEnd)
    val fromResolved: LocalDateTime =
        resolveFrom(fromPreset, fromCustom, windowStart, nowLocal, untilResolved)

    val chosenFrom = CaptureCutoff(cutoff.toCutoff(fromResolved))
    val chosenUntil = CaptureCeiling(cutoff.toCutoff(untilResolved))

    // Direction is derived from the switches. The dead (both-off) case never reaches a commit — Join is
    // disabled there — so its value is inert; DownloadOnly is an arbitrary safe placeholder.
    val chosenDirection: Direction = directionOf(shareOn, receiveOn)
    val joinEnabled: Boolean = shareOn || receiveOn

    return JoinSelection(
        windowStart = windowStart,
        windowEnd = windowEnd,
        nowLocal = nowLocal,
        nowAvailable = nowAvailable,
        fromResolved = fromResolved,
        untilResolved = untilResolved,
        chosenFrom = chosenFrom,
        chosenUntil = chosenUntil,
        chosenDirection = chosenDirection,
        joinEnabled = joinEnabled,
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
