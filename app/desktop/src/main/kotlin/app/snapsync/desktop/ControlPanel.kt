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
fun ControlPanel(controller: PanelController, dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Test-only view control: forces the phone pane's theme (this panel's own chrome is unaffected).
        Text("Theme (phone pane)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dark, onCheckedChange = onDarkChange)
            Text(if (dark) "Dark" else "Light")
        }

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

        Text("Join event (config absent — full-screen JoiningEvent)")
        ButtonRow {
            Button(onClick = { controller.showJoinLoading() }) { Text("Loading") }
            Button(onClick = { controller.showJoinExplainAccess() }) { Text("Explain access") }
            Button(onClick = { controller.showJoinReady() }) { Text("Ready (confirm)") }
            Button(onClick = { controller.showJoinNotFound() }) { Text("Not found") }
            Button(onClick = { controller.showJoinLoadFailed() }) { Text("Load failed") }
            Button(onClick = { controller.showJoinCommitting() }) { Text("Committing") }
            Button(onClick = { controller.showJoinCommitFailed() }) { Text("Commit failed") }
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

        Text("Switch confirmation (joined — dialog over the joined layer)")
        ButtonRow {
            Button(onClick = { controller.showSwitchReady() }) { Text("Ready (switch?)") }
            Button(onClick = { controller.showSwitchNotFound() }) { Text("Not found") }
            Button(onClick = { controller.showSwitchLoadFailed() }) { Text("Load failed") }
            Button(onClick = { controller.showSwitchCommitFailed() }) { Text("Commit failed") }
        }

        Text("Attestation (joined)")
        ButtonRow {
            Button(onClick = { controller.showUnattested() }) { Text("Unattested (can't verify device)") }
        }

        Text("Event not started")
        ButtonRow {
            // Forges a FUTURE `startsAt` on the config and lets the REAL reduction derive the health — so
            // this preset exercises the real precedence too. Combine it with a permission preset above to
            // watch NeedsAccess correctly outrank the clock line.
            Button(onClick = { controller.showNotStarted() }) { Text("Not started (clock line)") }
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

        Text("Download (joined layer — capability photo-download)")
        ButtonRow {
            Button(onClick = { controller.setDownload(0, 0) }) { Text("hidden (0/0)") }
            Button(onClick = { controller.setDownload(2, 5) }) { Text("downloading (2/5)") }
            Button(onClick = { controller.setDownload(5, 5) }) { Text("all downloaded (5/5)") }
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
