package app.snapsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.share_range_change
import app.snapsync.ui.components.resources.share_range_custom
import app.snapsync.ui.components.resources.share_range_event_start
import app.snapsync.ui.components.resources.share_range_from_now
import app.snapsync.ui.components.resources.share_range_title
import app.snapsync.ui.state.PickerView
import app.snapsync.ui.state.RangeChoice
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
 * The capture-date **range** as one choice and its answer: a row of chips — **From event start**, **From now** while
 * it is [fromNowOffered], and **Custom** — and beneath them the resolved range in the row's heaviest type with a
 * [detail] line (the caller's count). The quick options sit on the screen, one tap each; they used to hide behind an
 * edit button inside the calendar dialog (decision record `flexible-share-range`, D3).
 *
 * A preset chip commits its preset. **Custom** opens the design system's range calendar ([RangePickerDialog]), drawn
 * from [picker] — the app's calendar while it is open, `null` while closed — on the range currently chosen; tapping it
 * again while selected reopens the calendar there. OK takes the calendar's span as a custom range; Cancel changes
 * nothing. The calendar holds only the calendar: the presets are the chips.
 *
 * It is **not** a card of its own: it sits inside the Share section, because "do I share" and "which photos" are one
 * decision. Appearance-free: the choices, the actions and strings cross the signature — the caller owns the date
 * formatting (`:ui:components` never re-derives it).
 */
@Composable
fun AppShareRangeRow(
    choices: RangeChoices,
    actions: RangeChoiceActions,
    /** "From now" is offered only while the present lies inside the event window. */
    fromNowOffered: Boolean,
    picker: PickerView?,
    rangeLabel: String,
    /** The line beneath the range — how many photos it shares — or `null` when there is nothing to say. */
    detail: String?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeChip(
                label = stringResource(Res.string.share_range_event_start),
                selected = choices.preset == RangeChoice.EVENT_START,
            ) { actions.onPreset(RangeChoice.EVENT_START) }
            if (fromNowOffered) {
                RangeChip(
                    label = stringResource(Res.string.share_range_from_now),
                    selected = choices.preset == RangeChoice.FROM_NOW,
                ) { actions.onPreset(RangeChoice.FROM_NOW) }
            }
            RangeChip(
                label = stringResource(Res.string.share_range_custom),
                selected = choices.preset == RangeChoice.CUSTOM,
                hint = stringResource(Res.string.share_range_change),
                onClick = actions.onOpen,
            )
        }
        Text(
            text = rangeLabel,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (detail != null) {
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }

    if (picker != null) {
        RangePickerDialog(
            picker = picker,
            title = stringResource(Res.string.share_range_title),
            gestures = actions.gestures,
            onDismiss = actions.onDismiss,
            onConfirm = actions.onConfirm,
        )
    }
}

/**
 * One choice of the range: a selected chip is filled in the brand's container tone, the rest are outlined. Each is one
 * click target with the radio-button role and its selection stated — the choices are mutually exclusive. [hint] is
 * what the tap does, read out where the label alone does not say it (Custom opens the calendar).
 */
@Composable
private fun RangeChip(label: String, selected: Boolean, hint: String? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = CircleShape // 50% corners: a pill on a wider-than-tall chip
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .background(if (selected) scheme.primaryContainer else scheme.surface, shape)
            .clickable(onClickLabel = hint, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        )
    }
}
