@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.testsupport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile

/**
 * A private directory for one test, removed afterwards.
 *
 * A near-copy of `:adapter:ios:ext-safe`'s, and deliberately not shared: a Kotlin/Native **test**
 * source set cannot be exported to another module, so the alternative would be promoting a test
 * helper into one of the two shipped adapter modules — where it would link into the app and the
 * upload extension binaries. The duplication is four lines; the alternative ships test code to users.
 */
internal fun withTempDirectory(block: (String) -> Unit) {
    val path = newTempDirectory()
    try {
        block(path)
    } finally {
        removeDirectory(path)
    }
}

/** A fresh, empty directory; the caller removes it with [removeDirectory] (a contract binding's dispose). */
internal fun newTempDirectory(): String {
    val path = NSTemporaryDirectory().trimEnd('/') + "/snapsync-test-" + NSUUID().UUIDString()
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    return path
}

internal fun removeDirectory(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

/** Write [text] to [path], creating or replacing it. */
internal fun writeTextFile(path: String, text: String) {
    val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return
    data.writeToFile(path, atomically = true)
}

/** Whether a file exists — used to assert a removal actually removed something. */
internal fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)
