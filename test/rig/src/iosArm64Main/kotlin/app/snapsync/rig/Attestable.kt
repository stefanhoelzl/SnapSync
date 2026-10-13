package app.snapsync.rig

import app.snapsync.compose.AppDevicePorts

/** A phone has App Attest: its own integrity service stands. */
internal actual fun attestable(real: AppDevicePorts): AppDevicePorts = real
