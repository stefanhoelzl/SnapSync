package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A semantic opt-in row: a checkbox with a [label], the whole row tappable. Appearance-free — no colors,
 * shapes, text styles, or `Modifier` in the signature; the Material 3 `Checkbox`/`Text` (and the app's
 * primary-green selected tint) are contained here (design-system containment rule). Used on the join
 * surface for the "Save event photos to an album" opt-in (capability `event-album`).
 */
@Composable
fun AppCheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
