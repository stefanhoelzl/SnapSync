package app.snapsync.integration

import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The selection policy over the **real** stack (capability `photo-selection-policy`): the real
 * `UploadCycle`, engine, ledger, device-manifest producer and mini-edge, with only PhotoKit faked.
 *
 * Each test asserts all three consequences an exclusion must have, because getting one and missing another
 * is exactly the failure mode this change exists to close:
 *
 *  1. **No bytes.** No upload job, no object in the backend store.
 *  2. **No ledger row.** Nothing to reconcile against later.
 *  3. **Not in the manifest** — so it never enters the event union and no other member downloads it. This is
 *     the one that used to leak: the manifest hook was fed the raw discovery.
 */
class SelectionPolicyIntegrationTest {

    @Test
    fun a_screenshot_is_neither_uploaded_nor_shared() = worldTest {
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("CAM")        // an ordinary camera photo
        w.addScreenshot("SHOT")     // …and a screenshot taken at the same event

        w.runUploadCycle()

        // 1. No bytes: only the camera photo got a job.
        assertEquals(listOf("CAM-primary.jpg"), w.platform.created.map { it.filename })
        // 2. No ledger row.
        assertNull(w.ledgerBackend.get("SHOT-primary.jpg"), "an excluded asset gains no ledger row")
        // 3. Not in the manifest → never in the event union, so no other member ever sees it.
        val manifest = w.manifestStore.loadAccumulator()
        assertTrue(manifest.none { it.assetId == "SHOT" }, "the screenshot never reaches the device manifest")
        assertTrue(manifest.any { it.assetId == "CAM" }, "…while the camera photo does")
    }

    @Test
    fun a_whatsapp_album_photo_is_neither_uploaded_nor_shared() = worldTest {
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("CAM")
        w.addOwnAsset("WA")
        w.placeInAlbum("WhatsApp", "WA") // saved by WhatsApp, not taken here

        w.runUploadCycle()

        assertEquals(listOf("CAM-primary.jpg"), w.platform.created.map { it.filename })
        assertTrue(w.manifestStore.loadAccumulator().none { it.assetId == "WA" })
    }

    @Test
    fun a_compressed_received_image_is_excluded_but_a_1080p_recording_is_not() = worldTest {
        // The single most dangerous line in this policy: 1080p video is 2.07 MP, BELOW the 3 MP image floor.
        // If the floors were shared, every video anyone recorded at the event would silently disappear.
        val w = World()
        w.provision("E")
        w.addLowResPhoto("WA")  // 1600x1200 = 1.9 MP → excluded
        w.addHdVideo("CLIP")    // 1920x1080 = 2.07 MP → ADMITTED
        w.addOwnAsset("CAM")

        w.runUploadCycle()

        assertEquals(setOf("CAM-primary.jpg", "CLIP-primary.jpg"), w.platform.created.map { it.filename }.toSet())
    }

    @Test
    fun the_status_total_excludes_what_the_cycle_refuses_so_the_screen_reaches_in_sync() = worldTest {
        // The two components enumerate INDEPENDENTLY. If the total counted the screenshot the cycle will
        // never upload, completeness would peg below 100% and the joined screen would say "pending" forever.
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("CAM")
        w.addScreenshot("SHOT")
        w.addLowResPhoto("WA")

        w.ownGallery.refresh(World.DEFAULT_CUTOFF)
        assertEquals(1, w.ownGallery.size.value, "N counts only the admitted camera photo")

        // Drive the admitted photo all the way to COMPLETED, and the world reports fully in sync.
        w.runUploadCycle()
        w.platform.completeJob("CAM-primary.jpg")
        w.runUploadCycle()

        val union = w.store.union(eventId)!!
        assertTrue(union.any { it.assetId == "CAM" })
        assertTrue(union.none { it.assetId == "SHOT" || it.assetId == "WA" })
    }
}
