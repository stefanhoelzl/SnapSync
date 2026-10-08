package app.snapsync.android

import app.snapsync.android.link.AndroidInstallReferrer
import app.snapsync.compose.AppDevicePorts
import app.snapsync.dev.InertDevControls
import co.touchlab.kermit.Logger

/**
 * A production build's adapter set: the root's real adapters as they are — the Compose screen as the UI among them —
 * development controls that are inert and never deliver. Compiled only WITHOUT
 * `-Psnapsync.rig=true`; the rig build composes over an adapter choice of real and mocked systems instead. Its crash
 * reporter is the real one, which starts only on a build that carries a destination — a distributed one. It reads the
 * invite a Play install carried, through the real links adapter.
 */
internal fun platformAdapters(root: SnapSyncRoot, real: AppDevicePorts): PlatformAdapters = PlatformAdapters(
    devControls = InertDevControls,
    ui = real.ui,
    ports = real,
    bootLines = listOf("[boot] adapters = all real"),
    afterLaunch = {},
    installReferrer = {
        AndroidInstallReferrer(root.application, root.links, Logger.withTag("InstallReferrer")).deliverOnce()
    },
)
