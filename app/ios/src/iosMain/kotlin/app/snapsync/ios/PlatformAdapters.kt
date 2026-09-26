package app.snapsync.ios

import app.snapsync.ports.DevControls
import app.snapsync.ports.Ui

/**
 * The adapters a production build and a rig build supply differently — the one place the two builds differ
 * (`docs/architecture.md`, "A build-time-only module is contained by compilation"). `platformAdapters()` answers it
 * from this module's `src/prod`, or — only under `-Psnapsync.rig=true` — from the control channel's source, which
 * decorates the UI and supplies its own development controls. There is no flag and no inert stub in either binary.
 */
internal class PlatformAdapters(
    /** The build's development controls: inert in production. */
    val devControls: DevControls,
    /** The platform's UI, as this build registers it: the Compose scene itself in production. */
    val ui: Ui,
)
