package app.snapsync.data.upload

import app.snapsync.domain.upload.AppUploaderSwitch
import app.snapsync.domain.upload.ExtensionSwitch
import app.snapsync.ports.DevControls

/**
 * The per-uploader development switches, read at every use — the rig's channel changes them live. On a production build
 * the port answers no pin at all, so both are on: what a shipped process uploads with is a function of the device and
 * its grant (decision record `changes/both-uploaders-active`, D8).
 */
class UploaderSwitches(private val dev: DevControls) {
    fun app(): AppUploaderSwitch = when (dev.uploaderPin()?.app) {
        false -> AppUploaderSwitch.OFF
        true, null -> AppUploaderSwitch.ON
    }

    fun extension(): ExtensionSwitch = when (dev.uploaderPin()?.extension) {
        false -> ExtensionSwitch.OFF
        true, null -> ExtensionSwitch.ON
    }
}
