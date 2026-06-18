package app.snapsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Display overrides: forge any supported UI state for manual UI exploration. Permission
 * presets write the permission cell only; sync presets force permission to Granted so
 * their screen is always visible. No current-state readout — the phone frame shows the
 * truth.
 */
@Composable
fun ControlPanel(controller: PanelController) {
    Column(
        modifier = Modifier.fillMaxHeight().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Config")
        val config by controller.currentConfig.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = config != null, onCheckedChange = { controller.setConfigPresent(it) })
            Text(if (config != null) "Config set" else "No config")
        }

        Text("Permission")
        Button(onClick = { controller.showPermissionNotDetermined() }) { Text("Not determined") }
        Button(onClick = { controller.showPermissionDenied() }) { Text("Denied") }
        Button(onClick = { controller.showPermissionGranted() }) { Text("Granted") }

        Text("Next request →")
        val grants by controller.armedRequestGrants.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = grants, onClick = { controller.armNextRequest(true) })
            Text("grants")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !grants, onClick = { controller.armNextRequest(false) })
            Text("denies")
        }

        Text("Sync")
        Button(onClick = { controller.showLoading() }) { Text("Loading") }
        Button(onClick = { controller.showNeverSynced() }) { Text("Never synced") }
        Button(onClick = { controller.showInProgress() }) { Text("In progress (~2 min left)") }
        Button(onClick = { controller.showInProgressEstimating() }) { Text("In progress (estimating…)") }
        Button(onClick = { controller.showSuspended() }) { Text("Suspended (waiting)") }
        Button(onClick = { controller.showComplete() }) { Text("Complete (5 min ago)") }
        Button(onClick = { controller.showIncomplete() }) { Text("Incomplete (5 min ago)") }
    }
}
