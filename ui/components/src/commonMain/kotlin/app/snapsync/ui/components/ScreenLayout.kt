package app.snapsync.ui.components

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The background `Surface` fills the whole screen (painting edge-to-edge under the iOS notch /
 * home indicator), while the content `Column` insets past the safe-area before applying the
 * 24.dp margin — uniform, except when [contentPinsActionCluster] tightens the bottom edge.
 */
@Composable
fun ScreenLayout(
    title: String,
    heading: String? = null,
    bottomActions: (@Composable () -> Unit)? = null,
    // The content pins its own full-width action cluster to the bottom edge (e.g. the join gate's
    // Join / Cancel): the cluster reads as the screen's floor, so it sits on a tighter 12.dp bottom
    // inset instead of the uniform 24.dp. The inset values stay owned here — screens only name the
    // arrangement.
    contentPinsActionCluster: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(
                    start = 24.dp,
                    top = 24.dp,
                    end = 24.dp,
                    bottom = if (contentPinsActionCluster) 12.dp else 24.dp,
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
                modifier = Modifier.fillMaxWidth().padding(bottom = if (heading == null) 12.dp else 4.dp),
            )
            // The prominent heading (the joined event's name), directly beneath the nav label.
            if (heading != null) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
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
