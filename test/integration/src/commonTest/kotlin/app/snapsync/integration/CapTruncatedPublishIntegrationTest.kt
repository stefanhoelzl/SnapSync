package app.snapsync.integration

import app.snapsync.ports.CycleResult
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A device with more outstanding work than the platform will accept jobs for still reaches the other
 * members — over the **real** stack (same `snapSyncApp` core the device shells call; only PhotoKit and
 * the edge are faked).
 *
 * This is the failure the change exists to remove, asserted where it was actually felt: not "the cycle
 * returned the right enum", but *a member of this event can see these photos*. Measured in the field
 * before the fix (build 0.3(605), iPhone11,2 / iOS 18.7.9): 26 consecutive cycles, 65 uploads completed,
 * **zero** manifest writes, and an event union that did not change for two hours while the app was open
 * and working.
 */
class CapTruncatedPublishIntegrationTest {

    @Test
    fun a_device_that_never_drains_still_publishes_what_it_uploaded() = worldTest {
        val w = World(this)
        w.provision("E")
        w.platform.jobLimit = 2 // fewer slots than there is work, with enough left over to stay behind
        for (id in listOf("A", "B", "C", "D", "E")) w.addOwnAsset(id)

        // Cycle 1: two jobs created, the third is left over → the cycle cannot drain.
        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())
        assertEquals(listOf("A-primary.jpg", "B-primary.jpg"), w.platform.created.map { it.filename })

        // The two uploads land, freeing both slots — and the next cycle is STILL truncated, because
        // three assets remain and only two can be in flight. This is the steady state of a device that
        // is behind: it never reaches the end of its own work list.
        w.platform.completeJob("A-primary.jpg")
        w.platform.completeJob("B-primary.jpg")
        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())

        // THE POINT. A cycle that never drained has published what it settled, so the event union lists
        // this device's photos — while it is still uploading, not after it stops.
        val listed = w.store.union("E").orEmpty().map { it.assetId }.toSet()
        assertEquals(setOf("A", "B"), listed, "the union lists what landed, on a cycle that did not drain")
    }

    @Test
    fun the_leftover_resumes_from_the_ledger_without_re_enumerating() = worldTest {
        val w = World(this)
        w.provision("E")
        w.platform.jobLimit = 1
        w.addOwnAsset("A")
        w.addOwnAsset("B")

        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())
        val walksAfterFirstCycle = w.platform.discoverCalls

        // Nothing changes in the gallery. Under the old design this is the dead spot: an incremental walk
        // reports nothing, so "B" was reachable only by a full re-enumeration — which is exactly why the
        // cursor was not allowed to advance, which is why every cycle re-enumerated everything.
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        assertTrue(
            "B-primary.jpg" in w.platform.resolvedKeys,
            "the leftover was resolved by key from the ledger, not re-derived by a walk",
        )
        assertTrue(
            w.platform.discoverCalls > walksAfterFirstCycle,
            "the cycle still consults the change feed — the cursor is not a change oracle",
        )
        assertEquals(
            listOf("A-primary.jpg", "B-primary.jpg"),
            w.platform.created.map { it.filename },
            "and the leftover was enqueued",
        )
    }
}
