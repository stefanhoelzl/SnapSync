package app.snapsync.ios

import app.snapsync.compose.DevicePorts
import app.snapsync.dev.InertDevControls

/**
 * A production build's adapter set: the root's real adapters as they are — the Compose scene as the UI among them —
 * development controls that are inert and never deliver, and a launch that always composes. Compiled only WITHOUT
 * `-Psnapsync.rig=true`; a rig build takes the control channel's instead.
 */
internal fun platformAdapters(real: DevicePorts): PlatformAdapters = PlatformAdapters(
    devControls = InertDevControls,
    ui = real.lazies.ui,
    ports = real,
    launch = { compose -> compose() },
    appDrivenUpload = { build -> build() },
    bootLines = emptyList(),
)
