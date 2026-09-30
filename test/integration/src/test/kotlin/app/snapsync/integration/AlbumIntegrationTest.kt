package app.snapsync.integration

import app.snapsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The event album over the real stack (capability `event-album`), read back off the photo library: an opted-in
 * member's own photos are placed when their upload is first enqueued, one album per event however often it is
 * ensured, and neither a Save nor a join waits for the gather it starts.
 *
 * What a gather admits — carried-over and received photos, a failed union read, the first permission emission — is
 * `AlbumGatherTest`'s (`:test:feature`); turning the album on in place is `ReconfigureIntegrationTest`'s.
 */
class AlbumIntegrationTest {

    @Test
    fun enqueued_uploads_are_placed_in_the_album_before_they_finish_and_only_once() = rigTest {
        extensionUploadsOnly()
        createAndJoin("saveToAlbum" to "true", name = "Party")
        addPhoto("A")
        addPhoto("B")

        cycle() // placed, then the jobs are created

        assertEquals(listOf("A", "B"), albums().single().assets.sorted(), "placed before either upload finished")

        completeJobs()
        cycle() // acknowledged: nothing is placed again
        assertEquals(2, albums().single().assets.size, "a completion places nothing a second time")
    }

    @Test
    fun a_phone_without_album_writes_is_offered_no_album_and_gets_none() = rigTest {
        // Capability `event-album`: an Android phone's library cannot hold an event album, so the join screen offers
        // none, and even a join that asks for one creates and fills nothing.
        device("album/writes", "on" to "false")
        device("relaunch")
        create(name = "Party")
        val gate = state().ui.layer as Layer.JoiningEvent
        assertEquals(false, gate.form.albumOffered, "the join screen offers no album")
        assertEquals(false, gate.form.saveToAlbum)

        join("saveToAlbum" to "true")
        addPhoto("A")
        cycle()

        assertTrue(albums().isEmpty(), "no album was created or filled: ${albums()}")
    }

    @Test
    fun rejoining_the_event_reuses_its_album() = rigTest {
        val event = createAndJoin("saveToAlbum" to "true", name = "Party")
        assertEquals(1, albums().size)
        leave()

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join("saveToAlbum" to "true")

        assertEquals(1, albums().size, "the stored album is reused, never duplicated")
    }

    @Test
    fun neither_a_save_nor_a_join_waits_for_the_gather_it_starts() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin("saveToAlbum" to "false")
        addPhoto("A")
        uploadAll()
        device("album/hold-adds")

        // If the Save awaited the gather, it would never land: every add is held.
        user("reconfigure", "saveToAlbum" to "true")
        awaitState { it.joined?.membership?.saveToAlbum == true }
        assertTrue(albums().single().assets.isEmpty(), "the Save landed with its gather still held")
        device("album/hold-adds", "on" to "false")
        eventually(read = { albums().single().assets }) { it == listOf("A") }

        // A join's gather: a photo received in this event, whose import is permanent across a leave.
        user("reconfigure", "saveToAlbum" to "false")
        awaitState { it.joined?.membership?.saveToAlbum == false }
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        leave()
        device("album/hold-adds")
        val placed = albums().single().assets.size

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join("saveToAlbum" to "true") // returns once joined

        assertEquals(placed, albums().single().assets.size, "the join landed with its gather still held")
        device("album/hold-adds", "on" to "false")
        eventually<Int>(read = { albums().single().assets.size }) { it > placed }
    }
}
