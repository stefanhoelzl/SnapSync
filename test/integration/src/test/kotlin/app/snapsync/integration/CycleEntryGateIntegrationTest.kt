package app.snapsync.integration

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cycle's **entry gate** over the real stack (capability `background-upload`, and `join-event`'s
 * *An unreadable config is not an absent config*): the real `UploadCycle`, ledger and mini-edge, driven through the
 * control protocol, with only the membership read forced (`membership/unreadable`).
 *
 * These assertions could not be made before the gate moved into the shared core, in either of the two senses that
 * matter:
 *
 *  - The **harness** could not express an unreadable membership. Its config cell was nullable, so it modelled
 *    only *joined* and *absent* — the outcome three shipped bugs turned on was the one the harness could
 *    not reach.
 *  - The **decision** lived in each composition root, which is `iosMain` and untested by project rule. No
 *    test could reach it at all, which is why one tier had it and the other did not.
 *
 * The distinction under test is not academic. An unreadable read that acted like a leave would touch a
 * settled membership's upload state or publish over its manifest; a cycle must do neither until it can read what
 * the device is joined to.
 */
class CycleEntryGateIntegrationTest {

    /** How many manifest publishes the backend APPLIED for this device in the joined event. */
    private suspend fun Rig.publishes(): Int = deviceJson("backend/publishes").getValue("applied").jsonPrimitive.int

    private suspend fun Rig.membershipUnreadable(on: Boolean) {
        device("membership/unreadable", "on" to on.toString())
    }

    @Test
    fun an_unreadable_membership_leaves_the_ledger_untouched() = rigTest {
        // Weakened from "the ledger rows are byte-identical" (the ledger is internal) to its observable twins: the
        // device stays joined, nothing is uploaded and no job is created while unreadable — and once readable again,
        // the settled photo is NOT re-queued, which a cleared ledger would do.
        val eventId = createAndJoin()
        addPhoto("A")
        // Settle the join: a first readable cycle records and uploads.
        uploadAll()
        assertTrue(primaryKey("A") in objects(), "precondition: the settled cycle uploaded the asset")
        val createdAfterSettle = jobs().created

        // The device is now woken while its membership cannot be read — a boot with no unlock since.
        membershipUnreadable(true)
        addPhoto("B")
        val result = cycle()

        assertEquals("completed", result, "an unreadable read is a clean no-op")
        assertEquals(eventId, state().ready.eventId, "unreadable is not a leave")
        assertEquals(createdAfterSettle, jobs().created, "no upload job while the membership is unreadable")
        assertEquals(setOf(primaryKey("A")), objects(), "nothing uploaded while the membership is unreadable")

        // The device of a membership that never left keeps what it settled: the next readable cycle queues only the
        // new photo, never the one already uploaded.
        membershipUnreadable(false)
        cycle()
        assertEquals(listOf(primaryKey("B")), jobs().live, "the settled photo was not re-queued")
    }

    @Test
    fun an_unreadable_membership_uploads_nothing_and_touches_no_storage() = rigTest {
        createAndJoin()
        addPhoto("A")
        membershipUnreadable(true)
        val before = publishes()

        cycle()

        assertEquals(0, jobs().created, "no upload job")
        assertTrue(objects().isEmpty(), "no object landed")
        // The publish is what announces a device's asset set now (there is no notify route on the
        // versioned device API), so "touched no storage" has to mean the cycle published nothing — not
        // merely that the resulting manifest looks unchanged, which a republish would satisfy too.
        assertEquals(before, publishes(), "no manifest published")
    }

    // The other half of the gate: the fix must not turn a REAL leave into a skip. A definitively absent
    // membership reads as not joined, and the cycle uploads nothing for it.
    @Test
    fun a_cleared_membership_reads_as_not_joined_and_uploads_nothing() = rigTest {
        createAndJoin()
        addPhoto("A")
        cycle()
        val created = jobs().created

        // A real leave: the config is definitively gone, and readable.
        leave()
        addPhoto("B")
        // SKIPPED: with no membership the app engine's pump must not re-arm a heartbeat for no event.
        assertEquals("skipped", cycle())

        assertEquals(created, jobs().created, "a device that left uploads nothing")
    }

    @Test
    fun a_readable_membership_still_uploads_so_the_gate_is_not_skipping_everything() = rigTest {
        // The control. A gate that declines every cycle is indistinguishable from a gate that works
        // unless the happy path is asserted alongside it — and a silently-skipped upload is this
        // project's defining failure mode.
        createAndJoin()
        addPhoto("A")

        cycle()

        assertEquals(listOf(primaryKey("A")), jobs().live)
    }

    @Test
    fun the_membership_is_re_read_each_cycle_so_the_skip_is_not_sticky() = rigTest {
        // The cycle is long-lived now. An unreadable read must not latch: the next cycle, once the device
        // is unlocked, has to resume normally.
        createAndJoin()
        addPhoto("A")
        membershipUnreadable(true)
        cycle()
        assertEquals(0, jobs().created, "precondition: skipped")

        membershipUnreadable(false) // the user unlocked
        cycle()

        assertEquals(listOf(primaryKey("A")), jobs().live, "the next cycle resumes")
    }
}
