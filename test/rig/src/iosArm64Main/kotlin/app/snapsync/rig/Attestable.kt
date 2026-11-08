package app.snapsync.rig

import app.snapsync.composition.app.AppDevicePorts

/** A phone has App Attest: its own integrity service stands. */
internal actual fun attestable(real: AppDevicePorts): AppDevicePorts = real
