package app.snapsync.world

import app.snapsync.model.GalleryAccess
import app.snapsync.model.CycleResult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app process's admission over the REAL composition (capability `background-upload`, "The upload cycle owns
 * its entry decision"): the world's cycle takes the same `appUploadAdmission()` the device app engine gates on.
 */
class AdmissionWorldTest {

    @Test
    fun a_revoked_grant_withholds_the_cycle_and_leaves_the_published_union_intact() = worldTest {
        val w = World(this)
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()
        assertTrue(w.store.union(eventId)!!.any { it.deviceId == w.ownDeviceId && it.assetId == "A" })

        w.permission.set(GalleryAccess.NOT_DETERMINED)
        w.addOwnAsset("B")

        assertEquals(CycleResult.SKIPPED, w.runUploadCycle(), "no usable access: the engine is not the resolved one")
        assertTrue(w.platform.created.none { it.filename == "B-primary.jpg" }, "nothing created without a grant")
        assertTrue(
            w.store.union(eventId)!!.any { it.deviceId == w.ownDeviceId && it.assetId == "A" },
            "a temporary grant state publishes nothing — never the empty manifest that would blank the union",
        )
    }

    @Test
    fun a_restored_grant_resumes_where_it_left_off() = worldTest {
        val w = World(this)
        w.provision("E")
        w.permission.set(GalleryAccess.DENIED)
        w.addOwnAsset("A")
        assertEquals(CycleResult.SKIPPED, w.runUploadCycle())

        w.permission.set(GalleryAccess.GRANTED)

        assertEquals(CycleResult.COMPLETED, w.runUploadCycle())
        assertTrue(w.platform.created.any { it.filename == "A-primary.jpg" })
    }
}
