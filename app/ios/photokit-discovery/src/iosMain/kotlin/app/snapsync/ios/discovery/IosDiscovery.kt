package app.snapsync.ios.discovery

import app.snapsync.engine.UploadRequest
import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.upload.Discovery
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary

/**
 * Shared iOS discovery + upload-request support, used by BOTH upload tiers — the ≥26.1 PhotoKit
 * adapter ([app.snapsync.ios.upload.IosPhotoKitUploadPlatform]) and the 18–26.0 app-driven URLSession
 * adapter. It owns the parts of upload execution that are identical across the tiers: the
 * persistent-change-token library walk ([discover]), the PUT request builder ([buildRequest], with
 * HTTP/3 disabled), and change-token (un)archiving. Only the *job lifecycle*
 * (create/fetch/retry/acknowledge) differs between the tiers and stays in each adapter.
 *
 * Decision-free platform code (faked in the harness); not unit-tested — the change-token subsystem is
 * exercised on device/simulator, with a smoke test confirming enumeration is callable.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDiscovery(
    private val log: Logger,
    private val enumerator: GalleryResourceEnumerator,
) {
    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    /**
     * Enumerate the resources changed since [sinceToken] (null / unarchivable / expired → whole
     * library), plus the cursor to persist once the cycle drains. Identical for both upload tiers.
     */
    suspend fun discover(sinceToken: ByteArray?): Discovery {
        val token = sinceToken?.let(::unarchiveToken)
        val changes = token?.let { library.fetchPersistentChangesSinceToken(it, error = null) }
        if (token == null || changes == null) {
            // Full enumeration: `resources` is every current resource key — the live set the cycle
            // reconciles against. No change feed, so no incremental removals.
            return Discovery(
                resources = enumerator.enumerate(),
                nextToken = archiveToken(library.currentChangeToken),
                fullEnumeration = true,
            )
        }
        // Incremental: derive changed assets to (re)upload and removed assets to prune. Removed ids
        // are normalized `/`→`_` so they match the `<localId>-…` key scheme.
        val identifiers = linkedSetOf<String>()
        val removed = linkedSetOf<String>()
        changes.enumerateChangesWithBlock { change, _ ->
            val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, error = null)
                ?: return@enumerateChangesWithBlock
            details.insertedLocalIdentifiers().forEach { identifiers.add(it as String) }
            details.updatedLocalIdentifiers().forEach { identifiers.add(it as String) }
            details.deletedLocalIdentifiers().forEach { removed.add((it as String).replace('/', '_')) }
        }
        return Discovery(
            resources = enumerator.resources(identifiers.toList()),
            nextToken = archiveToken(library.currentChangeToken),
            removedAssetIds = removed.toList(),
        )
    }

    /** Build the edge PUT request for [request] — HTTP/3 disabled (see below). Shared by both tiers. */
    fun buildRequest(url: NSURL, request: UploadRequest): NSMutableURLRequest {
        val urlRequest = NSMutableURLRequest(uRL = url)
        urlRequest.setHTTPMethod("PUT")
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
        // Force HTTP/2-over-TCP: the system otherwise performs the upload over HTTP/3 (QUIC) against
        // the public edge endpoint, but that QUIC connection never completes on real networks (it
        // hangs ~11s, cancels, and retries forever — nothing uploads), with no TCP fallback. The edge
        // only offers h2/http1.1 anyway. Opting the request out of HTTP/3 keeps uploads on TCP.
        urlRequest.setAssumesHTTP3Capable(false)
        return urlRequest
    }

    // Token (un)archiving is best-effort efficiency only: any failure degrades to a full
    // re-enumeration (null token), which the ledger makes harmless — it must never fail the cycle.
    private fun archiveToken(token: PHPersistentChangeToken): ByteArray =
        runCatching {
            NSKeyedArchiver.archivedDataWithRootObject(token, requiringSecureCoding = true, error = null)?.toByteArray()
        }.onFailure { log.w(it) { "archiveToken failed — cursor will not advance this cycle" } }
            .getOrNull() ?: ByteArray(0)

    private fun unarchiveToken(bytes: ByteArray): PHPersistentChangeToken? =
        runCatching {
            NSKeyedUnarchiver.unarchivedObjectOfClass(PHPersistentChangeToken, bytes.toNSData(), error = null)
                as? PHPersistentChangeToken
        }.onFailure { log.w(it) { "unarchiveToken failed — re-enumerating from scratch" } }
            .getOrNull()
}
