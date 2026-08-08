@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.gallery


import app.snapsync.ports.DeviceManifestStore

import app.snapsync.engine.LEDGER_APP_GROUP
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
 * The App-Group persistence for the device manifest (capability `device-manifest`): the device-global
 * JSON of the last successfully-uploaded projection (skip-if-unchanged), under `device-manifest/` in
 * the [LEDGER_APP_GROUP] container.
 *
 * The device-global accumulator this also held is gone — the manifest is projected from the upload
 * ledger now (capability `sync-ledger`). Its file is simply abandoned: a stale `accumulator.json` in
 * the container is inert, and deleting it would be a migration with nothing to gain.
 *
 * **[containerPath] is a parameter, defaulting to the shared container**, so *where* the container
 * lives is the composition's decision rather than this adapter's (spec `module-architecture`, "Ports
 * are the I/O boundary named for the need"). Both shells omit it. This file was previously described
 * as "untestable (Foundation file I/O)", which was only true while the path was resolved in here: a
 * bundle-less test binary has no App-Group entitlement, so the container resolves to `null` and every
 * operation degrades to a silent no-op — the state a test could not tell from a working store.
 *
 * A `null` [containerPath] keeps exactly that degraded behaviour rather than raising, and deliberately
 * so: the manifest is a skip-if-unchanged *cache*, so losing it costs one redundant upload of the
 * manifest and nothing else, whereas raising here would abort an upload cycle over a cache miss.
 * (Contrast [app.snapsync.download.IosStagedBytes], which raises: staged *bytes* have nowhere else to
 * go.)
 */
class IosDeviceManifestStore(
    containerPath: String? = NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path,
) : DeviceManifestStore {

    private val fileManager = NSFileManager.defaultManager

    private val dir: NSURL? = containerPath
        ?.let { NSURL.fileURLWithPath(it, isDirectory = true) }
        ?.URLByAppendingPathComponent("device-manifest", isDirectory = true)

    override fun loadLastUploaded(): String? = readString(LAST_UPLOADED)

    override fun saveLastUploaded(json: String) = writeString(LAST_UPLOADED, json)

    /** Deletes the file: absent and "not believed" are the same state, which `loadLastUploaded` reads. */
    override fun clearLastUploaded() {
        val url = fileUrl(LAST_UPLOADED) ?: return
        fileManager.removeItemAtURL(url, error = null)
    }

    private fun fileUrl(name: String): NSURL? = dir?.URLByAppendingPathComponent(name)

    private fun readString(name: String): String? {
        val url = fileUrl(name) ?: return null
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        return NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    private fun writeString(name: String, content: String) {
        val container = dir ?: return
        fileManager.createDirectoryAtURL(container, withIntermediateDirectories = true, attributes = null, error = null)
        val url = fileUrl(name) ?: return
        val data = (content as NSString).dataUsingEncoding(NSUTF8StringEncoding) as? NSData ?: return
        data.writeToURL(url, atomically = true)
    }

    private companion object {
        const val LAST_UPLOADED = "last-uploaded.json"
    }
}
