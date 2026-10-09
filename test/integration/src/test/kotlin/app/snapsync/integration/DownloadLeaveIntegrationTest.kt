package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.Layer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Received photos and leaving, over the real stack, read back off the photo library, the operating system's jobs and
 * the backend: an error body is never imported, an imported photo outlives a leave and is never sent back, and leaving
 * departs this device and nothing more.
 */
class DownloadLeaveIntegrationTest {

    /**
     * A `502` arrives as a *successful* transfer of an error body. Staged, it would become the store's truth: the import
     * would fail against it forever, the transfer never re-run, and nothing would say so.
     */
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
        scenario = "A broken download is retried",
    )
    fun a_rejected_transfer_imports_nothing_and_stays_pending_for_the_next_reconcile() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        val before = libraryTotal()
        reconcile()

        stage(status = 502)
        assertEquals(before, libraryTotal(), "an error body is never imported")

        reconcile() // not terminal: the resource stayed un-staged, so the transfer is started again
        stage()
        assertEquals(before + 1, libraryTotal(), "the retry imports — the bytes were re-fetched")
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving deletes no one's photos",
        scenario = "Received photos stay",
    )
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun an_imported_photo_outlives_a_leave_and_a_rejoin_never_sends_it_back() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        val imported = libraryTotal()

        leave()
        assertEquals(imported, libraryTotal(), "the imported photo survives the leave")

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
        join()
        cycle()
        assertEquals(0, jobs().created, "the imported photo is still suppressed, so nothing is uploaded")
    }

    /** Leaving is RENAME-ONLY: no reap, no byte collection — the nightly sweep does that. */
    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving deletes no one's photos",
        scenario = "Shared photos outlive the member's leave",
    )
    @Verifies(spec = "event-lifetime", requirement = "A member who leaves keeps contributing what they shared")
    fun leaving_departs_this_device_and_keeps_the_event_its_bytes_and_the_union() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        addPhoto("A")
        uploadAll()
        foreignDevice("DEV-F", "FQ")

        leave()

        assertTrue(
            deviceJson("backend/departed", "event" to event).getValue("departed").jsonPrimitive.boolean,
            "this device is departed",
        )
        assertTrue(
            deviceJson("backend/event", "event" to event).getValue("registered").jsonPrimitive.boolean,
            "the event lives on",
        )
        assertTrue(primaryKey("A") in objects(event = event), "its bytes are kept")
        assertEquals(setOf("A", "FQ"), union(event).keys, "the union still serves the departed member's photos")
    }
}
