package app.snapsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The screen's main call to action: a full-width, prominent button. Emphasis is a design-time choice,
 * so it is a distinct component, not a parameter — a `SecondaryButton` arrives only with its first
 * caller. [onClick] is the action, or `null` while it is not available — one value, so a disabled button never
 * carries a handler; the skin owns the disabled treatment. Width, height, and shape are owned here (the call
 * site passes no appearance).
 */
@Composable
fun PrimaryButton(label: String, onClick: (() -> Unit)?) {
    Button(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
