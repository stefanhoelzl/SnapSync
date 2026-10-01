package app.snapsync.android

import app.snapsync.android.link.AndroidInstallReferrer
import app.snapsync.compose.DevicePorts
import app.snapsync.dev.InertDevControls
import co.touchlab.kermit.Logger

/**
 * A production build's adapter set: the root's real adapters as they are — the Compose screen as the UI among them —
 * development controls that are inert and never deliver. Compiled only WITHOUT
 * `-Psnapsync.rig=true`; the rig build composes over an adapter choice of real and mocked systems instead. It links no
 * crash reporter yet ([app.snapsync.compose.NoCrashReporter] until phase 5), and reports nowhere. It reads the invite
 * a Play install carried, through the real links adapter.
 */
internal fun platformAdapters(root: SnapSyncRoot, real: DevicePorts): PlatformAdapters = PlatformAdapters(
    devControls = InertDevControls,
    ui = real.lazies.ui,
    ports = real,
    bootLines = listOf("[boot] adapters = all real (no crash reporter)"),
    afterLaunch = {},
    installReferrer = {
        AndroidInstallReferrer(root.application, root.links, Logger.withTag("InstallReferrer")).deliverOnce()
    },
)
