package app.snapsync.ios

import app.snapsync.compose.DevicePorts
import app.snapsync.ports.DevControls
import app.snapsync.ports.Ui

/**
 * The adapters a production build and a rig build supply differently — the one place the two builds differ
 * (`docs/architecture.md`, "A build-time-only module is contained by compilation"). `platformAdapters()` answers it
 * from this module's `src/prod`, or — only under `-Psnapsync.rig=true` — from the control channel's source, which
 * decorates the UI, supplies its own development controls and hands the root the ports its launch-time adapters chose
 * (`docs/testing.md`, "Launch-time adapters"). There is no flag and no inert stub in either binary.
 */
internal class PlatformAdapters(
    /** The build's development controls: inert in production. */
    val devControls: DevControls,
    /**
     * The platform's UI, as this build registers it: the Compose scene itself in production. Lazy, because the scene
     * reads the process's clock, which this set is built before.
     */
    val ui: Lazy<Ui>,
    /** The ports this launch composes over: the root's real adapters in production. */
    val ports: DevicePorts,
    /** Compose the graph at launch — [compose] itself in production; nothing on a rig launch whose adapter choice was refused. */
    val launch: (compose: () -> Unit) -> Unit,
    /** What this build adds to the process's boot banner: nothing in production. */
    val bootLines: List<String>,
)
