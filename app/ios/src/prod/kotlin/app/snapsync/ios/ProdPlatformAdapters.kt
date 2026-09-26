package app.snapsync.ios

import app.snapsync.dev.InertDevControls
import app.snapsync.scene.IosUi

/**
 * A production build's adapter set: the Compose scene as the UI, and development controls that are inert and never
 * deliver. Compiled only WITHOUT `-Psnapsync.rig=true`; a rig build takes the control channel's instead.
 */
internal fun platformAdapters(ui: IosUi): PlatformAdapters = PlatformAdapters(devControls = InertDevControls, ui = ui)
