package app.snapsync.integration

import app.snapsync.model.captureCutoff
import app.snapsync.model.PermissionStatus
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The join-time **shareable-count preview** over the **real** stack (capability `join-share-count`): the
 * count the join surface shows must equal the set the real `UploadCycle` uploads for the same cutoff — the
 * one-universe requirement (capability `photo-selection-policy`). Same `snapSyncApp` core the device shells
 * call; only PhotoKit is faked.
 */
class ShareableCountIntegrationTest {

    @Test
    fun the_preview_count_equals_the_set_the_cycle_uploads() = worldTest {
        val w = World(this)
        w.provision("E") // cutoff = DEFAULT_CUTOFF
        w.addOwnAsset("CAM")     // an ordinary camera photo — admitted
        w.addScreenshot("SHOT")  // excluded by subtype
        w.addLowResPhoto("WA")   // 1.9 MP → below the 3 MP floor, excluded

        // The preview for the same cutoff the cycle applies: exactly the camera photo.
        assertEquals(1, w.core.loadShareableCount(captureCutoff(World.DEFAULT_CUTOFF), null), "the preview counts only the admitted photo")

        // …and the cycle uploads exactly that set — the count is not a separate, looser rule.
        w.runUploadCycle()
        assertEquals(listOf("CAM-primary.jpg"), w.platform.created.map { it.filename })
    }

    @Test
    fun a_later_candidate_cutoff_previews_fewer_photos() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("OLD", creationDate = "2026-05-01T00:00:00Z")
        w.addOwnAsset("NEW", creationDate = "2026-07-01T00:00:00Z")

        // A candidate cutoff between the two dates admits only NEW — the preview tracks the chosen cutoff.
        assertEquals(2, w.core.loadShareableCount(captureCutoff("2026-04-01T00:00:00Z"), null))
        assertEquals(1, w.core.loadShareableCount(captureCutoff("2026-06-01T00:00:00Z"), null))
        assertEquals(0, w.core.loadShareableCount(captureCutoff("2026-08-01T00:00:00Z"), null))
    }

    @Test
    fun no_count_without_a_usable_grant() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("CAM")

        w.permission.set(PermissionStatus.DENIED)
        assertNull(w.core.loadShareableCount(captureCutoff(World.DEFAULT_CUTOFF), null), "a denied grant yields no count")

        w.permission.set(PermissionStatus.NOT_DETERMINED)
        assertNull(w.core.loadShareableCount(captureCutoff(World.DEFAULT_CUTOFF), null), "an unresolved grant yields no count")
    }
}
