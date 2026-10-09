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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.snapsync.model.PickerView
import app.snapsync.model.RangeChoice
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.share_range_change
import app.snapsync.ui.components.resources.share_range_from_now
import app.snapsync.ui.components.resources.share_range_title
import app.snapsync.ui.components.resources.share_range_whole_event
import kotlinx.datetime.LocalDateTime
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

/**
 * What a member can do to a [RangeChoices]: pick a preset, or open the range calendar ([onOpen]), work it ([gestures])
 * and take its span ([onConfirm]) or close it unchanged ([onDismiss]).
 */
class RangeChoiceActions(
    val onPreset: (RangeChoice) -> Unit,
    val onOpen: () -> Unit,
    val gestures: RangeGestures,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The capture-date **range** as ONE row: the resolved
 * range in the row's heaviest type, a [detail] line beneath it (the caller's "<preset> · <count>"), and an
 * edit affordance that opens the design system's range calendar bounded to the event's window, with the
 * **Whole event** / **From now** presets as chips above it. It replaced two captioned preset lists (three
 * start rows, two end rows) that alone filled the first view of the join screen (decision record
 * `simplify-join-screen`, D2).
 *
 * The calendar is the create screen's range picker in a dialog ([RangePickerDialog]), drawn from [picker] — the app's
 * calendar while it is open, `null` while closed. A chip commits its preset; OK commits the calendar's span as a custom
 * range; Cancel changes nothing. The app opens it on the range currently chosen, complete, and holds it to the event's
 * window.
 *
 * It is **not** a card of its own: it sits inside the Share section, because "do I share" and "which
 * photos" are one decision. Appearance-free: the choices, the actions, the window, and two strings cross
 * the signature — the caller owns the date formatting (`:ui:components` never re-derives it).
 */
@Composable
fun AppShareRangeRow(
    choices: RangeChoices,
    actions: RangeChoiceActions,
    /** "From now" is offered only while the present lies inside the event window. */
    fromNowOffered: Boolean,
    picker: PickerView?,
    rangeLabel: String,
    detail: String,
) {
    val scheme = MaterialTheme.colorScheme

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
            IconButton(onClick = actions.onOpen) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.share_range_change))
            }
        }
    }

    if (picker != null) {
        val wholeEvent = stringResource(Res.string.share_range_whole_event)
        val fromNow = stringResource(Res.string.share_range_from_now)
        RangePickerDialog(
            picker = picker,
            title = stringResource(Res.string.share_range_title),
            presets = buildList {
                add(presetChip(wholeEvent, RangeChoice.WHOLE_EVENT, choices, actions))
                if (fromNowOffered) add(presetChip(fromNow, RangeChoice.FROM_NOW, choices, actions))
            },
            gestures = actions.gestures,
            onDismiss = actions.onDismiss,
            onConfirm = actions.onConfirm,
        )
    }
}

private fun presetChip(label: String, preset: RangeChoice, choices: RangeChoices, actions: RangeChoiceActions) =
    RangePresetChip(label = label, selected = choices.preset == preset) { actions.onPreset(preset) }
