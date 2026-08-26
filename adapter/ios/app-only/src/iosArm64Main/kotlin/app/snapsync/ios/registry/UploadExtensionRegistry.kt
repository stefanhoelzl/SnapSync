package app.snapsync.ios.registry

import app.snapsync.ports.UploadExtensionRegistry
import co.touchlab.kermit.Logger

/**
 * The device target's binding: the real `PHPhotoLibrary` registration.
 *
 * Every shipped binary compiles this actual and only this one; the simulator substitute is absent from the
 * binary rather than merely unused.
 */
actual fun uploadExtensionRegistry(log: Logger): UploadExtensionRegistry = PhotoKitExtensionRegistry(log)
