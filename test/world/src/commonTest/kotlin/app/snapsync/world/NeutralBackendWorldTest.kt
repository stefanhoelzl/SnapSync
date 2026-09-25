package app.snapsync.world

import app.snapsync.model.CycleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The backend-neutral surface over the mini-edge (`docs/testing.md`, "Neutral inspection and
 * minted event ids beside the mini-edge-only surface"): the same calls a test makes over the real backend,
 * answered by the mini-edge through its own HTTP routes — and agreeing with the store they read.
 */
class NeutralBackendWorldTest {

    @Test
    fun a_minted_provision_and_a_completed_job_are_visible_through_the_neutral_reads() = worldTest {
        val w = World(this)
        val eventId = w.provisionMinted()
        w.addOwnAsset("A")
        assertEquals(CycleResult.COMPLETED, w.runUploadCycle())
        w.platform.completeJob("A-primary.jpg")

        assertEquals(w.store.objectsOf(w.ownDeviceId), w.neutral.objectsOf(w.ownDeviceId).orFail())
        assertTrue("A-primary.jpg" in w.neutral.objectsOf(w.ownDeviceId).orFail())
        assertEquals(true, w.neutral.isRegistered(eventId).orFail())
        assertEquals(false, w.neutral.isRegistered("00000000-0000-4000-8000-00000000dead").orFail())
    }

    @Test
    fun a_foreign_device_seeded_through_the_routes_appears_in_the_union() = worldTest {
        val w = World(this)
        val eventId = w.provisionMinted()
        w.addForeignDeviceMinted("F", listOf(World.foreignAsset("X")), eventId)
        assertTrue(w.neutral.unionOf(eventId).orFail().any { it.deviceId == "F" && it.assetId == "X" })
    }

    @Test
    fun a_mini_edge_lever_is_available_and_does_what_it_says() = worldTest {
        val w = World(this)
        assertIs<Answer.Available<Unit>>(w.neutral.setOffline(true))
        assertTrue(w.backendOffline)
    }

    @Test
    fun a_refused_transfer_fails_the_job_with_the_backends_status() = worldTest {
        val w = World(this)
        w.provisionMinted()
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.neutral.setOffline(true).orFail()
        w.platform.completeJob("A-primary.jpg")
        assertTrue("A-primary.jpg" !in w.store.objectsOf(w.ownDeviceId), "nothing landed on a 502")
    }
}
