package app.snapsync.integration

import app.snapsync.model.AlbumKind
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
        awaitAlbum() // a cycle places only into an album that exists
        addPhoto("A")
        addPhoto("B")

        cycle() // placed, then the jobs are created

        assertEquals(listOf("A", "B"), albums().single().assets.sorted(), "placed before either upload finished")

        completeJobs()
        cycle() // acknowledged: nothing is placed again
        assertEquals(2, albums().single().assets.size, "a completion places nothing a second time")
    }

    @Test
    fun an_android_phone_is_offered_the_album_and_it_collects_received_photos_only() = rigTest {
        // Capability `event-album`: an Android album is the folder received photos are saved into, so the join screen
        // offers it on, and it holds what arrives — never the member's own camera photo.
        androidLibrary()
        create(name = "Party")
        val gate = state().ui.layer as Layer.JoiningEvent
        assertEquals(AlbumKind.FOLDER, gate.form.albumKind)
        assertEquals(true, gate.form.saveToAlbum, "the album is on by default, as on iPhone")

        join()
        awaitAlbum() // a download imports into the album only once it exists
        addPhoto("A")
        cycle()
        foreignDevice("DEV-F", "FQ")
        downloadAll()

        assertEquals(listOf(RECEIVED), albums().single().assets, "the received photo is in the album; the own one is not")
        val candidates = gallery().policy!!.assets.map { it.assetId }
        assertTrue(RECEIVED !in candidates, "a photo in the album is never a candidate to share: $candidates")
    }

    @Test
    fun on_android_a_photo_received_with_the_album_off_is_gathered_when_it_is_turned_on() = rigTest {
        androidLibrary()
        createAndJoin("saveToAlbum" to "false")
        addPhoto("A")
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        assertTrue(albums().isEmpty(), "no album while it is off: the photo is in the camera folder")

        user("reconfigure", "saveToAlbum" to "true")
        awaitState { it.joined?.membership?.saveToAlbum == true }

        eventually<List<String>?>(read = { albums().singleOrNull()?.assets }) { it == listOf(RECEIVED) }
    }

    @Test
    fun on_android_a_deleted_album_stays_deleted_until_the_member_turns_it_on_again() = rigTest {
        androidLibrary()
        createAndJoin("saveToAlbum" to "true", name = "Party")
        awaitAlbum()
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        val first = albums().single()
        assertEquals(listOf(RECEIVED), first.assets)

        device("album/delete", "album" to first.id) // the gallery app deletes the folder, and its photo with it
        foreignDevice("DEV-F", "FR")
        downloadAll()
        assertEquals(listOf(first.id), albums().map { it.id }, "no album was brought back")
        assertTrue(albums().single().assets.isEmpty(), "the later photo arrived in the camera folder")

        user("reconfigure", "saveToAlbum" to "false")
        awaitState { it.joined?.membership?.saveToAlbum == false }
        user("reconfigure", "saveToAlbum" to "true")
        awaitState { it.joined?.membership?.saveToAlbum == true }

        eventually<List<String>?>(read = { albums().firstOrNull { it.id != first.id }?.assets }) { it == listOf("imported-DEV-F-FR") }
    }

    @Test
    fun rejoining_the_event_reuses_its_album() = rigTest {
        val event = createAndJoin("saveToAlbum" to "true", name = "Party")
        assertEquals(1, awaitAlbum().size)
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
        assertTrue(awaitAlbum().single().assets.isEmpty(), "the Save landed with its gather still held")
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

    /** The photo library plays an Android phone's, whose albums are folders — read once at host assembly. */
    private suspend fun Rig.androidLibrary() {
        device("album/kind", "kind" to "folder")
        device("relaunch")
    }

    private companion object {
        /** The id the photo library gives the photo DEV-F shared as FQ. */
        const val RECEIVED = "imported-DEV-F-FQ"
    }
}
