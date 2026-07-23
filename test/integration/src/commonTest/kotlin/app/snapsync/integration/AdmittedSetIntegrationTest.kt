package app.snapsync.integration

import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventEnd
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **One admitted set, over the real stack** (capability `photo-selection-policy`).
 *
 * The unit tests assert the property over one policy value. This asserts it over the composed core the
 * device shells actually run — the same `snapSyncApp`/`uploadCore`, the real `UploadCycle`, the real
 * status source, the real preview — with only PhotoKit faked. That distinction is the whole reason the
 * original bug survived: each consumer's own unit tests passed while the four disagreed in production,
 * because nothing composed them and compared.
 *
 * The fixture is the shape that surfaced it on device: a **closed** capture window (an event whose end has
 * passed) holding a photo taken after the ceiling. Every prior fixture left the ceiling absent.
 */
class AdmittedSetIntegrationTest {

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val ceiling = captureCeiling("2026-06-30T00:00:00Z")
    private val end = eventEnd("2026-06-30T00:00:00Z")

    private val inWindow = "2026-06-15T12:00:00Z"
    private val postCeiling = "2026-07-15T12:00:00Z"

    @Test
    fun a_post_ceiling_photo_reaches_no_consumer() = worldTest {
        val w = World(this)
        w.provision("E", minPhotoDate = cutoff, maxPhotoDate = ceiling, endsAt = end)
        w.addOwnAsset("IN", creationDate = inWindow)
        w.addOwnAsset("AFTER", creationDate = postCeiling)

        // ① the join preview
        assertEquals(1, w.core.loadShareableCount(cutoff, ceiling), "the preview counts only the in-window photo")

        // ② the status total N — the half the user can see. Counting AFTER here is what pegged the screen
        //    below 100% forever, because its bytes were never going to arrive.
        w.refreshStatus()
        assertEquals(1, w.core.gallery.size.value, "N counts only the in-window photo")

        // ③ the byte upload
        w.runUploadCycle()
        assertEquals(
            listOf("IN-primary.jpg"),
            w.platform.created.map { it.filename },
            "only the in-window photo's bytes are uploaded",
        )

        // ④ the device manifest — the consumer that leaked. A post-ceiling asset listed here enters the
        //    event union and is offered to every other member as bytes that were never uploaded: a 404
        //    for everyone, and invisible on the device that caused it.
        val manifest = w.store.manifestOf("E", w.ownDeviceId)
        assertEquals(
            listOf("IN"),
            manifest?.assets?.map { it.assetId },
            "the manifest lists only the in-window photo",
        )
    }

    @Test
    fun an_open_window_admits_the_same_photo_at_every_consumer() = worldTest {
        // The control. Without it the assertions above would pass just as well against a stack that
        // dropped AFTER for some unrelated reason — which is exactly how the original bug hid behind four
        // green suites.
        val w = World(this)
        w.provision("E", minPhotoDate = cutoff)
        w.addOwnAsset("IN", creationDate = inWindow)
        w.addOwnAsset("AFTER", creationDate = postCeiling)

        assertEquals(2, w.core.loadShareableCount(cutoff, null))
        w.refreshStatus()
        assertEquals(2, w.core.gallery.size.value)
        w.runUploadCycle()
        assertEquals(2, w.platform.created.size)
        assertEquals(2, w.store.manifestOf("E", w.ownDeviceId)?.assets?.size)
    }

    @Test
    fun the_origin_exclusions_reach_every_consumer_too() = worldTest {
        // The ceiling is the bound that drifted, but the property is about the SET, not about one rule:
        // a screenshot and a sub-floor image must be absent from all four answers just as firmly.
        val w = World(this)
        w.provision("E", minPhotoDate = cutoff, maxPhotoDate = ceiling, endsAt = end)
        w.addOwnAsset("CAM", creationDate = inWindow)
        w.addScreenshot("SHOT", creationDate = inWindow)
        w.addLowResPhoto("WA", creationDate = inWindow)

        assertEquals(1, w.core.loadShareableCount(cutoff, ceiling))
        w.refreshStatus()
        assertEquals(1, w.core.gallery.size.value)
        w.runUploadCycle()
        assertEquals(listOf("CAM-primary.jpg"), w.platform.created.map { it.filename })

        val listed = w.store.manifestOf("E", w.ownDeviceId)?.assets?.map { it.assetId }.orEmpty()
        assertEquals(listOf("CAM"), listed)
        assertTrue("SHOT" !in listed && "WA" !in listed, "no excluded asset reaches the event union")
    }
}
