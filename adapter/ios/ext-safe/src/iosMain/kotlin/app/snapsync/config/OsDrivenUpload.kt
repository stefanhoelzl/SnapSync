@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

/**
 * Whether this OS carries the OS-driven upload mechanism at all — the iOS 26.1 background-upload API, whose selectors
 * trap below it. Read by the registry (its `supported`) and by the app's root, which constructs the PhotoKit uploader
 * only where it exists.
 */
fun osCarriesOsDrivenUpload(): Boolean =
    NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
        cValue<NSOperatingSystemVersion> {
            majorVersion = OS_DRIVEN_UPLOAD_MAJOR
            minorVersion = OS_DRIVEN_UPLOAD_MINOR
            patchVersion = 0
        },
    )

private const val OS_DRIVEN_UPLOAD_MAJOR = 26L
private const val OS_DRIVEN_UPLOAD_MINOR = 1L
