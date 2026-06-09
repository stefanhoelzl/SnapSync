package app.snapsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Display overrides: forge any supported UI state for manual UI exploration. */
@Composable
fun ControlPanel(controller: PanelController) {
    Column(
        modifier = Modifier.fillMaxHeight().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Display overrides")
        Button(onClick = { controller.showIdle() }) { Text("Idle") }
        Button(onClick = { controller.showUploading(done = 0, total = 10) }) { Text("Upload started (0 of 10)") }
        Button(onClick = { controller.showUploading(done = 3, total = 10) }) { Text("Uploading (3 of 10)") }
        Button(onClick = { controller.showUploading(done = 9, total = 10) }) { Text("Almost done (9 of 10)") }
    }
}
