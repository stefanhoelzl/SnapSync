package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A brief, calm word at the foot of the screen — the outcome of something the member just did (capability
 * `privacy-security`: what became of a report). It changes nothing and asks nothing; tapping it puts it away early,
 * and the state clears it on its own. Announced politely, so a screen reader says it without interrupting.
 */
@Composable
fun AppNotice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.inverseSurface,
        contentColor = scheme.inverseOnSurface,
        shadowElevation = 4.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(onClick = onDismiss),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}
