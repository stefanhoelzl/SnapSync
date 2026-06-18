package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One step in the setup gate (setup-gate capability): a card with a status glyph, a title, an
 * optional detail line, and an optional trailing action slot (filled by an `App*` action component
 * such as [PrimaryButton]). Owns its own card containment and internal arrangement; appearance-free
 * — no colors, shapes, or `Modifier` in the signature. Renders compactly (glyph + title only) when
 * no detail and no action are supplied, which is how a satisfied, collapsed step appears.
 */
@Composable
fun SetupCard(
    indicator: StatusIndicator,
    title: String,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val expanded = detail != null || action != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IndicatorIcon(indicator)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (action != null) action()
            }
        }
    }
}
