package app.snapsync.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/**
 * The semantic "leave the event" action: a flat, icon-only button. Emphasis and glyph are
 * design-time choices owned here (per the `PrimaryButton` convention), so the call site passes only
 * an accessibility [description] and an [onClick] — never appearance. The Logout glyph and the flat
 * (no-fill) treatment are the skin's, contained to this module.
 */
@Composable
fun LeaveButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = description)
    }
}
