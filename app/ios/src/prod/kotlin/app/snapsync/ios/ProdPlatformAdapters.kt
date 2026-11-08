package app.snapsync.ios

import app.snapsync.background.IosWake
import app.snapsync.composition.app.AppDevicePorts
import app.snapsync.dev.InertDevControls
import app.snapsync.entry.NoEntryEvents
import app.snapsync.inbound.Log

/**
 * A production build's adapter set: the root's real adapters as they are — the Compose scene as the UI among them —
 * development controls that are inert and never deliver, and a launch that always composes. Compiled only WITHOUT
 * `-Psnapsync.rig=true`; a rig build takes the control channel's instead.
 */
internal fun platformAdapters(real: AppDevicePorts): PlatformAdapters = PlatformAdapters(
    devControls = InertDevControls,
    ui = real.ui,
    ports = real,
    entryEvents = NoEntryEvents,
    launch = { compose -> compose() },
    bootLines = emptyList(),
)

/** The root's wake: production's [IosWake], over the system's own task scheduler. */
internal fun rootWake(log: Log): IosWake = IosWake(log)
