package app.snapsync.world

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A device joined with a capture-date cutoff (capability `photo-selection-policy`) uploads and shares
 * **only** its post-cutoff photos: a pre-cutoff asset never lands as bytes and never enters the event
 * union (so no other member can download it), while a post-cutoff asset does. This exercises the one
 * cutoff driving BOTH the byte-upload filter and the device-manifest projection through the real stack.
 */
class CutoffWorldTest {

    @Test
    fun cutoff_excludes_pre_cutoff_photos_from_upload_and_the_union() = worldTest {
        val w = World(this)
        val eventId = "E"
        w.provision(eventId, minPhotoDate = "2026-07-06T00:00:00Z")
        w.addOwnAsset("OLD", creationDate = "2026-07-01T00:00:00Z") // before the cutoff
        w.addOwnAsset("NEW", creationDate = "2026-07-10T00:00:00Z") // after the cutoff

        // Cycle 1 creates the byte job(s) + PUTs the manifest; only NEW is admitted past the cutoff.
        w.runUploadCycle()
        w.platform.completeJob("NEW-primary.jpg") // deposit NEW's bytes
        w.runUploadCycle() // acknowledge

        val union = w.store.union(eventId)!!
        assertTrue(
            union.any { it.deviceId == w.ownDeviceId && it.assetId == "NEW" },
            "the post-cutoff photo is uploaded and shared into the event union",
        )
        assertTrue(
            union.none { it.assetId == "OLD" },
            "the pre-cutoff photo is never shared — not in the manifest, no bytes, absent from the union",
        )
        // And its bytes were never uploaded (the byte-upload filter, not just the manifest projection).
        assertTrue(
            w.store.deviceListing(w.ownDeviceId).none { it.filename == "OLD-primary.jpg" },
            "the pre-cutoff photo's bytes are never uploaded to the device partition",
        )
    }
}
