package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.snapsync.model.CreateDraftSession
import app.snapsync.model.Layer
import app.snapsync.model.ScreenMessage
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.components.EventRange
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

// The create flow's composition position and the draft it keeps across a failed create: a failed
// create says so and changes nothing.

/**
 * What the host has entered on the create form: the name and the date range.
 *
 * Hoisted out of [CreateEventScreen] because the form is REMOVED from composition while the create is in
 * flight ([CreatingEventScreen] replaces it), and state remembered inside it died with it — so a failed
 * create brought the host back to an empty form, breaking "the name and date range the host entered SHALL
 * still be there, so a retry is one tap". [CreateFlow] owns it instead, across both screens, and drops it
 * when the create flow is left for anything else (a join, the joined layer), or when a long absence starts a
 * fresh one.
 *
 * [rangeTouched] is whether the host has chosen anything in the range. Until then the range IS the preset —
 * the start now, the last day the start's day, the end time blank — and it follows the clock
 * ([followNow]); the host's first choice freezes it. The name never counts.
 */
@Stable
internal class CreateDraft(name: String, range: EventRange) {
    var name by mutableStateOf(name)
    var range by mutableStateOf(range)
        private set
    var rangeTouched by mutableStateOf(false)
        private set

    /** The host chose something in the range: it is theirs from now on. */
    fun choose(next: EventRange) {
        range = next
        rangeTouched = true
    }

    /** The preset follows the clock: an untouched range starts at [now]. A touched one is left alone. */
    fun followNow(now: LocalDateTime) {
        if (!rangeTouched && range.from != now) range = EventRange(from = now)
    }
}

/**
 * The next thing the host still has to do before Create is allowed, in the order the screen asks for it —
 * or [Complete] once there is nothing left, carrying the end a complete draft has.
 */
internal sealed interface CreateStep {
    object Name : CreateStep

    object EndTime : CreateStep

    class Complete(val until: LocalDateTime) : CreateStep
}

internal fun CreateDraft.nextStep(): CreateStep {
    val until = range.until
    return when {
        name.isBlank() -> CreateStep.Name
        until == null -> CreateStep.EndTime
        else -> CreateStep.Complete(until)
    }
}

/**
 * The fresh draft: an empty name, the start preset to now and the last day to today with its time BLANK.
 * There is deliberately no complete default: a range the host never looked at is exactly what got created
 * before. The start follows the clock until the host chooses something in the range (see [CreateDraft]).
 */
private fun freshDraft(cutoff: CutoffFormatter): CreateDraft =
    CreateDraft(name = "", range = EventRange(from = nowToTheMinute(cutoff)))

/**
 * Now, cut to the minute: the summary and the wheels state the start to the minute, so seconds the host
 * cannot see would make the created start differ from the shown one.
 */
private fun nowToTheMinute(cutoff: CutoffFormatter): LocalDateTime =
    cutoff.nowLocal().let { LocalDateTime(it.date, LocalTime(it.hour, it.minute)) }

/** How often an untouched start re-reads the clock: well inside a minute, so it never shows a stale one. */
private const val FOLLOW_NOW_MILLIS = 1_000L

/**
 * The create flow — the form and its in-flight state — as ONE composition position, so the [CreateDraft]
 * it remembers survives the form → creating → form round trip a failed create makes. [layer] is only
 * ever one of the two create layers.
 *
 * The draft follows the app's foreground life ([Layer.CreateEvent.draft]): a new
 * epoch — a return after a long absence — is a fresh draft; any other return moves an untouched start to now.
 * While the form shows and the range is untouched, the start also follows the clock minute by minute.
 */
@Composable
internal fun CreateFlow(
    layer: Layer,
    onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit,
    onOpenSettings: () -> Unit,
    cutoff: CutoffFormatter,
    onReportRefusal: (ScreenMessage) -> Unit,
) {
    // The creating layer carries no session; the draft keeps the one it was last shown with. A plain holder,
    // not state: remembering what was last rendered must not itself cause a recomposition.
    val lastShown = remember { LastSession() }
    val session = if (layer is Layer.CreateEvent) layer.draft.also { lastShown.value = it } else lastShown.value
    val draft = remember(session.epoch) { freshDraft(cutoff) }
    LaunchedEffect(draft, session.activation) { draft.followNow(nowToTheMinute(cutoff)) }
    if (layer is Layer.CreateEvent) {
        LaunchedEffect(draft, draft.rangeTouched) {
            while (!draft.rangeTouched) {
                draft.followNow(nowToTheMinute(cutoff))
                delay(FOLLOW_NOW_MILLIS)
            }
        }
        CreateEventScreen(layer, draft, CreateCallbacks(onCreateEvent, onOpenSettings, onReportRefusal), cutoff)
    } else {
        CreatingEventScreen()
    }
}

/** The draft session a [CreateFlow] last rendered the form with. */
private class LastSession(var value: CreateDraftSession = CreateDraftSession())
