package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The lower bound of a joining member's capture-date **range** (capability `photo-selection-policy`). A
 * semantic value — the caller maps it to an actual instant against the event window, so the design system
 * stays decoupled from the config capability.
 *
 * [EVENT_START] shares everything back to the event's start; [NOW] starts the contribution at the present
 * instant (offered only while the present is inside the event window — see [AppRangePresetChoices]); [CUSTOM]
 * lets a guest who arrived partway through pick their own start, bounded to the window.
 */
enum class FromChoice { EVENT_START, NOW, CUSTOM }

/**
 * The upper bound of a joining member's capture-date **range** (capability `photo-selection-policy`).
 * [EVENT_END] shares up to when the event ends (the default — narrow, never widen); [CUSTOM] lets a guest
 * stop contributing earlier, bounded to the window.
 */
enum class UntilChoice { EVENT_END, CUSTOM }

/**
 * The two ends of a capture-date range as the member has PICKED them: a preset each, plus the wall-clock
 * value behind a [FromChoice.CUSTOM] / [UntilChoice.CUSTOM] pick.
 *
 * A holder rather than four loose parameters because the four travel together everywhere — the join gate,
 * the reconfigure surface and this component all carry the same quartet, and interleaving each with its own
 * callback is what took the two screens' signatures past twenty parameters.
 */
class RangeChoices(
    val fromPreset: FromChoice,
    val fromCustom: LocalDateTime?,
    val untilPreset: UntilChoice,
    val untilCustom: LocalDateTime?,
)

/** The four edits a member can make to a [RangeChoices]. */
class RangeChoiceActions(
    val onFromPreset: (FromChoice) -> Unit,
    val onFromCustom: (LocalDateTime) -> Unit,
    val onUntilPreset: (UntilChoice) -> Unit,
    val onUntilCustom: (LocalDateTime) -> Unit,
)

/**
 * The event window a range is picked inside, and whether "now" falls within it.
 *
 * [nowAvailable] is `false` when the present is outside `[start, end]`; the **Now** row is then shown
 * disabled with a note rather than hidden, so the control's shape does not change between events.
 */
class RangeWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val nowAvailable: Boolean,
)

/**
 * The capture-date **range** as two **separate grouped sub-lists** — a **From** group
 * ([FromChoice.EVENT_START] / [FromChoice.NOW] / [FromChoice.CUSTOM]) and an **Until** group
 * ([UntilChoice.EVENT_END] / [UntilChoice.CUSTOM]) — each in its own recessed [AppSubSection] well headed
 * by a caption above it, each row with its option name, a one-line consequence, and a trailing checkmark on
 * the chosen one. The two bounds are two decisions, so they are two containers: as one well with a caption
 * between the groups, the seam *between* the handles was weaker than the dividers *inside* them.
 *
 * The component owns those wells — the embedding section does **not** wrap it in one. It is still **not a
 * card of its own**: both groups embed inside the Share section's card, because "share my photos" and
 * "from when / until when" are one decision surface.
 *
 * Selecting a **Custom** row opens the app's date+time picker dialog **directly**, constrained to the event
 * window `[windowStart, windowEnd]` — the row carries no inline field and never restates the chosen instant,
 * because the embedding section's own value line is the one statement of the resulting range. Confirming the
 * dialog commits the choice (coerced into the window via that handle's Custom-pick callback); cancelling
 * leaves the previous selection untouched. Tapping the already-selected Custom row re-opens the picker.
 *
 * [nowAvailable] is `false` when the present is outside the event window (`now < startsAt` or `now > endsAt`):
 * the **Now** row is then shown **disabled** (with a note) rather than hidden, so the control's shape does not
 * change between events.
 *
 * Appearance-free: the two option vocabularies, the two selections, the custom values, the window bounds, and
 * the two constraint notes cross the signature — no colors, shapes, text styles, or `Modifier`, no Material 3
 * type. The row treatment, the checkmark, and the picker dialog are owned here (design-system containment).
 */
@Composable
fun AppRangePresetChoices(
    choices: RangeChoices,
    actions: RangeChoiceActions,
    window: RangeWindow,
    fromFloorNote: String,
    untilCeilingNote: String,
) {
    // WHICH handle's Custom picker is open, if any — one nullable state rather than two booleans. Both
    // rows open the same dialog against the same window and differ only in seed and callback, and two
    // booleans made "both open at once" representable, which is not a state this surface has.
    var picking by remember { mutableStateOf<RangeHandle?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        FromGroup(
            preset = choices.fromPreset,
            onPreset = actions.onFromPreset,
            nowAvailable = window.nowAvailable,
            floorNote = fromFloorNote,
            onCustom = { picking = RangeHandle.FROM },
        )

        UntilGroup(
            preset = choices.untilPreset,
            onPreset = actions.onUntilPreset,
            ceilingNote = untilCeilingNote,
            onCustom = { picking = RangeHandle.UNTIL },
        )
    }

    picking?.let { handle ->
        DateTimePickerDialog(
            // Seed at the handle's current custom value, else its end of the window — never an empty
            // calendar.
            initial = when (handle) {
                RangeHandle.FROM -> choices.fromCustom ?: window.start
                RangeHandle.UNTIL -> choices.untilCustom ?: window.end
            },
            minimum = window.start,
            maximum = window.end,
            onDismiss = { picking = null },
            onConfirm = {
                picking = null
                // Coerce into the window: the calendar greys days outside it, but the wheels can still land
                // on a boundary-day hour outside it.
                val picked = it.coerceIn(window.start, window.end)
                when (handle) {
                    RangeHandle.FROM -> actions.onFromCustom(picked)
                    RangeHandle.UNTIL -> actions.onUntilCustom(picked)
                }
            },
        )
    }
}

/**
 * One handle as its own grouped sub-list: a [caption] above a recessed [AppSubSection] well holding that
 * handle's rows. Two bounds are two decisions, so each gets its own container — a single well with a
 * caption dropped between the groups makes the boundary between the handles the *weakest* seam in the
 * control, weaker than the dividers inside each group.
 *
 * The [tag] addresses the whole group (caption + well), so a test can assert a handle's rows are inside
 * their own group rather than merely present somewhere.
 */
@Composable
private fun HandleGroup(tag: String, caption: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        GroupCaption(caption)
        AppSubSection(content = rows)
    }
}

/**
 * The small caption heading a handle's group of rows, sitting **above** its well on the embedding card's
 * surface at the card's own text inset — the inset-grouped-list idiom, aligned with the section's note and
 * value lines. Inside the well it aligned with the row labels instead and read as a disabled first row.
 *
 * A [heading] in the semantics tree, so assistive technology can jump between the From and Until groups —
 * the navigation the visual split creates. One type level quieter than the `bodyLarge` row labels it heads,
 * within the existing scale (no new token).
 */
@Composable
private fun GroupCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/** The divider between two rows of a sub-section well — inset so it reads as a row seam, not an edge. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * One vertical choice: the option [label], its [consequence] beneath, a trailing checkmark when [selected].
 * The whole row is one `selectable` ([Role.RadioButton]) target — the mutually-exclusive idiom. It also
 * carries a [tag] so offscreen tests can address a specific row (the two "Custom" rows share a label, so
 * text alone is ambiguous by construction).
 */
@Composable
private fun ChoiceRow(
    tag: String,
    label: String,
    consequence: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 52.dp)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Text(
                text = consequence,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = scheme.primary,
                )
            }
        }
    }
}

/** Which end of the capture range a Custom picker is editing. */
private enum class RangeHandle { FROM, UNTIL }

/**
 * The **from** handle: the three ways to name a lower bound, in their own captioned well.
 *
 * Its own component because a handle is the unit this surface is made of — one caption, one selection,
 * one Custom row that opens the picker — and because the two handles differ in exactly the way that made
 * them worth naming separately: this one has three options and a [nowAvailable] gate, the other has two
 * and none.
 */
@Composable
private fun FromGroup(
    preset: FromChoice,
    onPreset: (FromChoice) -> Unit,
    nowAvailable: Boolean,
    floorNote: String,
    onCustom: () -> Unit,
) {
    HandleGroup(tag = "from-group", caption = "Share from") {
        ChoiceRow(
            tag = "from-event-start",
            label = "Event start",
            consequence = "Everything you've taken since the event began.",
            selected = preset == FromChoice.EVENT_START,
            enabled = true,
            onSelect = { onPreset(FromChoice.EVENT_START) },
        )
        RowDivider()
        ChoiceRow(
            tag = "from-now",
            label = "Now",
            consequence = if (nowAvailable) {
                "Only photos you take from here on."
            } else {
                "Same as the event start until the event begins."
            },
            selected = preset == FromChoice.NOW,
            enabled = nowAvailable,
            onSelect = { onPreset(FromChoice.NOW) },
        )
        RowDivider()
        ChoiceRow(
            tag = "from-custom",
            label = "Custom",
            // While selected the row states the CONSTRAINT (the floor), never the chosen date, which the
            // section's value line already carries.
            consequence = if (preset == FromChoice.CUSTOM) floorNote else "Pick your own start.",
            selected = preset == FromChoice.CUSTOM,
            enabled = true,
            onSelect = onCustom,
        )
    }
}

/** The **until** handle: two ways to name an upper bound. See [FromGroup] for why each handle is its own. */
@Composable
private fun UntilGroup(
    preset: UntilChoice,
    onPreset: (UntilChoice) -> Unit,
    ceilingNote: String,
    onCustom: () -> Unit,
) {
    HandleGroup(tag = "until-group", caption = "Share until") {
        ChoiceRow(
            tag = "until-event-end",
            label = "Event end",
            consequence = "Everything up to when the event ends.",
            selected = preset == UntilChoice.EVENT_END,
            enabled = true,
            onSelect = { onPreset(UntilChoice.EVENT_END) },
        )
        RowDivider()
        ChoiceRow(
            tag = "until-custom",
            label = "Custom",
            consequence = if (preset == UntilChoice.CUSTOM) ceilingNote else "Pick your own end.",
            selected = preset == UntilChoice.CUSTOM,
            enabled = true,
            onSelect = onCustom,
        )
    }
}
