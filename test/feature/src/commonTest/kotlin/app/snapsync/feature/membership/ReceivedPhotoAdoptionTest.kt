package app.snapsync.feature.membership

import app.snapsync.model.UnionTrigger
import app.snapsync.model.UnionPage
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
import app.snapsync.model.GalleryAccess
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

    private class World(
        union: Result<List<UnionAsset>>,
        library: List<RawAsset>,
        val grant: MutableStateFlow<GalleryAccess> = MutableStateFlow(GalleryAccess.GRANTED),
    ) {
        val store = DownloadService(inMemoryDatabases())
        var unionReads = 0
        val unionTriggers = mutableListOf<Pair<Long?, UnionTrigger>>()
        val unionSource = EventUnionSource { _, cursor, trigger ->
            unionReads++
            unionTriggers += cursor to trigger
            union.map { UnionPage(it, 0) }
        }
        val lookup = MarkedPhotoLookup(inMemoryGallery(MutableStateFlow(library), grant)) { SelectionScope.Unrestricted }
    }

    private fun World.adoption(me: String) = ReceivedPhotoAdoption(unionSource, store, lookup, store::adoptAll, testIdentity(me))

    @Test
    fun the_adoption_reads_the_whole_union_and_says_why() = runTest {
        // Decision record `changes/incremental-union`, D6: a mark is recognised only against every ref the event serves.
        val world = World(Result.success(unionOf(ref("A"))), emptyList())
        val adoption = world.adoption(me)
        adoption.adopt(cfg)
        adoption.adopt(cfg)
        assertEquals(listOf<Pair<Long?, UnionTrigger>>(null to UnionTrigger.JOIN, null to UnionTrigger.JOIN), world.unionTriggers)
        val fresh = World(Result.success(unionOf(ref("A"))), emptyList())
        fresh.adoption(me).ensureAdopted(cfg)
        assertEquals(listOf<Pair<Long?, UnionTrigger>>(null to UnionTrigger.GRANT), fresh.unionTriggers)
    }

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
        ReceivedPhotoAdoption(world.unionSource, world.store, world.lookup, world.store::adoptAll, unreadableIdentity()).adopt(cfg)
        assertTrue(world.store.suppressedLocalIds().isEmpty())
    }

    @Test
    fun ensuring_keeps_asking_while_the_library_is_unreadable_then_settles_once() = runTest {
        // A reinstall's rejoin: the dialog is open at the join, so the first passes read nothing.
        val world = World(Result.success(unionOf(ref("A"))), listOf(received("L-A", ref("A"))), MutableStateFlow(GalleryAccess.NOT_DETERMINED))
        val adoption = world.adoption(me)
        adoption.adopt(cfg)
        adoption.ensureAdopted(cfg)
        assertTrue(world.store.suppressedLocalIds().isEmpty(), "nothing readable yet")

        world.grant.value = GalleryAccess.GRANTED
        adoption.ensureAdopted(cfg)
        assertEquals(setOf(AssetId("L-A")), world.store.suppressedLocalIds(), "the first readable pass adopts")

        val reads = world.unionReads
        adoption.ensureAdopted(cfg)
        assertEquals(reads, world.unionReads, "settled: every later drain is told at once")
    }

    @Test
    fun an_unreachable_union_settles_rather_than_holding_every_import() = runTest {
        val world = World(Result.failure(IllegalStateException("offline")), emptyList())
        val adoption = world.adoption(me)
        adoption.ensureAdopted(cfg)
        adoption.ensureAdopted(cfg)
        assertEquals(1, world.unionReads)
    }

    @Test
    fun a_ref_planned_while_the_grant_was_pending_is_adopted_not_imported_again() = runTest {
        // A reinstall's rejoin plans its downloads with the photo-access dialog still open; the grant then adopts.
        val world = World(Result.success(unionOf(ref("A"))), listOf(received("L-A", ref("A"))))
        world.store.plan(ref("A"), date, listOf(PlannedResource("A-primary.heic", "https://e/a", "primary", "image/heic", "IMG.HEIC")))
        world.adoption(me).adopt(cfg)
        assertTrue(world.store.isSettled(ref("A")), "the library already holds it")
        assertEquals(setOf(AssetId("L-A")), world.store.suppressedLocalIds())
    }

    @Test
    fun a_ref_an_import_created_an_asset_for_is_left_untouched() = runTest {
        val world = World(Result.success(unionOf(ref("A"))), listOf(received("L-A", ref("A"))))
        world.store.plan(ref("A"), date, listOf(PlannedResource("A-primary.heic", "https://e/a", "primary", "image/heic", "IMG.HEIC")))
        world.store.recordCreatedLocalId(ref("A"), AssetId("L-NEW"))
        world.adoption(me).adopt(cfg)
        assertFalse(world.store.isSettled(ref("A")), "its import's own confirmation settles it")
        assertEquals(setOf(AssetId("L-NEW")), world.store.suppressedLocalIds())
    }
}
