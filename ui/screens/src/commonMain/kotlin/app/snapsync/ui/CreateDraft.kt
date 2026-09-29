package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.Layer
import app.snapsync.ui.components.EventRange
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

// The create flow's composition position and the draft it keeps across a failed create (capability
// `create-event`, "A failed create says so and changes nothing").

/**
 * What the host has entered on the create form: the name and the date range (capability `create-event`).
 *
 * Hoisted out of [CreateEventScreen] because the form is REMOVED from composition while the create is in
 * flight ([CreatingEventScreen] replaces it), and state remembered inside it died with it — so a failed
 * create brought the host back to an empty form, breaking "the name and date range the host entered SHALL
 * still be there, so a retry is one tap". [CreateFlow] owns it instead, across both screens, and drops it
 * when the create flow is left for anything else (a join, the joined layer): a later visit starts afresh
 * with a newly frozen start.
 */
@Stable
internal class CreateDraft(name: String, range: EventRange) {
    var name by mutableStateOf(name)
    var range by mutableStateOf(range)
}

/**
 * The next thing the host still has to do before Create is allowed, in the order the screen asks for it —
 * or [COMPLETE] once there is nothing left (capability `create-event`).
 */
internal enum class CreateStep { NAME, END_TIME, COMPLETE }

internal fun CreateDraft.nextStep(): CreateStep = when {
    name.isBlank() -> CreateStep.NAME
    range.untilTime == null -> CreateStep.END_TIME
    else -> CreateStep.COMPLETE
}

/**
 * The fresh draft: an empty name, the start preset to now — FROZEN at first composition, not re-derived at
 * submit, because the summary is the screen's statement of what will be sent — and the last day preset to
 * today with its time BLANK. There is deliberately no complete default: a range the host never looked at is
 * exactly what got created before.
 */
@Composable
private fun rememberCreateDraft(cutoff: CutoffFormatter): CreateDraft = remember {
    CreateDraft(name = "", range = EventRange(from = nowToTheMinute(cutoff)))
}

/**
 * Now, cut to the minute: the summary and the wheels state the start to the minute, so seconds the host
 * cannot see would make the created start differ from the shown one.
 */
private fun nowToTheMinute(cutoff: CutoffFormatter): LocalDateTime =
    cutoff.nowLocal().let { LocalDateTime(it.date, LocalTime(it.hour, it.minute)) }

/**
 * The create flow — the form and its in-flight state — as ONE composition position, so the [CreateDraft]
 * it remembers survives the form → creating → form round trip a failed create makes. [layer] is only
 * ever one of the two create layers.
 */
@Composable
internal fun CreateFlow(
    layer: Layer,
    onCreateEvent: (String, LocalDateTime, LocalDateTime) -> Unit,
    cutoff: CutoffFormatter,
) {
    val draft = rememberCreateDraft(cutoff)
    if (layer is Layer.CreateEvent) CreateEventScreen(layer, draft, onCreateEvent, cutoff) else CreatingEventScreen()
}
