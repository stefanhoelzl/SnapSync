package app.snapsync.world

import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.model.Direction
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PermissionStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Event-album placement over the REAL upload cycle (capability `event-album`): a `saveToAlbum`
 * membership's own photos are added to the event album when their upload is first enqueued, in the process
 * that ran the cycle. Asserted through the recording [FakeAlbumManager] — no PhotoKit.
 *
 * And the album's **gather** over the real composition: what an opt-in act places of what the device already
 * holds — a Save, a join through the real `JoinEvent`, an in-process grant — and that none of those acts
 * waits for it.
 */
class AlbumWorldTest {

    @Test
    fun enqueued_uploads_are_added_to_the_album_when_opted_in() = worldTest {
        val w = World(this)
        w.provision("E", name = "Party", saveToAlbum = true)
        // The app is the sole creator; the world stands in for that by ensuring the album up front.
        val albumId = w.albumCoordinator.ensureAlbum("E", "Party", saveToAlbum = true)!!
        w.addOwnAsset("A")
        w.addOwnAsset("B")

        w.runUploadCycle()                       // placeInAlbum fires, then the jobs are created

        // Both assets landed in the event album (raw ids recovered by the cycle's reversal) before either
        // upload finished.
        assertEquals(setOf("A", "B"), w.albumManager.assetsIn(albumId).toSet())

        w.platform.completeJob("A-primary.jpg")
        w.platform.completeJob("B-primary.jpg")
        w.runUploadCycle()                       // ack → COMPLETED, and nothing is placed again

        assertEquals(2, w.albumManager.assetsIn(albumId).size, "a completion places nothing a second time")
    }

    @Test
    fun no_album_placement_when_opted_out() = worldTest {
        val w = World(this)
        w.provision("E", name = "Party", saveToAlbum = false)
        w.albumCoordinator.ensureAlbum("E", "Party", saveToAlbum = true) // even if an album existed, opt-out places nothing
        w.addOwnAsset("A")

        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        assertTrue(w.albumManager.added.isEmpty())
    }

    @Test
    fun reprovisioning_reuses_the_same_album() = worldTest {
        val w = World(this)
        w.provision("E", name = "Party", saveToAlbum = true)
        val first = w.albumCoordinator.ensureAlbum("E", "Party", saveToAlbum = true)!!
        // A second ensure (e.g. a re-join with the box checked) reuses the stored album, no duplicate.
        val second = w.albumCoordinator.ensureAlbum("E", "Party", saveToAlbum = true)!!
        assertEquals(first, second)
        assertEquals(1, w.albumManager.created.size)
    }

    // ---- the gather (capability `event-album`, "Ensuring the album gathers what the device already holds") --

    /** Upload own photo [assetId] to completion — a genuinely already-shared photo. */
    private suspend fun World.shareToCompletion(assetId: String) {
        addOwnAsset(assetId)
        runUploadCycle()
        platform.completeJob("$assetId-primary.jpg")
        runUploadCycle()
    }

    /** Import foreign photo [assetId] of [device] under [eventId]; answers its created local identifier. */
    private suspend fun World.receive(device: String, eventId: String, assetId: String): String {
        val before = downloadStore.suppressedLocalIds()
        addForeignDevice(device, eventId, listOf(World.foreignAsset(assetId)))
        downloadController.reconcile(eventId)
        stageAllDownloads()
        return (downloadStore.suppressedLocalIds() - before).single()
    }

    private suspend fun World.join(eventId: String, saveToAlbum: Boolean): JoinOutcome {
        store.registerEvent(eventId, "Trip", World.DEFAULT_STARTS_AT)
        return joinEvent.join(
            eventId = eventId,
            name = "Trip",
            startsAt = eventStart(World.DEFAULT_STARTS_AT),
            endsAt = eventEnd(World.DEFAULT_FAR_CEILING),
            deletesAt = deletesAt(World.DEFAULT_FAR_CEILING),
            minPhotoDate = captureCutoff(World.DEFAULT_CUTOFF),
            maxPhotoDate = captureCeiling(World.DEFAULT_FAR_CEILING),
            direction = Direction.Both,
            saveToAlbum = saveToAlbum,
        )
    }

    private suspend fun World.albumOn(eventId: String) = userCommands.reconfigure(
        eventId, Direction.Both, captureCutoff(World.DEFAULT_CUTOFF), captureCeiling(World.DEFAULT_FAR_CEILING), true,
    )

    @Test
    fun turning_the_album_on_gathers_what_was_already_shared_and_received() = worldTest {
        val w = World(this)
        w.provision("E", name = "Party", saveToAlbum = false)
        w.shareToCompletion("A")
        val received = w.receive("DEV-PEER", "E", "FQ")
        assertTrue(w.albumManager.added.isEmpty(), "nothing placed while the album is off")

        w.albumOn("E")
        w.core.albumGather.awaitStarted()

        val albumId = w.albumManager.created.single().first
        assertEquals(setOf("A", received), w.albumManager.assetsIn(albumId).toSet())
    }

    @Test
    fun a_photo_carried_over_from_an_earlier_event_is_placed_when_its_loaded_row_is_healed() = worldTest {
        // The leave clears the upload ledger, and the next join loads it back from the device's stored-file
        // listing as BARE rows — which the join's gather cannot admit (no date yet). The walk that dates the
        // row is what places the photo, and it places it without an upload (capability `event-album`).
        val w = World(this)
        w.provision("E1", saveToAlbum = false)
        w.shareToCompletion("A")
        w.leave()
        val jobsBefore = w.platform.created.size
        // The world's provision port only writes the config — the Provision flow's ensure is the app shell's.
        val albumId = w.albumCoordinator.ensureAlbum("E2", "Trip", saveToAlbum = true)!!

        assertEquals(JoinOutcome.Committed, w.join("E2", saveToAlbum = true))
        w.core.albumGather.awaitStarted()
        assertTrue(w.albumManager.assetsIn(albumId).isEmpty(), "the join's gather finds no dated own row yet")

        w.runUploadCycle()

        assertEquals(listOf("A"), w.albumManager.assetsIn(albumId), "the carried-over photo is in the new album")
        assertEquals(jobsBefore, w.platform.created.size, "and no upload job was created to put it there")
    }

    @Test
    fun a_photo_received_in_an_earlier_event_is_not_gathered_into_the_next() = worldTest {
        val w = World(this)
        w.provision("E1", saveToAlbum = false)
        w.receive("DEV-PEER", "E1", "FQ")
        w.leave()
        val albumId = w.albumCoordinator.ensureAlbum("E2", "Trip", saveToAlbum = true)!!

        w.join("E2", saveToAlbum = true)
        w.core.albumGather.awaitStarted()

        assertTrue(w.albumManager.assetsIn(albumId).isEmpty(), "E1's import is not in E2's union")
    }

    @Test
    fun a_failed_union_read_still_gathers_the_own_photos() = worldTest {
        val w = World(this)
        w.provision("E", saveToAlbum = false)
        w.shareToCompletion("A")
        w.receive("DEV-PEER", "E", "FQ")
        w.backendOffline = true

        w.albumOn("E")
        w.core.albumGather.awaitStarted()

        assertEquals(listOf("A"), w.albumManager.assetsIn(w.albumManager.created.single().first))
    }

    private suspend fun World.seedCompletedOwnRow(assetId: String) {
        ledgerBackend.recordUnlessSettled(
            LedgerEntry("$assetId-primary.jpg", assetId, LedgerState.COMPLETED, creationDate = World.DEFAULT_DATE),
        )
    }

    /** Let the permission collector run until it has ensured the album, then let any gather it started finish. */
    private suspend fun World.settleGrant() {
        withTimeout(5_000) { while (albumManager.created.isEmpty()) yield() }
        core.albumGather.awaitStarted()
    }

    @Test
    fun a_cold_launch_already_granted_ensures_the_album_but_gathers_nothing() = worldTest {
        val w = World(this)
        w.provision("E", saveToAlbum = true)
        w.seedCompletedOwnRow("A")

        w.core.installPermissionSubscriptions() // first observation: already GRANTED
        w.settleGrant()

        assertTrue(w.albumManager.added.isEmpty(), "the replayed first observation is not a grant")
    }

    @Test
    fun a_grant_while_running_ensures_the_album_and_gathers() = worldTest {
        val w = World(this)
        w.permission.set(PermissionStatus.NOT_DETERMINED)
        w.provision("E", saveToAlbum = true)
        w.seedCompletedOwnRow("A")
        w.core.installPermissionSubscriptions()
        yield() // the collector observes NOT_DETERMINED first

        w.permission.set(PermissionStatus.GRANTED)
        w.settleGrant()

        assertEquals(listOf("A"), w.albumManager.assetsIn(w.albumManager.created.single().first))
    }

    @Test
    fun neither_a_save_nor_a_join_waits_for_the_gather_it_starts() = worldTest {
        val w = World(this)
        w.provision("E1", saveToAlbum = false)
        w.shareToCompletion("A")
        w.albumManager.holdAdds()

        // If Save awaited the gather, this would never return: every add is held.
        withTimeout(5_000) { w.albumOn("E1") }
        assertTrue(w.albumManager.added.isEmpty(), "the gather is still held")
        w.albumManager.releaseAdds()
        w.core.albumGather.awaitStarted()
        assertTrue(w.albumManager.added.isNotEmpty())

        w.leave()
        // Something the join's gather will place: a photo received in E2, whose import row is permanent
        // across a leave (capability `receiving-photos`) and which E2's union still lists.
        w.provision("E2", saveToAlbum = false)
        w.receive("DEV-PEER", "E2", "FQ")
        w.leave()
        w.albumCoordinator.ensureAlbum("E2", "Trip", saveToAlbum = true)
        w.albumManager.holdAdds()
        val placedBefore = w.albumManager.added.size
        withTimeout(5_000) { w.join("E2", saveToAlbum = true) }
        assertEquals(placedBefore, w.albumManager.added.size, "the join returned with its gather still held")
        w.albumManager.releaseAdds()
        w.core.albumGather.awaitStarted()
        assertTrue(w.albumManager.added.size > placedBefore)
    }
}
