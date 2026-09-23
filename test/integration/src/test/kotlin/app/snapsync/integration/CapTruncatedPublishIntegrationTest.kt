package app.snapsync.integration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A device with more outstanding work than the platform will accept jobs for still reaches the other members —
 * over the **real** stack (same `snapSyncApp` core the device shells call; only PhotoKit and the edge are faked),
 * driven through the control protocol.
 *
 * This is the failure the change exists to remove, asserted where it was actually felt: not "the cycle returned the
 * right enum", but *a member of this event can see these photos*. Measured in the field before the fix (build
 * 0.3(605), iPhone11,2 / iOS 18.7.9): 26 consecutive cycles, 65 uploads completed, **zero** manifest writes, and an
 * event union that did not change for two hours while the app was open and working.
 *
 * That a truncated cycle's leftover resumes from the ledger without re-enumerating is an enumeration count, with
 * nothing outside the app to observe it; it is `UploadCycleTest`'s
 * `a_truncated_cycle_resumes_its_remainder_from_the_ledger_without_re_discovering`.
 */
class CapTruncatedPublishIntegrationTest {

    @Test
    fun a_device_that_never_drains_still_publishes_what_it_uploaded() = rigTest {
        val event = createAndJoin()
        device("jobs/limit", "n" to "2") // fewer slots than there is work, with enough left over to stay behind
        for (id in listOf("A", "B", "C", "D", "E")) addPhoto(id)

        // Cycle 1: two jobs created, the third is left over → the cycle cannot drain.
        assertEquals("processing", cycle())
        assertEquals(setOf(primaryKey("A"), primaryKey("B")), jobs().live.toSet())

        // The two uploads land, freeing both slots — and the next cycle is STILL truncated, because three assets
        // remain and only two can be in flight. This is the steady state of a device that is behind: it never
        // reaches the end of its own work list.
        completeJobs()
        assertEquals("processing", cycle())

        // THE POINT. A cycle that never drained has published what it settled, so the event union lists this
        // device's photos — while it is still uploading, not after it stops.
        assertEquals(setOf("A", "B"), union(event).keys, "the union lists what landed, on a cycle that did not drain")
    }
}
