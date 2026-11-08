package app.snapsync.ios.upload

import app.snapsync.inbound.Log
import app.snapsync.ports.OsUploadQueue

/**
 * The device target's binding: the real PhotoKit upload-job queue, exactly as before this seam existed.
 *
 * Every shipped binary — TestFlight, App Store, and every sideloaded dev build — compiles this actual and
 * only this one. The simulator substitute is not merely unused here; it is absent from the binary.
 */
actual fun uploadJobQueue(log: Log): OsUploadQueue = IosPhotoKitUploadPlatform(log)
