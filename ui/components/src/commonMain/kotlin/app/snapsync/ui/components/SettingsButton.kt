package app.snapsync.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The semantic "event settings" action: a flat, icon-only button mirroring [ShareButton] and
 * [LeaveButton], for the joined-layer `settings · share · leave` row (capability
 * `reconfigure-membership`). It opens the in-place reconfigure surface. Emphasis and glyph are
 * design-time choices owned here, so the call site passes only an accessibility [description] and an
 * [onClick] — never appearance. Unlike [LeaveButton] (destructive → error accent), it keeps the default
 * content tint, like [ShareButton]. The gear glyph and the flat (no-fill) treatment are the skin's,
 * contained to this module.
 */
@Composable
fun SettingsButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = description,
            modifier = Modifier.size(28.dp),
        )
    }
}
