package app.snapsync.integration

import app.snapsync.ports.CycleResult
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cycle's **entry gate** over the real stack (capability `upload-lifecycle`, and `event-link`'s
 * *An unreadable config is not an absent config*): the real `UploadCycle`, ledger and mini-edge, with only
 * the membership read forced.
 *
 * These assertions could not be made before this change, in either of the two senses that matter:
 *
 *  - The **world** could not express an unreadable membership. Its config cell is nullable, so it modelled
 *    only *joined* and *absent* — the outcome three shipped bugs turned on was the one the harness could
 *    not reach.
 *  - The **decision** lived in each composition root, which is `iosMain` and untested by project rule. No
 *    test could reach it at all, which is why one tier had it and the other did not.
 *
 * The distinction under test is not academic. An unreadable read that acted like a leave would touch a
 * settled membership's ledger or publish over its manifest; a cycle must do neither until it can read what
 * the device is joined to.
 */
class CycleEntryGateIntegrationTest {

    @Test
    fun an_unreadable_membership_leaves_the_ledger_untouched() = worldTest {
        val w = World(this)
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("A")
        // Settle the join: a first readable cycle records and uploads.
        w.runUploadCycle()
        val ledgerAfterSettle = w.ledgerBackend.manifestRows()
        assertTrue(ledgerAfterSettle.isNotEmpty(), "precondition: the settled cycle recorded the asset")

        // The device is now woken while its membership cannot be read — a boot with no unlock since.
        w.membershipUnreadable = true
        val result = w.runUploadCycle()

        assertEquals(CycleResult.COMPLETED, result, "an unreadable read is a clean no-op")
        assertEquals(eventId, w.configSource.config.value?.eventId, "unreadable is not a leave")
        assertEquals(
            ledgerAfterSettle, w.ledgerBackend.manifestRows(),
            "the ledger of a device that never left must not be touched",
        )
    }

    @Test
    fun an_unreadable_membership_uploads_nothing_and_touches_no_storage() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.membershipUnreadable = true
        val before = w.store.publishesOf("E", w.ownDeviceId)

        w.runUploadCycle()

        assertEquals(emptyList(), w.platform.created.map { it.filename }, "no upload job")
        assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "no object landed")
        assertNull(w.ledgerBackend.get("A-primary.jpg"), "no ledger row")
        // The publish is what announces a device's asset set now (there is no notify route on the
        // versioned device API), so "touched no storage" has to mean the cycle published nothing — not
        // merely that the resulting manifest looks unchanged, which a republish would satisfy too.
        assertEquals(before, w.store.publishesOf("E", w.ownDeviceId), "no manifest published")
    }

    // The other half of the gate: the fix must not turn a REAL leave into a skip. A definitively absent
    // membership reads as not joined, and the cycle uploads nothing for it.
    @Test
    fun a_cleared_membership_reads_as_not_joined_and_uploads_nothing() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        val created = w.platform.created.size

        // A real leave: the config is definitively gone, and readable.
        w.leave()
        w.addOwnAsset("B")
        // SKIPPED: with no membership the app engine's pump must not re-arm a heartbeat for no event.
        assertEquals(CycleResult.SKIPPED, w.runUploadCycle())

        assertEquals(created, w.platform.created.size, "a device that left uploads nothing")
        assertTrue(w.ledgerBackend.manifestRows().isEmpty(), "and the leave cleared its upload ledger")
    }

    @Test
    fun a_readable_membership_still_uploads_so_the_gate_is_not_skipping_everything() = worldTest {
        // The control. A gate that declines every cycle is indistinguishable from a gate that works
        // unless the happy path is asserted alongside it — and a silently-skipped upload is this
        // project's defining failure mode.
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")

        w.runUploadCycle()

        assertEquals(listOf("A-primary.jpg"), w.platform.created.map { it.filename })
    }

    @Test
    fun the_membership_is_re_read_each_cycle_so_the_skip_is_not_sticky() = worldTest {
        // The cycle is long-lived now. An unreadable read must not latch: the next cycle, once the device
        // is unlocked, has to resume normally.
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.membershipUnreadable = true
        w.runUploadCycle()
        assertEquals(emptyList(), w.platform.created.map { it.filename }, "precondition: skipped")

        w.membershipUnreadable = false // the user unlocked
        w.runUploadCycle()

        assertEquals(listOf("A-primary.jpg"), w.platform.created.map { it.filename }, "the next cycle resumes")
    }
}
