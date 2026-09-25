package app.snapsync.integration

import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.presentation.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The selection policy over the **real** stack (capability `photo-sharing`): the real `UploadCycle`,
 * engine, ledger, device-manifest producer and mini-edge, with only PhotoKit faked — driven through the control
 * protocol.
 *
 * Each test asserts the consequences an exclusion must have, because getting one and missing another is exactly
 * the failure mode this change exists to close:
 *
 *  1. **No bytes.** No upload job, no object on the backend.
 *  2. **Not in the manifest** — so it never enters the event union and no other member downloads it. This is the
 *     one that used to leak: the manifest hook was fed the raw discovery.
 */
class SelectionPolicyIntegrationTest {

    @Test
    fun a_screenshot_is_neither_uploaded_nor_shared() = rigTest {
        val event = createAndJoin()
        addPhoto("CAM") // an ordinary camera photo
        addPhoto("SHOT", kind = "screenshot") // …and a screenshot taken at the same event

        cycle()

        // 1. No bytes: only the camera photo got a job.
        assertEquals(listOf(primaryKey("CAM")), jobs().live)
        assertEquals(1, jobs().created)
        // 2. Not in the manifest → never in the event union, so no other member ever sees it. The manifest lists
        //    what landed once the cycle that records the completion re-projects: complete the admitted job and
        //    invoke again.
        completeJobs(primaryKey("CAM"))
        cycle()
        assertEquals(setOf("CAM"), manifest(event)?.keys, "only the camera photo reaches the device manifest")
        assertEquals(setOf("CAM"), union(event).keys)
    }

    @Test
    fun a_whatsapp_album_photo_is_neither_uploaded_nor_shared() = rigTest {
        val event = createAndJoin()
        addPhoto("CAM")
        addPhoto("WA")
        device("album/place", "album" to "WhatsApp", "asset" to "WA") // saved by WhatsApp, not taken here

        cycle()

        assertEquals(listOf(primaryKey("CAM")), jobs().live)
        assertEquals(1, jobs().created)
        completeJobs(primaryKey("CAM"))
        cycle()
        assertEquals(setOf("CAM"), manifest(event)?.keys)
    }

    @Test
    fun a_compressed_received_image_is_excluded_but_a_1080p_recording_is_not() = rigTest {
        // The single most dangerous line in this policy: 1080p video is 2.07 MP, BELOW the 3 MP image floor.
        // If the floors were shared, every video anyone recorded at the event would silently disappear.
        createAndJoin()
        addPhoto("WA", kind = "low-res") // 1600x1200 = 1.9 MP → excluded
        addPhoto("CLIP", kind = "hd-video") // 1920x1080 = 2.07 MP → ADMITTED
        addPhoto("CAM")

        cycle()

        assertEquals(setOf("CAM", "CLIP"), jobs().live.mapTo(mutableSetOf(), ::assetIdFromUploadKey))
        assertEquals(2, jobs().created)
    }

    @Test
    fun the_status_total_excludes_what_the_cycle_refuses_so_the_screen_reaches_in_sync() = rigTest {
        // The two components enumerate INDEPENDENTLY. If the total counted the screenshot the cycle will never
        // upload, completeness would peg below 100% and the joined screen would say "pending" forever — so the
        // screen reaching In sync once the one admitted photo lands IS the total excluding the other two.
        val event = createAndJoin()
        addPhoto("CAM")
        addPhoto("SHOT", kind = "screenshot")
        addPhoto("WA", kind = "low-res")

        refresh()
        awaitHealth { it is SyncHealth.Syncing } // the admitted photo is outstanding

        // Drive the admitted photo all the way to landed, and the screen reports fully in sync.
        uploadAll()
        refresh()
        awaitInSync()

        val union = union(event)
        assertTrue("CAM" in union, "$union")
        assertTrue("SHOT" !in union && "WA" !in union, "$union")
    }
}
