package app.snapsync.integration

import app.snapsync.model.Layer
import app.snapsync.model.ShareCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The join-time **shareable-count preview** over the **real** stack (capability `join-event`): the count the
 * join surface shows must equal the set the real `UploadCycle` uploads for the same cutoff — the one-universe
 * requirement (capability `photo-sharing`). Same `snapSyncApp` core the device shells call; only PhotoKit
 * is faked. The preview is read where a member reads it: on the open join gate, before the commit.
 */
class ShareableCountIntegrationTest {

    @Test
    fun the_preview_count_equals_the_set_the_cycle_uploads() = rigTest {
        addPhoto("CAM") // an ordinary camera photo — admitted
        addPhoto("SHOT", kind = "screenshot") // excluded by subtype
        addPhoto("WA", kind = "low-res") // 1.9 MP → below the 3 MP floor, excluded

        // The preview for the same cutoff the cycle applies: exactly the camera photo.
        create()
        awaitShareCount(ShareCount.Ready(1), "the preview counts only the admitted photo")
        join()

        // …and the cycle uploads exactly that set — the count is not a separate, looser rule.
        cycle()
        assertEquals(listOf(primaryKey("CAM")), jobs().live)
        assertEquals(1, jobs().created)
    }

    @Test
    fun a_later_candidate_cutoff_previews_fewer_photos() = rigTest {
        addPhoto("OLD", date = "2026-05-20T00:00:00Z")
        addPhoto("NEW", date = "2026-06-10T00:00:00Z")
        create()

        // A candidate cutoff between the two dates admits only NEW — the preview tracks the chosen cutoff.
        user("setRange", "cutoff" to "2026-05-16T00:00:00Z")
        awaitShareCount(ShareCount.Ready(2))
        user("setRange", "cutoff" to "2026-06-01T00:00:00Z")
        awaitShareCount(ShareCount.Ready(1))
        user("setRange", "cutoff" to "2026-06-12T00:00:00Z")
        awaitShareCount(ShareCount.Ready(0))
    }

    @Test
    fun no_count_without_a_usable_grant() = rigTest {
        addPhoto("CAM")
        create()
        awaitShareCount(ShareCount.Ready(1)) // counted under the grant — so an absent count below is the grant's doing

        permission("DENIED")
        awaitShareCount(ShareCount.Unavailable, "a denied grant yields no count")

        permission("GRANTED")
        awaitShareCount(ShareCount.Ready(1))
        permission("NOT_DETERMINED")
        awaitShareCount(ShareCount.Unavailable, "an unresolved grant yields no count")
    }
}

/** Wait until the open join gate's shareable-count preview reads [expected]. */
private suspend fun Rig.awaitShareCount(expected: ShareCount, what: String = "the preview reads $expected") {
    try {
        awaitState { s -> (s.ui.layer as? Layer.JoiningEvent)?.range?.shareCount == expected }
    } catch (e: IllegalStateException) {
        fail("$what — last: ${(state().ui.layer as? Layer.JoiningEvent)?.range?.shareCount}", e)
    }
}
