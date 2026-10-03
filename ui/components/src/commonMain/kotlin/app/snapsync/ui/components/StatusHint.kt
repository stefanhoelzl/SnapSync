package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign

/**
 * A quiet, centered secondary hint line (e.g. the create screen's "scan to join" footer). Semantic:
 * the call site passes only the text; the muted treatment and centering are owned here. [isError] states a
 * failure in the same slot (the create screen's message below Create), in the error colour. Given [onClick],
 * the line is a button that leads to what it names (the create screen's next missing step).
 */
@Composable
fun StatusHint(text: String, isError: Boolean = false, onClick: (() -> Unit)? = null) {
    val tappable = onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().then(tappable),
    )
}
