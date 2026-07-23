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
 * the [LEDGER_APP_GROUP] container. Wiring-only and untestable (Foundation file I/O); the model +
 * producer it backs are exercised in `commonTest`.
 *
 * The device-global accumulator this also held is gone — the manifest is projected from the upload
 * ledger now (capability `sync-ledger`). Its file is simply abandoned: a stale `accumulator.json` in
 * the container is inert, and deleting it would be a migration with nothing to gain.
 */
class IosDeviceManifestStore(appGroup: String = LEDGER_APP_GROUP) : DeviceManifestStore {

    private val fileManager = NSFileManager.defaultManager

    private val dir: NSURL? = fileManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroup)
        ?.URLByAppendingPathComponent("device-manifest", isDirectory = true)

    override fun loadLastUploaded(): String? = readString(LAST_UPLOADED)

    override fun saveLastUploaded(json: String) = writeString(LAST_UPLOADED, json)

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
