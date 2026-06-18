package app.snapsync.ios.upload

import app.snapsync.engine.Resource
import app.snapsync.engine.UploadRequest
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHAssetResourceUploadJobActionAcknowledge
import platform.Photos.PHAssetResourceUploadJobActionRetry
import platform.Photos.PHAssetResourceUploadJobChangeRequest
import platform.Photos.PHCloudIdentifierMapping
import platform.Photos.PHFetchResult
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPhotoLibrary
import platform.Photos.cloudIdentifierMappingsForLocalIdentifiers

/**
 * The PhotoKit implementation of [UploadJobPlatform] — the **only** place that touches PhotoKit, so
 * it stays as dumb as possible: raw enumeration, cloud-id resolution, field extraction, and the
 * system-job create/fetch/acknowledge calls. All decisions live in [UploadCycle]; key layout lives
 * in [uploadKey]. None of this is unit-tested (the cloud-identifier and upload-job subsystems are
 * device-only); it is verified on a real device. A [PhotoKitSmokeTest] only confirms the general
 * enumeration surface is callable on the simulator.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUploadJobPlatform(
    private val store: DiscoveryStore,
    private val log: Logger,
) : UploadJobPlatform {

    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    override suspend fun drainJobs() {
        for (action in listOf(PHAssetResourceUploadJobActionAcknowledge, PHAssetResourceUploadJobActionRetry)) {
            val jobs = PHAssetResourceUploadJob.fetchJobsWithAction(action, options = null)
            var index = 0uL
            while (index < jobs.count) {
                val job = jobs.objectAtIndex(index) as PHAssetResourceUploadJob
                library.performChangesAndWait(
                    changeBlock = {
                        PHAssetResourceUploadJobChangeRequest.changeRequestForUploadJob(job)?.acknowledge()
                    },
                    error = null,
                )
                index++
            }
        }
    }

    override suspend fun discoverResources(): List<Resource> {
        val localIdentifiers = changedLocalIdentifiers()
        if (localIdentifiers.isEmpty()) return emptyList()

        val mappings = library.cloudIdentifierMappingsForLocalIdentifiers(localIdentifiers)
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(localIdentifiers, null)
        val resources = mutableListOf<Resource>()
        var index = 0uL
        while (index < assets.count) {
            val asset = assets.objectAtIndex(index) as PHAsset
            index++
            val cloudId = (mappings[asset.localIdentifier] as? PHCloudIdentifierMapping)
                ?.cloudIdentifier?.stringValue
            if (cloudId == null) {
                // identifierNotFound — never key by the per-device localIdentifier. Skip; the
                // routine full re-enumeration retries once the cloud id resolves.
                log.i { "skipping asset with no resolvable cloud identifier" }
                continue
            }
            val version = (asset.modificationDate?.timeIntervalSince1970 ?: 0.0).toString()
            for (any in PHAssetResource.assetResourcesForAsset(asset)) {
                val resource = any as PHAssetResource
                resources += Resource(
                    filename = uploadKey(cloudId, resource.type, resource.originalFilename),
                    contentType = resource.uniformTypeIdentifier,
                    version = version,
                    metadata = emptyMap(), // asset-layer metadata is out of scope for the dummy slice
                    data = resource,
                )
            }
        }
        return resources
    }

    override suspend fun createJob(request: UploadRequest, resource: Resource) {
        val phResource = resource.data as PHAssetResource
        val url = NSURL.URLWithString(request.url) ?: return
        val urlRequest = NSMutableURLRequest(uRL = url)
        urlRequest.setHTTPMethod("PUT")
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
        library.performChangesAndWait(
            changeBlock = {
                PHAssetResourceUploadJobChangeRequest.creationRequestForJobWithDestination(urlRequest, phResource)
            },
            error = null,
        )
    }

    /** Local identifiers changed since the last cycle (first run / token expiry: the whole library). */
    private fun changedLocalIdentifiers(): List<String> {
        val token = store.loadToken()
        if (token == null) {
            val all = PHAsset.fetchAssetsWithOptions(null)
            store.saveToken(library.currentChangeToken)
            return all.localIdentifiers()
        }
        val changes = library.fetchPersistentChangesSinceToken(token, error = null)
        if (changes == null) {
            val all = PHAsset.fetchAssetsWithOptions(null)
            store.saveToken(library.currentChangeToken)
            return all.localIdentifiers()
        }
        val identifiers = linkedSetOf<String>()
        changes.enumerateChangesWithBlock { change, _ ->
            val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, error = null)
                ?: return@enumerateChangesWithBlock
            details.insertedLocalIdentifiers().forEach { identifiers.add(it as String) }
            details.updatedLocalIdentifiers().forEach { identifiers.add(it as String) }
        }
        store.saveToken(library.currentChangeToken)
        return identifiers.toList()
    }

    private fun PHFetchResult.localIdentifiers(): List<String> {
        val out = ArrayList<String>(count.toInt())
        var index = 0uL
        while (index < count) {
            out.add((objectAtIndex(index) as PHAsset).localIdentifier)
            index++
        }
        return out
    }
}
