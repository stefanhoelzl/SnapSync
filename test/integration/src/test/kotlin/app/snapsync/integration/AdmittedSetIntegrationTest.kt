package app.snapsync.integration

import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.presentation.Layer
import app.snapsync.presentation.ShareCount
import app.snapsync.presentation.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **One admitted set, over the real stack** (capability `photo-sharing`).
 *
 * The unit tests assert the property over one policy value. This asserts it over the composed core the device
 * shells actually run — the same `snapSyncApp`/`uploadCore`, the real `UploadCycle`, the real status source, the
 * real preview — with only PhotoKit faked, driven through the control protocol. That distinction is the whole
 * reason the original bug survived: each consumer's own unit tests passed while the four disagreed in production,
 * because nothing composed them and compared.
 *
 * The fixture is the shape that surfaced it on device: a **closed** capture window (an event whose end has passed)
 * holding a photo taken after the ceiling.
 */
class AdmittedSetIntegrationTest {

    private val inWindow = "2026-06-15T12:00:00Z"
    private val postCeiling = "2026-07-05T12:00:00Z"

    /** The closed window: it ends before [postCeiling]. */
    private val closedStart = "2026-06-01T00:00:00"
    private val closedEnd = "2026-06-30T00:00:00"

    @Test
    fun a_post_ceiling_photo_reaches_no_consumer() = rigTest {
        addPhoto("IN", date = inWindow)
        addPhoto("AFTER", date = postCeiling)

        // ① the join preview
        create(startsAt = closedStart, endsAt = closedEnd)
        assertEquals(1, awaitShareCount(), "the preview counts only the in-window photo")
        val event = join()

        // ③ the byte upload
        cycle()
        assertEquals(listOf(primaryKey("IN")), jobs().live, "only the in-window photo's bytes are uploaded")
        assertEquals(1, jobs().created)

        // ④ the device manifest — the consumer that leaked. A post-ceiling asset listed here enters the event union
        //    and is offered to every other member as bytes that were never uploaded: a 404 for everyone, and
        //    invisible on the device that caused it. The manifest lists what landed once the cycle that records the
        //    completion re-projects, so complete the job, then invoke again.
        completeJobs()
        cycle()
        assertEquals(setOf("IN"), manifest(event)?.keys, "the manifest lists only the in-window photo")

        // ② the status total N — the half the user can see. Counting AFTER here is what pegged the screen below
        //    100% forever, because its bytes were never going to arrive: In sync once IN has landed is N = 1.
        refresh()
        awaitInSync()
    }

    @Test
    fun an_open_window_admits_the_same_photo_at_every_consumer() = rigTest {
        // The control. Without it the assertions above would pass just as well against a stack that dropped AFTER
        // for some unrelated reason — which is exactly how the original bug hid behind four green suites. An event
        // is at most 30 days long, so "open" is a window whose end lies past AFTER.
        addPhoto("IN", date = inWindow)
        addPhoto("AFTER", date = postCeiling)

        create(startsAt = "2026-06-10T00:00:00", endsAt = "2026-07-09T00:00:00")
        assertEquals(2, awaitShareCount())
        val event = join()

        refresh()
        awaitHealth { it is SyncHealth.Syncing }
        cycle()
        assertEquals(setOf("IN", "AFTER"), jobs().live.mapTo(mutableSetOf(), ::assetIdFromUploadKey))
        completeJobs()
        cycle()
        assertEquals(setOf("IN", "AFTER"), manifest(event)?.keys)
        refresh()
        awaitInSync()
    }

    @Test
    fun the_origin_exclusions_reach_every_consumer_too() = rigTest {
        // The ceiling is the bound that drifted, but the property is about the SET, not about one rule: a
        // screenshot and a sub-floor image must be absent from all four answers just as firmly.
        addPhoto("CAM", date = inWindow)
        addPhoto("SHOT", date = inWindow, kind = "screenshot")
        addPhoto("WA", date = inWindow, kind = "low-res")

        create(startsAt = closedStart, endsAt = closedEnd)
        assertEquals(1, awaitShareCount())
        val event = join()

        cycle()
        assertEquals(listOf(primaryKey("CAM")), jobs().live)
        completeJobs()
        cycle()

        val listed = manifest(event)?.keys.orEmpty()
        assertEquals(setOf("CAM"), listed)
        assertTrue("SHOT" !in listed && "WA" !in listed, "no excluded asset reaches the event union")
        refresh()
        awaitInSync()
    }
}

/** The open join gate's shareable-count preview, once it has counted. */
private suspend fun Rig.awaitShareCount(): Int {
    val count = awaitState { s ->
        ((s.ui.layer as? Layer.JoiningEvent)?.range?.shareCount) is ShareCount.Ready
    }
    return ((count.ui.layer as Layer.JoiningEvent).range!!.shareCount as ShareCount.Ready).count
}
