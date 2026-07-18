package app.snapsync.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The semantic "share the invite" action: a flat, icon-only button mirroring [LeaveButton]. Emphasis
 * and glyph are design-time choices owned here, so the call site passes only an accessibility
 * [description] and an [onClick] — never appearance. The share glyph and the flat (no-fill) treatment
 * are the skin's, contained to this module.
 */
@Composable
fun ShareButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(
            imageVector = Icons.Filled.IosShare,
            contentDescription = description,
            modifier = Modifier.size(28.dp),
        )
    }
}
