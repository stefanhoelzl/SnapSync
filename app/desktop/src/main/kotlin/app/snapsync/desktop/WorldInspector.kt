package app.snapsync.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapsync.engine.UploadError
import app.snapsync.permission.PermissionStatus

/**
 * The world-inspector control panel (capability `full-stack-harness`): raw Material 3, **never** App*
 * (test equipment, like the forge's `ControlPanel`). Every control routes through the single
 * [WorldInspectorController]; no composable mutates world state inline. Two-column paired sections fill
 * the width and cut scroll. The panel reads the controller's recomputed [InspectorSnapshot].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorldInspector(
    controller: WorldInspectorController,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = controller.snapshot
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- Phone-pane theme (test-only view control; no world state) --------------------------
        Header("Theme (phone pane)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = dark, onCheckedChange = onDarkChange)
            Text(if (dark) "Dark" else "Light")
        }

        // ---- Presets + the OS invocation --------------------------------------------------------
        Header("Presets")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.presetClean() }) { Text("Clean") }
            Button(onClick = { controller.presetEnrolled() }) { Text("Enrolled") }
            Button(onClick = { controller.presetFreshJoin() }) { Text("Fresh join") }
            Button(onClick = { controller.presetReprovisionDedup() }) { Text("Re-provision (dedup)") }
            Button(onClick = { controller.presetForeignDownload() }) { Text("Foreign download") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.invokeExtension() }) { Text("▶ Invoke extension") }
            OutlinedButton(onClick = { controller.expireToken() }) { Text("Expire change token") }
        }

        // ---- Enrollment -------------------------------------------------------------------------
        Header("Enrollment")
        Text("Permission")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.setPermission(PermissionStatus.NOT_DETERMINED) }) { Text("Not determined") }
            OutlinedButton(onClick = { controller.setPermission(PermissionStatus.DENIED) }) { Text("Denied") }
            OutlinedButton(onClick = { controller.setPermission(PermissionStatus.GRANTED) }) { Text("Granted") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Next request → ")
            RadioButton(selected = controller.armedGrants, onClick = { controller.armNextRequest(true) })
            Text("grants")
            RadioButton(selected = !controller.armedGrants, onClick = { controller.armNextRequest(false) })
            Text("denies")
        }
        Text("Joined event: ${snap.joinedEventId ?: "— none —"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = snap.joinedEventId != null, onClick = { controller.reprovision() }) { Text("Re-provision") }
            // Both sides of the event-start FLOOR, drivable through the real stack (capability
            // `photo-selection-policy`). "started" is the ordinary case. "not started" is the interesting one:
            // the event's start is in the future, so the clamped cutoff admits NO photo — invoking the
            // extension must leave the backend column empty while the phone frame reads the clock line.
            OutlinedButton(onClick = { controller.createEvent("Harness event", PAST_START) }) {
                Text("Create event (started)")
            }
            OutlinedButton(onClick = { controller.createEvent("Future event", FUTURE_START) }) {
                Text("Create event (not started)")
            }
            OutlinedButton(enabled = snap.joinedEventId != null, onClick = { controller.leaveEvent() }) { Text("Leave") }
        }

        // ---- Gallery | Backend ------------------------------------------------------------------
        Header("Gallery  ▏  Backend")
        TwoUp(
            left = {
                Button(onClick = { controller.addAsset() }) { Text("+ Add asset") }
                // Selection policy (capability `photo-selection-policy`): each of these adds a real asset to
                // the gallery that the policy EXCLUDES — it must appear here and then never upload, never
                // enter the union, and never inflate N. "+ 1080p video" is the control: it is BELOW the
                // image floor but above the video floor, so it must still upload.
                Faint("selection policy — these must NOT upload (except the video):")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { controller.addScreenshot() }) { Text("+ Screenshot") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { controller.addScreenRecording() }) { Text("+ Screen rec") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { controller.addGif() }) { Text("+ GIF") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { controller.addLowResPhoto() }) { Text("+ Low-res") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { controller.addWhatsAppAlbumPhoto() }) { Text("+ WhatsApp album") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { controller.addHdVideo() }) { Text("+ 1080p video") }
                }
                if (snap.galleryRows.isEmpty()) Faint("(empty gallery)")
                snap.galleryRows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { controller.removeAsset(row.assetId) }) { Text("✕") }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildString {
                                append(row.assetId)
                                if (row.suppressed) append("  ⛔ upload-suppressed")
                                if (row.policyExcluded) append("  🚫 policy-excluded")
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            right = {
                OutlinedButton(
                    enabled = snap.joinedEventId != null,
                    onClick = { controller.injectForeignDevice() },
                ) { Text("+ Inject device") }
                snap.backend.forEach { dev ->
                    Text((if (dev.own) "own · " else "") + dev.deviceId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dev.objects.isEmpty()) Faint("   (no objects)")
                    dev.objects.forEach { obj -> Faint("   $obj") }
                }
            },
        )

        // ---- Upload jobs | Downloads ------------------------------------------------------------
        Header("Upload jobs  ▏  Downloads")
        TwoUp(
            left = {
                Faint("job limit: " + if (snap.jobLimit == Int.MAX_VALUE) "∞" else snap.jobLimit.toString())
                if (snap.jobs.isEmpty()) Faint("(no live jobs)")
                snap.jobs.forEach { job ->
                    Text(job.key + "  ·  attempt ${job.attempts}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { controller.completeJob(job.key) }) { Text("✓") }
                        OutlinedButton(onClick = { controller.failJob(job.key, UploadError.Network) }) { Text("Net") }
                        OutlinedButton(onClick = { controller.failJob(job.key, UploadError.Http(500)) }) { Text("Http") }
                        OutlinedButton(onClick = { controller.failJob(job.key, UploadError.Cancelled) }) { Text("Cxl") }
                        OutlinedButton(onClick = { controller.failJob(job.key, UploadError.Unknown("forced")) }) { Text("Unk") }
                    }
                }
            },
            right = {
                Button(enabled = snap.downloads.isNotEmpty(), onClick = { controller.stageAllDownloads() }) { Text("Stage all pending") }
                if (snap.downloads.isEmpty()) Faint("(no pending downloads)")
                snap.downloads.forEach { dl ->
                    Faint("${dl.deviceId}/${dl.assetId} · ${dl.resourceKey}")
                }
            },
        )

        // ---- Failure levers ---------------------------------------------------------------------
        Header("Failure levers")
        TwoUp(
            left = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = snap.backendOffline, onCheckedChange = { controller.setBackendOffline(it) })
                    Text(if (snap.backendOffline) "backend OFFLINE (502)" else "backend online")
                }
                OutlinedButton(onClick = { controller.armImportFailure() }) { Text("Arm import failure") }
            },
            right = {
                Text("Job limit")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { controller.setJobLimit(0) }) { Text("0") }
                    OutlinedButton(onClick = { controller.setJobLimit(1) }) { Text("1") }
                    OutlinedButton(onClick = { controller.setJobLimit(2) }) { Text("2") }
                    OutlinedButton(onClick = { controller.setJobLimit(Int.MAX_VALUE) }) { Text("∞") }
                }
            },
        )

        // ---- Engine console footer --------------------------------------------------------------
        Header("Engine console")
        val consoleScroll = rememberScrollState()
        val lines = controller.console
        LaunchedEffect(lines.size) { consoleScroll.scrollTo(consoleScroll.maxValue) }
        Column(
            modifier = Modifier.fillMaxWidth().height(140.dp)
                .border(1.dp, Color.Gray).padding(6.dp).verticalScroll(consoleScroll),
        ) {
            if (lines.isEmpty()) Faint("(empty)")
            lines.forEach { Faint(it) }
        }
        OutlinedButton(onClick = { controller.clearConsole() }) { Text("Clear console") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    Text(text, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun Faint(text: String) {
    Text(text, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Two equal-width columns for the paired sections. */
@Composable
private fun TwoUp(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { left() }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { right() }
    }
}

/**
 * Event-start presets for the inspector's Create controls (capability `full-stack-harness`). Canonical
 * cutoff shape — the mini-edge 400s anything else, faithfully to the real backend.
 *
 * [PAST_START] precedes `World.DEFAULT_DATE`, so a default-dated gallery asset is in scope and uploads
 * flow exactly as they did before start dates existed. [FUTURE_START] is far enough out that it stays in
 * the future for the life of the project — a fixed constant, so the harness needs no clock.
 */
private const val PAST_START = "2026-01-01T00:00:00Z"
private const val FUTURE_START = "2099-12-31T23:59:59Z"
