package app.snapsync.ios.registry

import app.snapsync.inbound.Log
import app.snapsync.ports.ExtensionRegistry

/**
 * The device target's binding: the real `PHPhotoLibrary` registration.
 *
 * Every shipped binary compiles this actual and only this one; the simulator substitute is absent from the
 * binary rather than merely unused.
 */
actual fun extensionRegistry(log: Log): ExtensionRegistry = PhotoKitExtensionRegistry(log)
