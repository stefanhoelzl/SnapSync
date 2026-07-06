package app.snapsync.world

import app.snapsync.membership.HttpLeaveNotifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The event-leave lifecycle over the world's real mini-edge (`DELETE /events/<id>/devices/<id>`):
 * departed rename, last-active-member reap, and reference-checked byte/config GC (capability
 * `event-leave-endpoint`). Asserts backend/world outcomes on the [BackendStore] the real seam drives.
 */
class LeaveCascadeWorldTest {

    /** Upload the own device's asset [assetId] to [eventId] (manifest + stored bytes). */
    private suspend fun World.ownUpload(eventId: String, assetId: String) {
        addOwnAsset(assetId)
        runUploadCycle() // creates the job (REQUESTED) + writes the device manifest
        platform.completeJob("$assetId-primary.jpg") // store-direct byte deposit
        runUploadCycle() // ack → COMPLETED
    }

    @Test
    fun leave_with_another_member_departs_the_device_but_keeps_the_event_and_union() = worldTest {
        val w = World()
        val e = "E"
        w.provision(e)
        w.ownUpload(e, "A")
        w.addForeignDevice("DEV-FOREIGN", e, listOf(World.foreignAsset("FQ")))
        assertNotNull(w.store.manifestOf(e, w.ownDeviceId))

        w.leave()

        // The own device is DEPARTED, but its manifest + bytes persist, the union still serves its
        // asset, and the event stays alive (the foreign member keeps it).
        assertTrue(w.store.isDeparted(e, w.ownDeviceId))
        assertTrue(w.store.isRegistered(e))
        assertTrue(w.store.objectsOf(w.ownDeviceId).isNotEmpty())
        val union = w.store.union(e)!!
        assertTrue(union.any { it.deviceId == w.ownDeviceId }) // departed photos still downloadable
        assertTrue(union.any { it.deviceId == "DEV-FOREIGN" })
    }

    @Test
    fun last_active_member_leaving_reaps_the_event_and_gcs_its_orphaned_bytes() = worldTest {
        val w = World()
        val e = "E"
        w.provision(e)
        w.ownUpload(e, "A")
        assertTrue(w.store.objectsOf(w.ownDeviceId).isNotEmpty())

        w.leave() // the own device is the only member → last active leaves

        assertFalse(w.store.isRegistered(e)) // event reaped
        assertNull(w.store.union(e)) // gone (unregistered)
        assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty()) // bytes GC'd (fully orphaned)
    }

    @Test
    fun a_device_still_in_another_event_keeps_its_bytes_after_a_reap() = worldTest {
        val w = World()
        val e = "E"
        val f = "F"
        val x = "DEV-X"
        // X contributes to BOTH events; it is E's only member, so leaving E reaps E.
        w.addForeignDevice(x, e, listOf(World.foreignAsset("Q1")))
        w.addForeignDevice(x, f, listOf(World.foreignAsset("Q1")))

        // X leaves E through the REAL DELETE seam over the mini-edge.
        HttpLeaveNotifier(w.client, w.host).leave(e, x)

        assertFalse(w.store.isRegistered(e)) // E reaped (X was its last active member)
        assertTrue(w.store.isRegistered(f)) // F intact
        assertTrue(w.store.objectsOf(x).isNotEmpty()) // X's bytes retained — F still references them
        assertNotNull(w.store.manifestOf(f, x))
    }

    @Test
    fun rejoin_after_a_non_reap_leave_reconciles_without_re_uploading() = worldTest {
        val w = World()
        val e = "E"
        w.provision(e)
        w.ownUpload(e, "A")
        // a foreign member keeps E alive so leaving only departs the own device (no reap).
        w.addForeignDevice("DEV-FOREIGN", e, listOf(World.foreignAsset("FQ")))

        w.leave()
        assertTrue(w.store.isDeparted(e, w.ownDeviceId)) // departed, but manifest + bytes persist

        // Re-scan the same event → re-join: reconcile seeds the already-stored bytes COMPLETED, so the
        // next cycle uploads NOTHING (the bytes are already in storage; see `event-rejoin-reconciliation`).
        // The backend's fresh-manifest-supersedes-.left.json path is covered by the backend unit tests.
        w.provision(e)
        val createdBefore = w.platform.created.size
        w.runUploadCycle()

        assertEquals(createdBefore, w.platform.created.size) // nothing re-uploaded (bytes already stored)
        assertTrue(w.store.union(e)!!.any { it.deviceId == w.ownDeviceId }) // still served in the union
    }
}
