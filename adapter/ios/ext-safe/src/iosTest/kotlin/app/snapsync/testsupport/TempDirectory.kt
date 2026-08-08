@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.testsupport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * A private directory for one test, removed afterwards.
 *
 * This is what makes the App-Group-backed adapters testable at all. A Kotlin/Native test binary is
 * not an app bundle, so it holds no `application-groups` entitlement and
 * `containerURLForSecurityApplicationGroupIdentifier` answers `nil` — every store that resolves its
 * own container therefore degrades to a no-op or a crash here, and the *only* thing a test could
 * observe would be that degradation. Handing the store a real directory instead exercises the same
 * code the device runs, with the one platform lookup it cannot have replaced.
 */
internal fun withTempDirectory(block: (String) -> Unit) {
    val manager = NSFileManager.defaultManager
    val path = NSTemporaryDirectory().trimEnd('/') + "/snapsync-test-" + NSUUID().UUIDString()
    manager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    try {
        block(path)
    } finally {
        manager.removeItemAtPath(path, error = null)
    }
}

/** The file's text, or `null` when it does not exist — the shape assertions here want. */
internal fun readTextFile(path: String): String? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    return NSString.create(data, NSUTF8StringEncoding)?.toString()
}

/** Write [text] to [path], creating or replacing it. */
internal fun writeTextFile(path: String, text: String) {
    val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) as? NSData ?: return
    data.writeToFile(path, atomically = true)
}

/** Whether a file exists — used to assert a removal actually removed something. */
internal fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)
