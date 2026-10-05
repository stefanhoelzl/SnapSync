package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.snapsync.model.RangeChoice
import kotlinx.datetime.LocalDateTime
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.share_range_change
import app.snapsync.ui.components.resources.share_range_from_now
import app.snapsync.ui.components.resources.share_range_title
import app.snapsync.ui.components.resources.share_range_whole_event
import org.jetbrains.compose.resources.stringResource

/**
 * The capture-date range as the member has CHOSEN it: the [preset], and the [from]/[until] it resolves to.
 * The resolved bounds travel with the preset because the row states them and the calendar opens on them —
 * a custom pick and a preset both read back as two instants.
 */
class RangeChoices(
    val preset: RangeChoice,
    val from: LocalDateTime,
    val until: LocalDateTime,
)

/** The two edits a member can make to a [RangeChoices]: pick a preset, or pick a custom range. */
class RangeChoiceActions(
    val onPreset: (RangeChoice) -> Unit,
    val onCustom: (from: LocalDateTime, until: LocalDateTime) -> Unit,
)

/**
 * The event window a range is picked inside, and whether "now" falls within it.
 *
 * [nowAvailable] is `false` when the present is outside `[start, end]`; the **From now** chip is then not
 * offered at all — outside the window it would clamp to a bound the member did not choose.
 */
class RangeWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val nowAvailable: Boolean,
)

/**
 * The capture-date **range** as ONE row (capabilities `join-event`, `manage-membership`): the resolved
 * range in the row's heaviest type, a [detail] line beneath it (the caller's "<preset> · <count>"), and an
 * edit affordance that opens the design system's range calendar bounded to the event [window], with the
 * **Whole event** / **From now** presets as chips above it. It replaced two captioned preset lists (three
 * start rows, two end rows) that alone filled the first view of the join screen (decision record
 * `simplify-join-screen`, D2).
 *
 * The calendar is the create screen's range picker in a dialog ([RangePickerDialog]), held to the event's
 * window. A chip commits its preset and closes; OK commits the calendar's span as a custom range; Cancel
 * changes nothing. The calendar opens on the range currently chosen, complete, so the first tap on a day
 * starts a new range and narrowing one end is a drag of that end.
 *
 * It is **not** a card of its own: it sits inside the Share section, because "do I share" and "which
 * photos" are one decision. Appearance-free: the choices, the actions, the window, and two strings cross
 * the signature — the caller owns the date formatting (`:ui:components` never re-derives it).
 */
@Composable
fun AppShareRangeRow(
    choices: RangeChoices,
    actions: RangeChoiceActions,
    window: RangeWindow,
    rangeLabel: String,
    detail: String,
) {
    val scheme = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }

    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = rangeLabel,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { picking = true }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.share_range_change))
            }
        }
    }

    if (picking) {
        val wholeEvent = stringResource(Res.string.share_range_whole_event)
        val fromNow = stringResource(Res.string.share_range_from_now)
        RangePickerDialog(
            initial = EventRange(choices.from, choices.until.date, choices.until.time, endPending = false),
            bounds = RangeBounds.within(window.start, window.end),
            title = stringResource(Res.string.share_range_title),
            presets = buildList {
                add(presetChip(wholeEvent, RangeChoice.WHOLE_EVENT, choices, actions) { picking = false })
                if (window.nowAvailable) {
                    add(presetChip(fromNow, RangeChoice.FROM_NOW, choices, actions) { picking = false })
                }
            },
            onDismiss = { picking = false },
            onConfirm = { from, until ->
                picking = false
                actions.onCustom(from.coerceIn(window.start, window.end), until.coerceIn(window.start, window.end))
            },
        )
    }
}

private fun presetChip(
    label: String,
    preset: RangeChoice,
    choices: RangeChoices,
    actions: RangeChoiceActions,
    close: () -> Unit,
) = RangePresetChip(label = label, selected = choices.preset == preset) {
    close()
    actions.onPreset(preset)
}
