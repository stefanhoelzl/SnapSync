package app.snapsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.model.Layer
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

// The create flow's composition position and the draft it keeps across a failed create (capability
// `create-event`, "A failed create says so and changes nothing").

/**
 * What the host has entered on the create form: the name and the date range (capability `create-event`).
 *
 * Hoisted out of [CreateEventScreen] because the form is REMOVED from composition while the create is in
 * flight ([CreatingEventScreen] replaces it), and state remembered inside it died with it — so a failed
 * create brought the host back to an empty name and a fresh default range, breaking "the name and date
 * range the host entered SHALL still be there, so a retry is one tap". [CreateFlow] owns it instead,
 * across both screens, and drops it when the create flow is left for anything else (a join, the joined
 * layer): a later visit starts afresh with a newly frozen default, as the spec's first scenario requires.
 */
@Stable
internal class CreateDraft(initialFrom: LocalDateTime, initialUntil: LocalDateTime) {
    var name by mutableStateOf("")
    var from by mutableStateOf(initialFrom)
    var until by mutableStateOf(initialUntil)
}

/**
 * The default window `[now, now + 1 day]`, FROZEN at first composition (not re-derived at submit): the label
 * is the screen's whole statement of what will be sent, so a value that drifted between display and post
 * would make it lie.
 */
@Composable
private fun rememberCreateDraft(cutoff: CutoffFormatter): CreateDraft = remember {
    val initialFrom = cutoff.nowLocal()
    val next = initialFrom.date.plus(1, DateTimeUnit.DAY)
    CreateDraft(
        initialFrom = initialFrom,
        initialUntil = LocalDateTime(next.year, next.month.ordinal + 1, next.day, initialFrom.hour, initialFrom.minute),
    )
}

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
