package app.snapsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 *
 * The panel is **vertically scrollable** and each preset group flows across rows ([FlowRow]) so every
 * control stays reachable even when the window is short or the group list grows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlPanel(controller: PanelController) {
    Column(
        modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Config")
        val config by controller.currentConfig.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = config != null, onCheckedChange = { controller.setConfigPresent(it) })
            Text(if (config != null) "Config set" else "No config")
        }

        Text("Create event (config absent)")
        ButtonRow {
            Button(onClick = { controller.showCreateInput() }) { Text("Create input") }
            Button(onClick = { controller.showCreating() }) { Text("Creating (in flight)") }
            Button(onClick = { controller.showCreateFailedInvalidName() }) { Text("Failed: invalid name") }
            Button(onClick = { controller.showCreateFailedServer() }) { Text("Failed: server error") }
        }

        Text("Permission")
        ButtonRow {
            Button(onClick = { controller.showPermissionNotDetermined() }) { Text("Not determined") }
            Button(onClick = { controller.showPermissionDenied() }) { Text("Denied") }
            Button(onClick = { controller.showPermissionGranted() }) { Text("Granted") }
        }

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

        Text("Permission blocked (event joined)")
        ButtonRow {
            Button(onClick = { controller.showPermissionBlockedNotDetermined() }) { Text("Allow access (priming)") }
            Button(onClick = { controller.showPermissionBlockedDenied() }) { Text("Photo access turned off") }
        }

        Text("Sync")
        ButtonRow {
            Button(onClick = { controller.showLoading() }) { Text("Loading") }
            Button(onClick = { controller.showNothingToSync() }) { Text("Nothing to sync (N=0)") }
            Button(onClick = { controller.showInProgress() }) { Text("In progress (12/47, 8 uploading)") }
            Button(onClick = { controller.showComplete() }) { Text("Complete (34 of 34)") }
            Button(onClick = { controller.showOvershoot() }) { Text("Overshoot (6 of 5 → clamps)") }
        }

        Text("Gallery size (N)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.adjustGalleryBy(-1) }) { Text("N −") }
            Button(onClick = { controller.adjustGalleryBy(+1) }) { Text("N +") }
        }

        Text("In-flight (uploading now)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.adjustInFlightBy(-1) }) { Text("in-flight −") }
            Button(onClick = { controller.adjustInFlightBy(+1) }) { Text("in-flight +") }
        }
    }
}

// A preset group laid out across as many rows as the width needs, so a long group never runs off the
// edge and the panel stays compact (test equipment — raw Material 3, never App*).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}
