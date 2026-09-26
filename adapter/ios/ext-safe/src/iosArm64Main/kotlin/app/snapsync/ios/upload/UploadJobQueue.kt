package app.snapsync.ios.upload

import app.snapsync.ports.Upload
import co.touchlab.kermit.Logger

/**
 * The device target's binding: the real PhotoKit upload-job queue, exactly as before this seam existed
 * (capability `background-upload`).
 *
 * Every shipped binary — TestFlight, App Store, and every sideloaded dev build — compiles this actual and
 * only this one. The simulator substitute is not merely unused here; it is absent from the binary.
 */
actual fun uploadJobQueue(log: Logger): Upload = IosPhotoKitUploadPlatform(log)
