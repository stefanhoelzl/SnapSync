package app.snapsync.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun StatusText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}
