package app.snapsync.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * The screen's main call to action. Emphasis is a design-time choice, so it is a distinct
 * component, not a parameter — a `SecondaryButton` arrives only with its first caller. [enabled] is
 * semantic (whether the action is available), not appearance — the skin owns the disabled treatment.
 */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled) {
        Text(label)
    }
}
