package app.snapsync.android

import app.snapsync.compose.DevicePorts

/**
 * A production build's adapter set — which does not exist yet: Android has real adapters only for the screen and its
 * foreground life, and a composition over nothing else would fail at its first port anyway. So the build refuses at
 * start, naming why, rather than composing half an app. Compiled only WITHOUT `-Psnapsync.rig=true`; the rig build
 * composes over the mocks instead. The storage, sharing and receiving adapters replace this.
 */
@Suppress("UNUSED_PARAMETER")
internal fun platformAdapters(root: SnapSyncRoot, real: DevicePorts): PlatformAdapters =
    error("this Android build has no adapters for the systems the app talks to yet; build with -Psnapsync.rig=true")
