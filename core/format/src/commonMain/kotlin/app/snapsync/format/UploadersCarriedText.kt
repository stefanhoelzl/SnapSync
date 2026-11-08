package app.snapsync.format

import app.snapsync.domain.upload.OsSupport

/**
 * Which uploaders an OS carries, as the bug report and the rig name it (`uploaders_carried`): the app's on every
 * version, and the extension beside it where the OS carries the mechanism. A constant of the running build — not which
 * one runs, because both may.
 */
object UploadersCarriedText {
    fun of(support: OsSupport): String = when (support) {
        OsSupport.CARRIED -> "app+extension"
        OsSupport.NOT_CARRIED -> "app"
    }
}
