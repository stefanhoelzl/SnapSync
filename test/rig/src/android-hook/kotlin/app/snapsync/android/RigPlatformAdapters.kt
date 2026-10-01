package app.snapsync.android

import app.snapsync.compose.DevicePorts
import app.snapsync.rig.androidRigLaunch
import app.snapsync.rig.start

/**
 * The Android rig build's **adapter set** — the control channel's footprint inside `:app:android`, living in
 * `:test:rig`'s tree. `app/android/build.gradle.kts` compiles this directory in place of `src/prod`, and adds the
 * `:test:rig` dependency, ONLY under `-Psnapsync.rig=true`.
 *
 * The app composes over the mocks, bar the real screen and lifecycle ([androidRigLaunch]); the UI is decorated so the
 * channel's `/user` verbs reach the core as taps do; the development controls are the channel's. Compiled INTO
 * `:app:android` and scanned by the shell gate, so it holds no decisions — they live in `:test:rig`.
 *
 * Called while `SnapSyncRoot` initializes: the root's members are reached only through the thunks, after it has
 * composed.
 */
internal fun platformAdapters(root: SnapSyncRoot, real: DevicePorts): PlatformAdapters {
    val launch = androidRigLaunch(real, uploadBase = BuildConfig.UPLOAD_BASE)
    return PlatformAdapters(
        devControls = launch.controls,
        ui = lazyOf(launch.ui),
        ports = launch.ports,
        bootLines = launch.bootLines,
        afterLaunch = {
            launch.start(
                core = { root.app },
                host = { root.host },
                lifecycle = root.lifecycle,
                links = root.links,
                context = root.application,
            )
        },
        // Play never installed a rig build, and its links may be mocked: an invite reaches it over `/os` instead.
        installReferrer = {},
    )
}
