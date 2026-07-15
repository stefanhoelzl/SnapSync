package app.snapsync.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The semantic "leave the event" action: a flat, icon-only button. Emphasis and glyph are
 * design-time choices owned here (per the `PrimaryButton` convention), so the call site passes only
 * an accessibility [description] and an [onClick] — never appearance. The Logout glyph and the flat
 * (no-fill) treatment are the skin's, contained to this module. Because leaving an event is
 * destructive, the glyph is tinted with the skin's **error** accent (the share action keeps the
 * default content tint) — a skin-local color choice, not a signature parameter.
 */
@Composable
fun LeaveButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp),
        )
    }
}
