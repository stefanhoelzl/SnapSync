@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.push

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.objc.checkedObjC
import app.snapsync.ports.PushRegistrationRecord
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL

/**
 * The last push registration the backend accepted (capability `receiving-photos`), as one file under
 * `push-registration/` in the [LEDGER_APP_GROUP] container — beside the membership and the manifest record, and
 * under the same default protection, so it is readable on a locked device once it has been unlocked since boot.
 *
 * App-only: the registration runs in the app process, and nothing in the upload extension reads it. A file in the
 * container rather than a Keychain item, deliberately: the container dies with the install, so a reinstall starts
 * with no record and publishes at its first entry — a Keychain item would outlive the install and could suppress
 * that publish against a backend that has since lost the registration.
 *
 * **[containerPath] is a parameter, defaulting to the shared container**, so where it lives is the composition's
 * decision and a test can point it at a temporary directory. A `null` path — a process without the App-Group
 * entitlement — degrades to a record that holds nothing and never raises: a lost record costs one idempotent
 * publish, and raising would end the registration's collector over a cache.
 */
class IosPushRegistrationRecord(
    containerPath: String? = NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path,
) : PushRegistrationRecord {

    private val fileManager = NSFileManager.defaultManager
    private val log = Logger.withTag("pushRegistrationRecord")

    private val dir: NSURL? = containerPath
        ?.let { NSURL.fileURLWithPath(it, isDirectory = true) }
        ?.URLByAppendingPathComponent("push-registration", isDirectory = true)

    private val file: NSURL? = dir?.URLByAppendingPathComponent(LAST_REGISTERED)

    override fun loadLastRegistered(): String? {
        val data = file?.let { NSData.dataWithContentsOfURL(it) } ?: return null
        return NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    override fun saveLastRegistered(value: String) {
        val container = dir ?: return
        val url = file ?: return
        checkedObjC("createDirectoryAtURL") {
            fileManager.createDirectoryAtURL(container, withIntermediateDirectories = true, attributes = null, error = it)
        }.onFailure { log.w(it) { "record directory could not be created — the write below fails" } }
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        if (!data.writeToURL(url, atomically = true)) {
            log.w { "the registration record was not written — the next entry publishes again" }
        }
    }

    private companion object {
        const val LAST_REGISTERED = "last-registered.txt"
    }
}
