package app.snapsync.feature.membership

import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryGallery
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.model.RawAsset
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.SelectionScope
import app.snapsync.model.UnionAsset
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.MarkedPhotoLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The join-time adoption (capability `receiving-photos`): a reinstalled app recognises the received photos still in
 * the library by their SnapSync mark, so they are neither downloaded again nor shared back.
 */
class ReceivedPhotoAdoptionTest {

    private val me = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val date = "2026-06-05T12:00:00Z"
    private val cfg = EventConfig(
        eventId = "EVENT-1",
        name = "Trip",
        minPhotoDate = CaptureCutoff(CaptureDate("2026-06-01T00:00:00Z")),
        startsAt = EventStart(CaptureDate("2026-06-01T00:00:00Z")),
        endsAt = EventEnd(CaptureDate("2026-06-10T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(CaptureDate("2026-06-10T00:00:00Z")),
    )

    private fun ref(asset: String, device: String = other) = AssetRef(device, AssetId(asset))

    private fun unionOf(vararg refs: AssetRef) = refs.map { UnionAsset(it.sourceDeviceId, it.sourceAssetId, date, emptyList()) }

    /** A photo an earlier install received for [ref]: named with its mark. */
    private fun received(localId: String, ref: AssetRef): RawAsset =
        LibraryAssets.photo(localId, creationDate = date, resources = listOf(LibraryAssets.primaryResource(ReceivedPhotoName.mark("IMG.HEIC", "k.heic", ref))))

    private class World(union: Result<List<UnionAsset>>, library: List<RawAsset>) {
        val store = DownloadService(inMemoryDatabases())
        val unionSource = EventUnionSource { union }
        val lookup = MarkedPhotoLookup(inMemoryGallery(MutableStateFlow(library))) { SelectionScope.Unrestricted }
    }

    private fun World.adoption(me: String) = ReceivedPhotoAdoption(unionSource, store, lookup, testIdentity(me))

    @Test
    fun a_marked_photo_of_the_union_is_adopted() = runTest {
        val world = World(Result.success(unionOf(ref("A"), ref("B"))), listOf(received("L-A", ref("A")), LibraryAssets.photo("L-OWN", creationDate = date)))
        world.adoption(me).adopt(cfg)

        assertEquals(setOf(ref("A")), world.store.settledAmong(listOf(ref("A"), ref("B"))), "only the photo still in the library")
        assertEquals(setOf(AssetId("L-A")), world.store.suppressedLocalIds(), "and it is never shared back")
        assertEquals(1, world.store.counts("EVENT-1").imported, "it counts as received for this event")
    }

    @Test
    fun a_token_of_no_union_ref_is_ignored() = runTest {
        val world = World(Result.success(unionOf(ref("A"))), listOf(received("L-X", ref("NOT-IN-THIS-EVENT"))))
        world.adoption(me).adopt(cfg)
        assertTrue(world.store.suppressedLocalIds().isEmpty())
    }

    @Test
    fun an_own_ref_is_never_adopted() = runTest {
        val own = ref("MINE", device = me)
        val world = World(Result.success(unionOf(own)), listOf(received("L-MINE", own)))
        world.adoption(me).adopt(cfg)
        assertFalse(world.store.isSettled(own))
        assertTrue(world.store.suppressedLocalIds().isEmpty())
    }

    @Test
    fun a_failed_union_adopts_nothing_and_does_not_throw() = runTest {
        val world = World(Result.failure(IllegalStateException("offline")), listOf(received("L-A", ref("A"))))
        world.adoption(me).adopt(cfg)
        assertTrue(world.store.suppressedLocalIds().isEmpty())
    }

    @Test
    fun an_unreadable_identity_does_not_throw() = runTest {
        val world = World(Result.success(unionOf(ref("A"))), listOf(received("L-A", ref("A"))))
        ReceivedPhotoAdoption(world.unionSource, world.store, world.lookup, unreadableIdentity()).adopt(cfg)
        assertTrue(world.store.suppressedLocalIds().isEmpty())
    }

    @Test
    fun a_row_the_store_already_holds_is_left_untouched() = runTest {
        val world = World(Result.success(unionOf(ref("A"))), listOf(received("L-A", ref("A"))))
        world.store.plan(ref("A"), date, listOf(PlannedResource("A-primary.heic", "https://e/a", "primary", "image/heic", "IMG.HEIC")))
        world.adoption(me).adopt(cfg)
        assertFalse(world.store.isSettled(ref("A")), "this install's own planned row still downloads")
        assertTrue(world.store.suppressedLocalIds().isEmpty())
    }
}
