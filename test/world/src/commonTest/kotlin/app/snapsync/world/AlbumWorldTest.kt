package app.snapsync.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Event-album placement over the REAL upload cycle (capability `event-album`): a `saveToAlbum`
 * membership's genuinely-completed own photos are added to the event album at cycle completion, in the
 * process that ran the cycle. Asserted through the recording [FakeAlbumManager] — no PhotoKit.
 */
class AlbumWorldTest {

    @Test
    fun completed_uploads_are_added_to_the_album_when_opted_in() = worldTest {
        val w = World(this)
        w.provision("E", name = "Party", saveToAlbum = true)
        // The app is the sole creator; the world stands in for that by ensuring the album up front.
        val albumId = w.albumCoordinator.ensureAlbum("E", "Party")!!
        w.addOwnAsset("A")
        w.addOwnAsset("B")

        w.runUploadCycle()                       // create jobs
        w.platform.completeJob("A-primary.jpg")
        w.platform.completeJob("B-primary.jpg")
        w.runUploadCycle()                       // ack → COMPLETED → placeInAlbum fires

        // Both completed assets landed in the event album (raw ids recovered by the cycle's reversal).
        assertEquals(setOf("A", "B"), w.albumManager.assetsIn(albumId).toSet())
    }

    @Test
    fun no_album_placement_when_opted_out() = worldTest {
        val w = World(this)
        w.provision("E", name = "Party", saveToAlbum = false)
        w.albumCoordinator.ensureAlbum("E", "Party") // even if an album existed, opt-out places nothing
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
        val first = w.albumCoordinator.ensureAlbum("E", "Party")!!
        // A second ensure (e.g. a re-join with the box checked) reuses the stored album, no duplicate.
        val second = w.albumCoordinator.ensureAlbum("E", "Party")!!
        assertEquals(first, second)
        assertEquals(1, w.albumManager.created.size)
    }
}
