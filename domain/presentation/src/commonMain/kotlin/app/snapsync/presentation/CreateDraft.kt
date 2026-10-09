package app.snapsync.presentation

import app.snapsync.presentation.range.EventRange
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration

/**
 * What the host has entered on the create form (capability `create-event`): the [name] and the [range], where the
 * draft stands against the app's foreground life ([session]), and whether the host has chosen anything in the range.
 *
 * Kept by the container, not by the form: the form gives way to the in-flight screen while a create runs, and a failed
 * create must bring the host back to what they entered, so a retry is one tap ("A failed create says so and changes
 * nothing"). Until [rangeTouched] the range IS the preset — the start now, the last day the start's day, the end time
 * blank — and it follows the clock ([followNow]); the host's first choice freezes it. The name never counts.
 */
internal data class CreateDraft(
    val session: CreateDraftSession,
    val name: String,
    val range: EventRange,
    val rangeTouched: Boolean,
) {
    /** The host chose something in the range: it is theirs from now on. */
    fun choose(next: EventRange): CreateDraft = copy(range = next, rangeTouched = true)

    /** The preset follows the clock: an untouched range starts at [now]. A touched one is left alone. */
    fun followNow(now: LocalDateTime): CreateDraft =
        if (!rangeTouched && range.from != now) copy(range = EventRange(from = now)) else this

    /**
     * The draft after the app's [count]th return to the foreground, [awayFor] after it left: a return after a long
     * absence starts a fresh draft at [now]; any other new return moves an untouched start to [now].
     */
    fun afterReturn(count: Int, awayFor: Duration?, now: LocalDateTime): CreateDraft {
        val next = session.afterReturn(count, awayFor)
        return when {
            next == session -> this
            next.epoch != session.epoch -> fresh(next, now)
            else -> copy(session = next).followNow(now)
        }
    }

    companion object {
        /**
         * A fresh draft at [now]: an empty name, the start preset to now and the last day to today with its time BLANK.
         * There is deliberately no complete default: a range the host never looked at is exactly what got created
         * before.
         */
        fun fresh(session: CreateDraftSession, now: LocalDateTime): CreateDraft =
            CreateDraft(session, name = "", range = EventRange(from = now), rangeTouched = false)
    }
}
