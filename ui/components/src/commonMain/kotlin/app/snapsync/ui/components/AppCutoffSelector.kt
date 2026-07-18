@file:OptIn(ExperimentalMaterial3Api::class)

package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime

/**
 * Which capture-date cutoff a joining member chose (capability `photo-selection-policy`). A semantic value —
 * the caller maps it to an actual cutoff instant, so the design system stays decoupled from the config
 * capability (mirrors the [SyncDirectionChoice] split).
 *
 * There are exactly two, and no "custom" member. The accepted cost, recorded so it is not later
 * rediscovered as a defect: a **late-arriving guest has no exact answer** — for a party that started at
 * 18:00 and a guest joining at 21:00, [EVENT_START] sweeps in photos they took earlier that day and [NOW]
 * drops the party photos they have already taken.
 */
enum class CutoffChoice { NOW, EVENT_START }

/**
 * The capture-date cutoff row on the join surface: a two-preset segmented control plus a label naming the
 * **resulting instant**, so the member always sees the value they are about to commit to.
 *
 * Appearance-free: it carries the [selected] preset, a selection callback, the [resulting] instant to
 * display, whether the [NOW][CutoffChoice.NOW] preset is [nowAvailable], and an [enabled] flag — no
 * colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type in the signature.
 *
 * [nowAvailable] is `false` when the event has **not started yet**. Pre-start, "Now" would clamp to the
 * very same instant as "Event start" (`max(now, startsAt) == startsAt`), so it is rendered **disabled**
 * rather than as a button that visibly does nothing. It is disabled rather than *hidden* so the control's
 * shape does not change between events.
 *
 * [enabled] is `false` under a download-only membership: the cutoff scopes uploads only, so it is visible
 * but inert.
 */
@Composable
fun AppCutoffSelector(
    selected: CutoffChoice,
    onSelect: (CutoffChoice) -> Unit,
    resulting: LocalDateTime,
    nowAvailable: Boolean = true,
    enabled: Boolean = true,
) {
    val options = listOf(CutoffChoice.NOW, CutoffChoice.EVENT_START)
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primary,
        activeContentColor = MaterialTheme.colorScheme.onPrimary,
        activeBorderColor = MaterialTheme.colorScheme.primary,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                val segmentEnabled =
                    enabled && (option != CutoffChoice.NOW || nowAvailable)
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = segmentEnabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = colors,
                    icon = {},
                ) {
                    Text(
                        when (option) {
                            CutoffChoice.NOW -> "Now"
                            CutoffChoice.EVENT_START -> "Event start"
                        },
                    )
                }
            }
        }
        Text(
            text = "From ${formatStart(resulting)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}