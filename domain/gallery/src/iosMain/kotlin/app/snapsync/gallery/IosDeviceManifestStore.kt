@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.gallery

import app.snapsync.model.encodeToJson

import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.deviceManifestFromJson
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
 * accumulator and the JSON of the last successfully-uploaded projection (skip-if-unchanged), under
 * `device-manifest/` in the [LEDGER_APP_GROUP] container. The accumulator is serialized as a
 * [DeviceManifest] with an empty `deviceId` (only its `assets` matter here — the real `deviceId` is
 * applied at projection time). Wiring-only and untestable (Foundation file I/O); the model + producer
 * it backs are exercised in `commonTest`.
 */
class IosDeviceManifestStore(appGroup: String = LEDGER_APP_GROUP) : DeviceManifestStore {

    private val fileManager = NSFileManager.defaultManager

    private val dir: NSURL? = fileManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroup)
        ?.URLByAppendingPathComponent("device-manifest", isDirectory = true)

    override fun loadAccumulator(): List<DeviceManifestAsset> {
        val text = readString(ACCUMULATOR) ?: return emptyList()
        return runCatching { deviceManifestFromJson(text).assets }.getOrDefault(emptyList())
    }

    override fun saveAccumulator(assets: List<DeviceManifestAsset>) {
        writeString(ACCUMULATOR, DeviceManifest(deviceId = "", assets = assets).encodeToJson())
    }

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
        const val ACCUMULATOR = "accumulator.json"
        const val LAST_UPLOADED = "last-uploaded.json"
    }
}
