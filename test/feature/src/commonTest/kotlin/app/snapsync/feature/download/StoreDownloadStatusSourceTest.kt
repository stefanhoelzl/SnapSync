package app.snapsync.feature.download

import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.services.downloads.DownloadService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreDownloadStatusSourceTest {

    /**
     * The seed is the whole point and is not tidiness: the joined screen's direction arrows are **conjunctive** —
     * "In sync" shows exactly when both are hidden — and the download arrow hides when `downloaded >= total`. A
     * placeholder `(0, 0)` marked *read* satisfies that on its own (the shape behind `SNAPSYNC-14`/`SNAPSYNC-16`).
     */
    @Test
    fun before_a_refresh_it_is_un_read_rather_than_a_counted_empty_union() {
        val source = StoreDownloadStatusSource(DownloadService(inMemoryDatabases()), currentEvent = { "E1" })

        assertEquals(DownloadProgress.UNREAD, source.progress.value)
    }

    private fun planned(id: String) =
        listOf(PlannedResource("$id-primary.jpg", "u", "primary", "image/jpeg", "$id.JPG"))

    @Test
    fun reports_imported_of_total_foreign() = runTest {
        val store = DownloadService(inMemoryDatabases())
        val source = StoreDownloadStatusSource(store, currentEvent = { "E1" })

        val x = AssetRef("A", AssetId("X"))
        val y = AssetRef("A", AssetId("Y"))
        store.planAll(
            listOf(
                PlannedAsset(x, "2026-06-30T10:00:00Z", planned("X")),
                PlannedAsset(y, "2026-06-30T10:00:00Z", planned("Y")),
            ),
            eventId = "E1",
            members = listOf(x, y),
        )
        source.refresh()
        assertEquals(0, source.progress.value.downloaded)
        assertEquals(2, source.progress.value.total)

        store.markStaged(AssetRef("A", AssetId("X")), "X-primary.jpg", "/x")
        store.markImported(AssetRef("A", AssetId("X")), AssetId("LOCAL-X"))
        source.refresh()
        assertEquals(1, source.progress.value.downloaded) // downloaded 1 of 2
        assertEquals(2, source.progress.value.total)
    }

    @Test
    fun an_earlier_events_imported_photos_are_not_received_for_the_current_one() = runTest {
        // Measured on a device: a joined event whose union was EMPTY read "2418 received" — every foreign photo the
        // device had imported for earlier events, kept as suppression handles after each leave.
        val store = DownloadService(inMemoryDatabases())
        val old = AssetRef("B", AssetId("OLD"))
        store.planAll(
            listOf(PlannedAsset(old, "2026-06-01T10:00:00Z", planned("OLD"))),
            eventId = "EARLIER",
            members = listOf(old),
        )
        store.markStaged(old, "OLD-primary.jpg", "/old")
        store.markImported(old, AssetId("LOCAL-OLD"))

        val source = StoreDownloadStatusSource(store, currentEvent = { "CURRENT" })
        store.planAll(emptyList(), eventId = "CURRENT", members = emptyList()) // its union is empty
        source.refresh()
        assertEquals(0, source.progress.value.downloaded)
        assertEquals(0, source.progress.value.total)
        assertEquals(setOf(AssetId("LOCAL-OLD")), store.suppressedLocalIds(), "the handle itself is kept")
    }
}
