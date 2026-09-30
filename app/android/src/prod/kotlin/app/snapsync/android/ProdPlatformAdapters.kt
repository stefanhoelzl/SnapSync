package app.snapsync.android

import app.snapsync.compose.DevicePorts
import app.snapsync.dev.InertDevControls

/**
 * A production build's adapter set: the root's real adapters as they are — the Compose screen as the UI among them —
 * development controls that are inert and never deliver, and the real app uploader. Compiled only WITHOUT
 * `-Psnapsync.rig=true`; the rig build composes over an adapter choice of real and mocked systems instead. It links no
 * crash reporter yet ([app.snapsync.compose.NoCrashReporter] until phase 5), and reports nowhere.
 */
@Suppress("UNUSED_PARAMETER")
internal fun platformAdapters(root: SnapSyncRoot, real: DevicePorts): PlatformAdapters = PlatformAdapters(
    devControls = InertDevControls,
    ui = real.lazies.ui,
    ports = real,
    appDrivenUpload = { build -> build() },
    bootLines = listOf("[boot] adapters = all real (no crash reporter)"),
    afterLaunch = {},
)
