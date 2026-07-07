@file:OptIn(ExperimentalMaterial3Api::class)

package app.snapsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The participation-direction choice on the join surface (capability `join-event`): whether this device
 * shares its photos, receives the event's photos, or both. A semantic value — the caller maps its own
 * `Direction` to/from this, so the design system stays decoupled from the config capability (mirrors the
 * `Arrow`/`ArrowLevel` split).
 */
enum class SyncDirectionChoice { BOTH, UPLOAD, DOWNLOAD }

/**
 * An arrows-only three-way segmented control for [SyncDirectionChoice] — an up arrow (share), a down
 * arrow (receive), and a bidirectional glyph (both). Appearance-free: no colors, shapes, text styles, or
 * `Modifier`, and no Material 3 type in the signature; the Material 3
 * `SingleChoiceSegmentedButtonRow` + `SegmentedButton` (and the arrow glyphs) are contained here
 * (design-system containment rule). Exactly one option is selected at all times; the caller renders an
 * explanatory caption alongside it (the glyphs alone carry no words).
 */
@Composable
fun AppDirectionSelector(selected: SyncDirectionChoice, onSelect: (SyncDirectionChoice) -> Unit) {
    val options = listOf(SyncDirectionChoice.BOTH, SyncDirectionChoice.UPLOAD, SyncDirectionChoice.DOWNLOAD)
    // The selected segment uses the app's primary green (the same hue as the in-sync check), not the M3
    // default secondary container.
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primary,
        activeContentColor = MaterialTheme.colorScheme.onPrimary,
        activeBorderColor = MaterialTheme.colorScheme.primary,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = colors,
                // No leading checkmark — the arrow glyph alone conveys the choice.
                icon = {},
            ) {
                when (option) {
                    SyncDirectionChoice.BOTH ->
                        Icon(Icons.Filled.SwapVert, contentDescription = "Share and receive")
                    SyncDirectionChoice.UPLOAD ->
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Only share")
                    SyncDirectionChoice.DOWNLOAD ->
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Only receive")
                }
            }
        }
    }
}
