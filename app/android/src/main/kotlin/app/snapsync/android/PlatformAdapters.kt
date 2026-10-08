package app.snapsync.android

import app.snapsync.compose.AppDevicePorts
import app.snapsync.ports.DevControls
import app.snapsync.ports.Ui

/**
 * The adapters a production build and a rig build supply differently — the one place the two builds differ
 * (`docs/architecture.md`, "A build-time-only module is contained by compilation"). `platformAdapters()` answers it from
 * this module's `src/prod`, or — only under `-Psnapsync.rig=true` — from the control channel's source. There is no flag
 * and no inert stub in either binary.
 */
internal class PlatformAdapters(
    /** The build's development controls. */
    val devControls: DevControls,
    /** The platform's UI, as this build registers it — decorated for the channel on a rig build. */
    val ui: Lazy<Ui>,
    /** The ports this launch composes over. */
    val ports: AppDevicePorts,
    /** What this build adds to the process's boot banner. */
    val bootLines: List<String>,
    /** What this build starts once the root has composed — the control channel, on a rig build. */
    val afterLaunch: () -> Unit,
    /**
     * Asks Play for the invite this installation carried (capability `join-event`) — the production build's
     * `AndroidInstallReferrer`; nothing on a rig build, which Play never installed and whose links may be mocked. Run
     * once per process, as the first screen is created.
     */
    val installReferrer: () -> Unit,
)
