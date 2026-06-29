package app.snapsync.status

import app.snapsync.rejoin.EventFilesSource
import app.snapsync.rejoin.RemoteAsset
import app.snapsync.rejoin.RemoteResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CompletedAssetsSourceTest {

    private fun asset(id: String) = RemoteAsset(id, listOf(RemoteResource("$id.jpg")))

    @Test
    fun `refresh re-reads the complete-asset set`() = runTest {
        val files = FakeEventFiles()
        val source = FilesCompletedAssetsSource(files) { "evt" }

        files.result = Result.success(listOf(asset("A"), asset("B")))
        source.refresh()
        assertEquals(setOf("A", "B"), source.completed.value)

        files.result = Result.success(listOf(asset("A"), asset("B"), asset("C")))
        source.refresh()
        assertEquals(setOf("A", "B", "C"), source.completed.value)
    }

    @Test
    fun `a failed listing keeps the last value`() = runTest {
        val files = FakeEventFiles()
        val source = FilesCompletedAssetsSource(files) { "evt" }

        files.result = Result.success(listOf(asset("A")))
        source.refresh()
        assertEquals(setOf("A"), source.completed.value)

        files.result = Result.failure(RuntimeException("network down"))
        source.refresh()
        assertEquals(setOf("A"), source.completed.value, "a failed refresh retains the previous value")
    }

    @Test
    fun `no configured event is a no-op`() = runTest {
        val files = FakeEventFiles()
        val source = FilesCompletedAssetsSource(files) { null }

        source.refresh()
        assertEquals(emptySet(), source.completed.value)
        assertEquals(0, files.calls, "no listing fetched without an event id")
    }
}

private class FakeEventFiles : EventFilesSource {
    var result: Result<List<RemoteAsset>> = Result.success(emptyList())
    var calls = 0
        private set

    override suspend fun list(eventId: String): Result<List<RemoteAsset>> {
        calls++
        return result
    }
}
