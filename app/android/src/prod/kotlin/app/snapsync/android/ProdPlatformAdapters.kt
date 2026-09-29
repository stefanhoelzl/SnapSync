package app.snapsync.android

import app.snapsync.compose.DevicePorts

/**
 * A production build's adapter set — which does not exist yet: Android has real adapters for the screen and its
 * foreground life, the clock, the storage systems, the photo library, the system UI and the uploads, but none yet for
 * the downloads, the push service or the crash reporter, and a composition over those would fail at its first use of
 * one anyway. So the build refuses at start, naming why, rather than composing half an app. Compiled only WITHOUT
 * `-Psnapsync.rig=true`; the rig build composes over an adapter choice of real and mocked systems instead. The
 * receiving and release adapters replace this.
 */
@Suppress("UNUSED_PARAMETER")
internal fun platformAdapters(root: SnapSyncRoot, real: DevicePorts): PlatformAdapters = error(
    "this Android build has no adapters yet for the downloads, the push service or the crash reporter; " +
        "build with -Psnapsync.rig=true",
)
