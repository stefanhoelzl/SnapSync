package app.snapsync.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * The screen's main call to action. Emphasis is a design-time choice, so it is a distinct
 * component, not a parameter — a `SecondaryButton` arrives only with its first caller.
 */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(label)
    }
}
