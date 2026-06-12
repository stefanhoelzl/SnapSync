package app.snapsync.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SyncEngineTest {

    private val provider = RecordingUploadRequestProvider()
    private val engine = SyncEngine(provider)

    private fun resource(filename: String = "cloud-1-ios.photo.heic") = Resource(
        filename = filename,
        contentType = "image/heic",
        metadata = mapOf("asset-id" to "cloud-1", "created" to "2026-06-12T10:00:00Z"),
        data = byteArrayOf(1, 2, 3),
    )

    @Test
    fun `resource change yields one first-attempt job`() = runTest {
        val resource = resource()

        val job = engine.handle(SyncEvent.ResourceChanged(resource))

        assertEquals(0, job.attempt)
    }

    @Test
    fun `provider is invoked exactly once with the platform's resource instance`() = runTest {
        val resource = resource()

        engine.handle(SyncEvent.ResourceChanged(resource))

        assertEquals(1, provider.invocations.size)
        assertSame(resource, provider.invocations.single())
    }

    @Test
    fun `job carries the provider's request unmodified`() = runTest {
        val job = engine.handle(SyncEvent.ResourceChanged(resource()))

        assertSame(provider.returned.single(), job.request)
    }

    @Test
    fun `resource instance round-trips onto the job's request`() = runTest {
        val resource = resource()

        val job = engine.handle(SyncEvent.ResourceChanged(resource))

        assertSame(resource, job.request.resource)
    }

    @Test
    fun `failed upload yields a retry with incremented attempt and a fresh request`() = runTest {
        val resource = resource()
        val failed = engine.handle(SyncEvent.ResourceChanged(resource))

        val retry = engine.handle(SyncEvent.UploadFailed(failed, UploadError.Http(403)))

        assertEquals(1, retry.attempt)
        assertNotSame(failed.request, retry.request)
        assertSame(resource, retry.request.resource)
        assertSame(resource, provider.invocations.last())
    }

    @Test
    fun `every error kind retries`() = runTest {
        val errors = listOf(
            UploadError.Network,
            UploadError.Http(500),
            UploadError.Cancelled,
            UploadError.Unknown("boom"),
        )
        var job = engine.handle(SyncEvent.ResourceChanged(resource()))

        for ((index, error) in errors.withIndex()) {
            job = engine.handle(SyncEvent.UploadFailed(job, error))
            assertEquals(index + 1, job.attempt)
        }
    }

    @Test
    fun `provider failure propagates unswallowed and the event can be re-handled`() = runTest {
        val event = SyncEvent.ResourceChanged(resource())
        val failure = IllegalStateException("mint failed")
        provider.nextFailure = failure

        val thrown = assertFailsWith<IllegalStateException> { engine.handle(event) }
        assertSame(failure, thrown)

        val job = engine.handle(event)
        assertEquals(0, job.attempt)
    }

    @Test
    fun `concurrent handle calls return exactly their own resources' jobs`() = runTest {
        val first = resource("cloud-1-ios.photo.heic")
        val second = resource("cloud-2-ios.pairedVideo.mov")

        val firstJob = async { engine.handle(SyncEvent.ResourceChanged(first)) }
        val secondJob = async { engine.handle(SyncEvent.ResourceChanged(second)) }

        assertSame(first, firstJob.await().request.resource)
        assertSame(second, secondJob.await().request.resource)
        assertEquals(2, provider.invocations.size)
    }
}
