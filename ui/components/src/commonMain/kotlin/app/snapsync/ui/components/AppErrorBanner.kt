package app.snapsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A **form-level error banner**: one bordered card, error-tinted, stating a submission failure right where
 * the action that caused it lives (pinned above the primary button). It is for a failure the field's
 * *current contents* did not cause — the server was unreachable, or rejected the request — so it does not
 * redden the input, which would falsely tell the host their typing is wrong.
 *
 * The card idiom matches the rest of the surface (16dp radius, 1dp border, `surface` fill); only the
 * accent is the scheme's `error`, tinted for the border and drawn as a small "!" glyph so the banner reads
 * as trouble at a glance without importing a new hue (the frozen palette keeps Material's error red by
 * convention). Appearance-free: the call site passes only the message [text].
 */
@Composable
fun AppErrorBanner(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.45f)),
        // A submission failure appears after an action the host just took, so announce it politely — the
        // banner reads itself to assistive tech without stealing focus from the field or the button.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IndicatorIcon(StatusIndicator.Error)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
