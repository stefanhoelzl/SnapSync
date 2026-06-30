package app.snapsync.status

import app.snapsync.engine.Resource
import app.snapsync.gallery.InMemoryGalleryResourceEnumerator
import app.snapsync.rejoin.DeviceFilesSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CompletedAssetsSourceTest {

    private val deviceId = "dev"

    private fun resource(filename: String, assetId: String) =
        Resource(filename, assetId, "image/jpeg", emptyMap(), Unit)

    private class FakeDeviceFiles : DeviceFilesSource {
        var result: Result<List<String>> = Result.success(emptyList())
        var calls = 0
            private set

        override suspend fun list(deviceId: String): Result<List<String>> {
            calls++
            return result
        }
    }

    @Test
    fun `an asset is complete only when all its expected resources are present`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(
                resource("A-primary.jpg", "A"),
                resource("A-live.mov", "A"), // A is a Live Photo: needs both
                resource("B-primary.jpg", "B"),
            ),
        )
        val files = FakeDeviceFiles()
        val source = OwnDeviceCompletedAssetsSource(enumerator, files, deviceId)

        // A fully present + B present → both complete.
        files.result = Result.success(listOf("A-primary.jpg", "A-live.mov", "B-primary.jpg"))
        source.refresh()
        assertEquals(setOf("A", "B"), source.completed.value)

        // A's live missing → A drops to incomplete; B still complete.
        files.result = Result.success(listOf("A-primary.jpg", "B-primary.jpg"))
        source.refresh()
        assertEquals(setOf("B"), source.completed.value)
    }

    @Test
    fun `a failed listing keeps the last value`() = runTest {
        val enumerator = InMemoryGalleryResourceEnumerator(listOf(resource("A-primary.jpg", "A")))
        val files = FakeDeviceFiles()
        val source = OwnDeviceCompletedAssetsSource(enumerator, files, deviceId)

        files.result = Result.success(listOf("A-primary.jpg"))
        source.refresh()
        assertEquals(setOf("A"), source.completed.value)

        files.result = Result.failure(RuntimeException("network down"))
        source.refresh()
        assertEquals(setOf("A"), source.completed.value, "a failed refresh retains the previous value")
    }

    @Test
    fun `downloaded suppressed assets are excluded from the upload total and completed`() = runTest {
        // B is a foreign photo this device downloaded + imported (suppressed). It is in the library
        // (enumerated) but must NOT count toward the upload universe — else progress pegs below 100%.
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(
                resource("A-primary.jpg", "A"), // own
                resource("B-primary.jpg", "B"), // downloaded foreign (suppressed)
            ),
        )
        val files = FakeDeviceFiles().apply { result = Result.success(listOf("A-primary.jpg")) }
        val source = OwnDeviceCompletedAssetsSource(enumerator, files, deviceId, suppressedLocalIds = { setOf("B") })

        source.refresh()

        assertEquals(1, source.size.value, "total counts only own assets (A), not the downloaded B")
        assertEquals(setOf("A"), source.completed.value, "B is excluded from completed too")
    }

    @Test
    fun `the list is fetched for this device`() = runTest {
        val files = FakeDeviceFiles()
        OwnDeviceCompletedAssetsSource(InMemoryGalleryResourceEnumerator(), files, deviceId).refresh()
        assertEquals(1, files.calls)
    }
}
