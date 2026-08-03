package app.snapsync.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Owns the screen's convention-bearing structure: edge insets, the small app-name nav label, an
 * optional prominent [heading] beneath it (the joined event's name), the vertical centering of the
 * body content (the screen is a glanceable status display), and a bottom action cluster centered
 * across the width. Screens supply one or more action composables; this container row-arranges them
 * centered with consistent spacing, so the screen never hardcodes anchor or row geometry (spec: design-system).
 *
 * [onEditHeading] is the heading's edit affordance (capability `event-rename`). It is the deliberate
 * OPPOSITE of [onTitleDoubleTap]: a visible control with click semantics and an accessibility label,
 * because renaming an event is something a member should be able to find. The two never collide — they
 * sit on different slots (the app-name label and the heading), and only one of them is a control.
 *
 * The background `Surface` fills the whole screen (painting edge-to-edge under the iOS notch /
 * home indicator), while the content `Column` insets past the safe-area before applying the
 * uniform 24.dp margin — except at the bottom under [contentPinsActionCluster], where the
 * safe-area itself becomes the margin.
 */
@Composable
fun ScreenLayout(
    title: String,
    heading: String? = null,
    bottomActions: (@Composable () -> Unit)? = null,
    // The content pins its own full-width action cluster to the bottom edge (e.g. the join gate's
    // Join / Cancel). Native bottom-anchored actions rest ON the home-indicator strip — the strip
    // IS the breathing room, never a second margin on top of it — so the bottom inset becomes
    // max(safe-area, 12.dp) instead of safe-area + 24.dp: on a home-indicator device the cluster
    // sits directly above the strip (34pt), while the 12.dp floor keeps the touch target off the
    // physical edge where no strip exists (home-button devices, the desktop harness). The inset
    // values stay owned here — screens only name the arrangement.
    contentPinsActionCluster: Boolean = false,
    // The hidden operator affordance (capability `diagnostic-logging`): a double-tap on the app-name
    // label. `null` — the default, and every build with no reporting channel — wires no gesture at all.
    //
    // Deliberately a raw pointer-input gesture rather than `combinedClickable`: that would add click
    // semantics and a ripple, which is precisely what makes a control look like a control. This one
    // must be invisible and absent from the accessibility tree, so it is discoverable only to someone
    // told about it.
    onTitleDoubleTap: (() -> Unit)? = null,
    // The heading's edit affordance (capability `event-rename`): a real control beside the event name.
    // `null` — the default, and every screen that renders no heading — renders nothing at all, leaving
    // the layout byte-identical to what it was before this parameter existed.
    //
    // Deliberately an `IconButton` rather than a raw gesture: unlike `onTitleDoubleTap` above, this one
    // MUST read as a control and MUST appear in the accessibility tree. The pencil glyph and the flat
    // treatment are the skin's, contained here.
    onEditHeading: (() -> Unit)? = null,
    editHeadingDescription: String = "Rename event",
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    if (contentPinsActionCluster) {
                        WindowInsets.safeDrawing.union(WindowInsets(bottom = 12.dp))
                    } else {
                        WindowInsets.safeDrawing
                    },
                )
                .padding(
                    start = 24.dp,
                    top = 24.dp,
                    end = 24.dp,
                    bottom = if (contentPinsActionCluster) 0.dp else 24.dp,
                ),
        ) {
            // The small app-name nav label — always present, top-anchored (mockup `.navtitle`).
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (heading == null) 12.dp else 4.dp)
                    .then(
                        onTitleDoubleTap?.let { onDoubleTap ->
                            Modifier.pointerInput(onDoubleTap) {
                                detectTapGestures(onDoubleTap = { onDoubleTap() })
                            }
                        } ?: Modifier,
                    ),
            )
            // The prominent heading (the joined event's name), directly beneath the nav label. With an
            // edit affordance it becomes a centered row: the name keeps its own centering (it is the
            // thing being read) and the control sits beside it rather than displacing it.
            if (heading != null) {
                if (onEditHeading == null) {
                    Text(
                        text = heading,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                        )
                        IconButton(onClick = onEditHeading, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = editHeadingDescription,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
            if (bottomActions != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        bottomActions()
                    }
                }
            }
        }
    }
}
