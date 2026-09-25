package app.snapsync.integration

import app.snapsync.model.Direction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam ↔ UI-state integration for the in-place reconfigure (capability `manage-membership`), driven through the
 * control protocol's `/user/reconfigure` over the real core — asserting **`UiState` AND observable outcomes**:
 * enabling share uploads, album-on gathers what is already held, and turning receive off cancels in-flight downloads.
 */
class ReconfigureIntegrationTest {

    @Test
    fun enabling_share_on_a_download_only_membership_starts_uploading_in_place() = rigTest {
        val event = createAndJoin("direction" to "download")
        addPhoto("A")

        // Download-only: the cycle uploads nothing (the privacy invariant).
        cycle()
        assertTrue(objects().isEmpty(), "download-only uploads nothing")

        // Reconfigure IN PLACE to Both — no leave, same eventId.
        user("reconfigure", "direction" to "both")
        val membership = awaitState { it.joined?.membership?.direction == Direction.Both }.joined!!.membership
        assertEquals(event, membership.eventId, "same membership, never left")

        // Now the very same stack uploads the own photo.
        uploadAll()
        refresh()

        assertTrue(primaryKey("A") in objects(), "enabling share uploads the own photo")
        awaitInSync()
    }

    @Test
    fun turning_the_album_on_gathers_already_synced_photos_and_places_new_ones() = rigTest {
        // Every membership carries a name (capability `join-event`); it titles the album.
        createAndJoin("saveToAlbum" to "false", name = "Anna's Birthday")
        addPhoto("A")

        // A uploads while the album is OFF — so it is a genuinely already-synced photo.
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        assertTrue(albums().isEmpty(), "no album while opted out")

        // Reconfigure the album ON: the album is ensured, and the gather (capability `event-album`) places the
        // already-synced A — detached, so the Save returned before it ran.
        user("reconfigure", "saveToAlbum" to "true")
        val album = eventually(read = { albums() }) { it.size == 1 && it.single().assets == listOf("A") }.single()
        assertEquals("Anna's Birthday", album.name)

        // A NEW photo synced after the toggle is placed too, at its first enqueue — before the upload completed.
        addPhoto("B")
        cycle()
        eventually<List<String>>(read = { albums().single().assets }) { "B" in it }
    }

    @Test
    fun turning_receive_off_cancels_in_flight_downloads_and_imports_nothing() = rigTest {
        createAndJoin() // Both
        foreignDevice("DEV-F", "FQ")
        val before = libraryTotal()

        // Enqueue an in-flight download, then turn RECEIVE off before it is staged.
        reconcile()
        user("reconfigure", "direction" to "upload")
        awaitState { it.joined?.membership?.direction == Direction.UploadOnly }

        // The in-flight download was cancelled, so staging imports nothing, and the now-gated reconcile enqueues
        // nothing further.
        stage()
        reconcile()
        stage()
        assertEquals(before, libraryTotal(), "receive-off cancels in-flight downloads; nothing imports")

        refresh()
        awaitInSync()
    }

    // ---- helpers --------------------------------------------------------------------------------

    private class Album(val name: String, val assets: List<String>)

    /** Every album this app created, with the assets placed in it. */
    private suspend fun Rig.albums(): List<Album> =
        deviceJson("album/contents").getValue("albums").jsonArray.map { it.jsonObject.toAlbum() }

    private fun JsonObject.toAlbum() = Album(
        name = getValue("name").jsonPrimitive.content,
        assets = getValue("assets").jsonArray.map { it.jsonPrimitive.content },
    )
}
