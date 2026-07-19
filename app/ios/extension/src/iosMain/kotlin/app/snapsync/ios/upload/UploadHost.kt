package app.snapsync.ios.upload

import platform.Foundation.NSBundle

/**
 * The compile-time upload host the system permits (`BackgroundUploadURLBase` in the extension's
 * `Info.plist`). PhotoKit's background-upload extension validates every job's destination against
 * this baked value — a user-configurable host is impossible — so it is the authoritative source of
 * the edge URL's host, combined at the composition root with the runtime Keychain event id.
 *
 * Reads from the **extension** bundle (`NSBundle.mainBundle` is the extension's own bundle in an
 * app-extension process). Returns `null` only if the key is missing — a build misconfiguration.
 */
internal fun uploadHostFromBundle(): String? =
    NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String
