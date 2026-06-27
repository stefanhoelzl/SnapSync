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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Owns the screen's convention-bearing structure: edge insets, title placement, the vertical
 * centering of the body content (the screen is a glanceable status display), and the placement and
 * arrangement of an optional bottom-right [bottomEndActions] cluster. Screens supply one or more
 * action composables; this container row-arranges them end-aligned with consistent spacing, so the
 * screen never hardcodes bottom-anchor or row geometry itself (design.md §5).
 *
 * The background `Surface` fills the whole screen (painting edge-to-edge under the iOS notch /
 * home indicator), while the content `Column` insets past the safe-area before applying the
 * uniform 24.dp margin — so the title clears the notch instead of sticking into it. On platforms
 * without system insets (the desktop harness) `safeDrawing` is zero, so this is a no-op there.
 */
@Composable
fun ScreenLayout(
    title: String,
    bottomEndActions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
            if (bottomEndActions != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        bottomEndActions()
                    }
                }
            }
        }
    }
}
