package app.snapsync.upload

import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import app.snapsync.testids.testEventId

/**
 * Test seam double: records every [provide] invocation and its returned request, and can be
 * scripted to throw. Minting is deterministic from the resource (url derives from the
 * filename), satisfying the provider contract.
 */
class RecordingUploadRequestProvider : UploadRequestProvider {
    override val event = testEventId("E")

    val invocations = mutableListOf<Resource>()

    /** The resources minted through [provideForRetry] — a subset of [invocations], in order. */
    val retryInvocations = mutableListOf<Resource>()
    val returned = mutableListOf<UploadRequest>()
    var nextFailure: Throwable? = null

    override suspend fun provide(resource: Resource): UploadRequest {
        invocations += resource
        nextFailure?.let { failure ->
            nextFailure = null
            throw failure
        }
        return UploadRequest(
            url = "https://bucket.test.invalid/photos/${resource.filename}",
            headers = mapOf("Content-Type" to resource.contentType) +
                resource.metadata.mapKeys { (key, _) -> "x-amz-meta-$key" },
            resource = resource,
            event = event,
        ).also { returned += it }
    }

    override suspend fun provideForRetry(resource: Resource): UploadRequest {
        retryInvocations += resource
        return provide(resource)
    }
}
