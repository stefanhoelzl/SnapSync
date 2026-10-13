package app.snapsync.feature.download

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.feature.support.testShared
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetRef
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UploadedManifest
import app.snapsync.model.encodeToJson
import app.snapsync.ports.DbOpen
import app.snapsync.services.downloads.DOWNLOADS_DB_NAME
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreDownloadStatusSourceTest {

    /**
     * The source as a receiving member who published [ownPublished] photos into E1 sees it — none by default, and an
     * undecodable record for `null`.
     */
    private fun source(
        store: DownloadService,
        currentEvent: () -> EventId?,
        receives: () -> Boolean? = { true },
        ownPublished: Int? = 0,
    ): StoreDownloadStatusSource {
        val manifest = DeviceManifestService(testShared())
        when (ownPublished) {
            0 -> Unit
            null -> manifest.saveLastUploaded(UploadedManifest(testEventId("E1"), 1, "not a manifest"))
            else -> manifest.saveLastUploaded(
                UploadedManifest(
                    testEventId("E1"),
                    1,
                    DeviceManifest(
                        testDeviceId("ME"),
                        (1..ownPublished).map {
                            DeviceManifestAsset(
                                AssetId("OWN-$it"),
                                "2026-06-30T10:00:00Z",
                                listOf(ManifestResource(ResourceRole.PRIMARY, "image/jpeg", "own-$it", "OWN.JPG")),
                            )
                        },
                    ).encodeToJson(),
                ),
            )
        }
        return StoreDownloadStatusSource(store, currentEvent, receives, manifest)
    }

    /**
     * The seed is the whole point and is not tidiness: the joined screen's direction arrows are **conjunctive** —
     * "In sync" shows exactly when both are hidden — and the download arrow hides when `downloaded >= total`. A
     * placeholder `(0, 0)` marked *read* satisfies that on its own (the shape behind `SNAPSYNC-14`/`SNAPSYNC-16`).
     */
    @Test
    fun before_a_refresh_it_is_un_read_rather_than_a_counted_empty_union() {
        val source = source(DownloadService(inMemoryDatabases()), currentEvent = { testEventId("E1") })

        assertEquals(DownloadProgress.UNREAD, source.progress.value)
    }

    @Test
    fun with_no_membership_a_refresh_reads_nothing_and_it_stays_un_read() = runTest {
        val source = source(DownloadService(inMemoryDatabases()), currentEvent = { null })

        source.refresh()

        assertEquals(DownloadProgress.UNREAD, source.progress.value)
    }

    @Test
    fun a_store_that_cannot_be_read_never_turns_un_read_into_a_counted_zero() = runTest {
        // Keep-last-good: a failed read must neither throw (it would cancel the foreground's siblings) nor publish
        // a placeholder the screen would take for "nothing to receive".
        val unreadable = DownloadService(
            inMemoryDatabases(mapOf(DOWNLOADS_DB_NAME to DbOpen.Failed("store unreadable"))),
        )
        val source = source(unreadable, currentEvent = { testEventId("E1") })

        source.refresh()

        assertEquals(DownloadProgress.UNREAD, source.progress.value)
    }

    private fun planned(id: String) =
        listOf(PlannedResource("$id-primary.jpg", "u", "primary", "image/jpeg", "$id.JPG"))

    @Test
    fun reports_imported_of_total_foreign() = runTest {
        val store = DownloadService(inMemoryDatabases())
        val source = source(store, currentEvent = { testEventId("E1") })

        val x = AssetRef(testDeviceId("A"), AssetId("X"))
        val y = AssetRef(testDeviceId("A"), AssetId("Y"))
        store.planAll(
            listOf(
                PlannedAsset(x, "2026-06-30T10:00:00Z", planned("X")),
                PlannedAsset(y, "2026-06-30T10:00:00Z", planned("Y")),
            ),
            eventId = testEventId("E1"),
            members = listOf(x, y),
        )
        source.refresh()
        assertEquals(0, source.progress.value.downloaded)
        assertEquals(2, source.progress.value.total)

        store.markStaged(AssetRef(testDeviceId("A"), AssetId("X")), "X-primary.jpg", "/x")
        store.markImported(AssetRef(testDeviceId("A"), AssetId("X")), AssetId("LOCAL-X"))
        source.refresh()
        assertEquals(1, source.progress.value.downloaded) // downloaded 1 of 2
        assertEquals(2, source.progress.value.total)
    }

    @Test
    fun an_earlier_events_imported_photos_are_not_received_for_the_current_one() = runTest {
        // Measured on a device: a joined event whose union was EMPTY read "2418 received" — every foreign photo the
        // device had imported for earlier events, kept as suppression handles after each leave.
        val store = DownloadService(inMemoryDatabases())
        val old = AssetRef(testDeviceId("B"), AssetId("OLD"))
        store.planAll(
            listOf(PlannedAsset(old, "2026-06-01T10:00:00Z", planned("OLD"))),
            eventId = testEventId("EARLIER"),
            members = listOf(old),
        )
        store.markStaged(old, "OLD-primary.jpg", "/old")
        store.markImported(old, AssetId("LOCAL-OLD"))

        val source = source(store, currentEvent = { testEventId("CURRENT") })
        store.planAll(emptyList(), eventId = testEventId("CURRENT"), members = emptyList()) // its union is empty
        source.refresh()
        assertEquals(0, source.progress.value.downloaded)
        assertEquals(0, source.progress.value.total)
        assertEquals(setOf(AssetId("LOCAL-OLD")), store.suppressedLocalIds(), "the handle itself is kept")
    }

    // ---- the others' declared photos (change `count-declared-photos`) ----

    /** [count] foreign photos of E1 planned from member B, the union read having answered [declared] at position 1. */
    private suspend fun DownloadService.offered(count: Int, declared: Long?) {
        val refs = (1..count).map { AssetRef(testDeviceId("B"), AssetId("P$it")) }
        planAll(
            refs.map { PlannedAsset(it, "2026-06-30T10:00:00Z", planned(it.sourceAssetId.value)) },
            eventId = testEventId("E1"),
            members = refs,
            cursor = 1,
            declared = declared,
        )
    }

    @Test
    fun photos_still_uploading_on_another_phone_are_awaited() = runTest {
        // The sharer's 200, 7 of them offered here: 6 of 200 received, not 6 of 7.
        val store = DownloadService(inMemoryDatabases())
        store.offered(7, declared = 200)
        (1..6).forEach { store.markImported(AssetRef(testDeviceId("B"), AssetId("P$it")), AssetId("LOCAL-$it")) }
        val source = source(store, currentEvent = { testEventId("E1") })

        source.refresh()

        val progress = source.progress.value
        assertEquals(6, progress.downloaded)
        assertEquals(7, progress.total)
        assertEquals(193, progress.awaited)
    }

    @Test
    fun the_devices_own_published_declaration_is_not_awaited() = runTest {
        val store = DownloadService(inMemoryDatabases())
        store.offered(2, declared = 15)
        val source = source(store, currentEvent = { testEventId("E1") }, ownPublished = 10)

        source.refresh()

        assertEquals(3, source.progress.value.awaited, "15 declared − 10 own − 2 offered")
    }

    @Test
    fun a_member_who_does_not_receive_awaits_nothing() = runTest {
        val store = DownloadService(inMemoryDatabases())
        store.offered(1, declared = 50)
        listOf(false, null).forEach { answer ->
            val source = source(store, currentEvent = { testEventId("E1") }, receives = { answer })
            source.refresh()
            assertEquals(0, source.progress.value.awaited, "receives = $answer")
        }
    }

    @Test
    fun a_photo_given_up_on_is_never_also_awaited() = runTest {
        val store = DownloadService(inMemoryDatabases())
        store.offered(2, declared = 2)
        assertTrue(store.settleUnimportable(AssetRef(testDeviceId("B"), AssetId("P1"))))
        val source = source(store, currentEvent = { testEventId("E1") })

        source.refresh()

        assertEquals(1, source.progress.value.total, "it left the denominator")
        assertEquals(0, source.progress.value.awaited, "and the declared total already holds it")
    }

    @Test
    fun a_short_subtraction_is_clamped_and_an_unknown_one_awaits_nothing() = runTest {
        val store = DownloadService(inMemoryDatabases())
        store.offered(5, declared = 4)
        source(store, currentEvent = { testEventId("E1") }).let {
            it.refresh()
            assertEquals(0, it.progress.value.awaited, "a publish crossing the read never goes negative")
        }
        source(store, currentEvent = { testEventId("E1") }, ownPublished = null).let {
            it.refresh()
            assertEquals(0, it.progress.value.awaited, "an unreadable own count is no guess")
        }
        store.offered(5, declared = null)
        source(store, currentEvent = { testEventId("E1") }).let {
            it.refresh()
            assertEquals(0, it.progress.value.awaited, "a backend without the total reads as before")
        }
    }
}
