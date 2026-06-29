package app.snapsync.gallery

import app.snapsync.engine.LEDGER_APP_GROUP
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToURL

/**
 * The background `URLSession` identifier shared by the **extension** (which enqueues manifest uploads)
 * and the **app** (which the system relaunches to handle their completion). Both must construct the
 * session with this identifier and `sharedContainerIdentifier = LEDGER_APP_GROUP`, or the app cannot
 * adopt the extension-started session's events.
 */
const val MANIFEST_URLSESSION_IDENTIFIER: String = "app.snapsync.manifests"

/** The PENDING/DONE state of an asset's on-disk manifest marker (capability `asset-manifest`). */
enum class ManifestState { ABSENT, PENDING, DONE }

/**
 * The App-Group on-disk dedup/retry marker for per-asset manifests (capability `asset-manifest`),
 * shared by the extension (writes PENDING, re-enqueues) and the app (flips to DONE on upload success).
 * A PENDING file holds the manifest JSON bytes that back the background upload; a DONE marker records
 * that the upload landed so the extension never re-enqueues it. Files live under `manifests/` in the
 * [LEDGER_APP_GROUP] container so both processes see the same state.
 *
 * Wiring-only and untestable (Foundation file I/O, device-only); the manifest model + synthesis it
 * stores are exercised in `commonTest` and on device.
 */
@OptIn(ExperimentalForeignApi::class)
class IosManifestStore(appGroup: String = LEDGER_APP_GROUP) {

    private val fileManager = NSFileManager.defaultManager

    // The `manifests/` subdirectory of the shared container; created lazily on first write.
    private val dir: NSURL? = fileManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroup)
        ?.URLByAppendingPathComponent("manifests", isDirectory = true)

    private fun pendingUrl(assetId: String): NSURL? = dir?.URLByAppendingPathComponent("$assetId$PENDING_SUFFIX")
    private fun doneUrl(assetId: String): NSURL? = dir?.URLByAppendingPathComponent("$assetId.manifest.done")

    private fun exists(url: NSURL?): Boolean = url?.path?.let(fileManager::fileExistsAtPath) == true

    /** The marker state for [assetId]: DONE wins over PENDING; neither present → ABSENT. */
    fun state(assetId: String): ManifestState = when {
        exists(doneUrl(assetId)) -> ManifestState.DONE
        exists(pendingUrl(assetId)) -> ManifestState.PENDING
        else -> ManifestState.ABSENT
    }

    /**
     * Write [json] as the asset's PENDING manifest file and return its file [NSURL] (the upload source
     * for a background `uploadTask(fromFile:)`), or `null` if the container/encoding is unavailable.
     */
    @OptIn(BetaInteropApi::class)
    fun writePending(assetId: String, json: String): NSURL? {
        val container = dir ?: return null
        fileManager.createDirectoryAtURL(container, withIntermediateDirectories = true, attributes = null, error = null)
        val url = pendingUrl(assetId) ?: return null
        val data = (json as platform.Foundation.NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
        return if ((data as NSData).writeToURL(url, atomically = true)) url else null
    }

    /** The PENDING file URL for re-enqueuing a stalled upload, or `null` if it is not present. */
    fun pendingFileUrl(assetId: String): NSURL? = pendingUrl(assetId)?.takeIf { exists(it) }

    /**
     * The asset ids with a PENDING (`.manifest.json`) marker on disk — the in-flight set the app's
     * status reads (capability `sync-status`). Enumerates the `manifests/` directory and strips the
     * `.manifest.json` suffix; an unavailable/empty directory yields an empty set.
     */
    fun pendingAssetIds(): Set<String> {
        val container = dir?.path ?: return emptySet()
        val names = fileManager.contentsOfDirectoryAtPath(container, error = null) ?: return emptySet()
        val out = mutableSetOf<String>()
        for (name in names) {
            val file = name as? String ?: continue
            if (file.endsWith(PENDING_SUFFIX)) out += file.removeSuffix(PENDING_SUFFIX)
        }
        return out
    }

    /**
     * Prune an asset's PENDING manifest file — the app's storage-truth backstop to the extension's
     * own prune-on-success (`sync-status`): once the listing reports the asset complete its in-flight
     * marker is removed. Idempotent — a missing file is a harmless no-op.
     */
    fun prunePending(assetId: String) {
        pendingUrl(assetId)?.let { fileManager.removeItemAtURL(it, error = null) }
    }

    /** Flip the asset to DONE (the upload landed): drop the PENDING source file and write the DONE marker. */
    @OptIn(BetaInteropApi::class)
    fun markDone(assetId: String) {
        pendingUrl(assetId)?.let { fileManager.removeItemAtURL(it, error = null) }
        val done = doneUrl(assetId) ?: return
        (("" as platform.Foundation.NSString).dataUsingEncoding(NSUTF8StringEncoding) as? NSData)
            ?.writeToURL(done, atomically = true)
    }

    private companion object {
        const val PENDING_SUFFIX = ".manifest.json"
    }
}
