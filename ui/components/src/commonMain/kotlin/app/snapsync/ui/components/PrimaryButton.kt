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
 * caller. [enabled] is semantic (whether the action is available), not appearance — the skin owns the
 * disabled treatment. Width, height, and shape are owned here (the call site passes no appearance).
 */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
