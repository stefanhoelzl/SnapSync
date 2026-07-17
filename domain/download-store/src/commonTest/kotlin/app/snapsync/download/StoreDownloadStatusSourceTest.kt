package app.snapsync.download

import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.ports.AssetRef
import app.snapsync.downloadstore.InMemoryDownloadStore
import app.snapsync.ports.PlannedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class StoreDownloadStatusSourceTest {

    private fun planned(id: String) =
        listOf(PlannedResource("$id-primary.jpg", "u", "primary", "image/jpeg", "$id.JPG"))

    @Test
    fun reports_imported_of_total_foreign() = runTest {
        val store = InMemoryDownloadStore()
        val source = StoreDownloadStatusSource(store)

        store.plan(AssetRef("A", "X"), "2026-06-30T10:00:00Z", planned("X"))
        store.plan(AssetRef("A", "Y"), "2026-06-30T10:00:00Z", planned("Y"))
        source.refresh()
        assertEquals(0, source.progress.value.downloaded)
        assertEquals(2, source.progress.value.total)

        store.markStaged(AssetRef("A", "X"), "X-primary.jpg", "/x")
        store.markImported(AssetRef("A", "X"), "LOCAL-X")
        source.refresh()
        assertEquals(1, source.progress.value.downloaded) // downloaded 1 of 2
        assertEquals(2, source.progress.value.total)
    }
}
