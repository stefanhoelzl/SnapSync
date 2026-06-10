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
        Button(onClick = { controller.showNeverSynced() }) { Text("Never synced") }
        Button(onClick = { controller.showInProgress() }) { Text("In progress (~2 min left)") }
        Button(onClick = { controller.showInProgressEstimating() }) { Text("In progress (estimating…)") }
        Button(onClick = { controller.showSuspended() }) { Text("Suspended (waiting)") }
        Button(onClick = { controller.showComplete() }) { Text("Complete (5 min ago)") }
        Button(onClick = { controller.showIncomplete() }) { Text("Incomplete (5 min ago)") }
        Button(onClick = { controller.showFailed() }) { Text("Failed (5 min ago)") }
    }
}
