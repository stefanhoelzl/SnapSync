package app.snapsync.integration

import app.snapsync.upload.CycleResult
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cycle's **entry gate** over the real stack (capability `upload-lifecycle`, and `event-link`'s
 * *An unreadable config is not an absent config*): the real `UploadCycle`, reconciler, ledger, marker and
 * mini-edge, with only the membership read forced.
 *
 * These assertions could not be made before this change, in either of the two senses that matter:
 *
 *  - The **world** could not express an unreadable membership. Its config cell is nullable, so it modelled
 *    only *joined* and *absent* — the outcome three shipped bugs turned on was the one the harness could
 *    not reach.
 *  - The **decision** lived in each composition root, which is `iosMain` and untested by project rule. No
 *    test could reach it at all, which is why one tier had it and the other did not.
 *
 * The distinction under test is not academic. `NotJoined` runs the leave-side reconciliation, which clears
 * the persisted `joinedEventId` marker; the next readable cycle then sees a mismatch and pays for a full
 * re-join — a device listing, an atomic ledger clear-and-seed, and a cursor reset forcing a complete
 * library re-enumeration (~110 ms of PhotoKit XPC per asset). Getting this wrong costs a settled join.
 */
class CycleEntryGateIntegrationTest {

    @Test
    fun an_unreadable_membership_does_not_clear_the_join_marker() = worldTest {
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("A")
        // Settle the join: a first readable cycle seeds the marker and uploads.
        w.runUploadCycle()
        assertEquals(eventId, w.marker.read(), "precondition: the join is settled")
        val cursorAfterSettle = w.discoveryStore.loadToken()
        assertNotNull(cursorAfterSettle, "precondition: a drained cycle advanced the cursor")

        // The device is now woken while its membership cannot be read — a boot with no unlock since.
        w.membershipUnreadable = true
        val result = w.runUploadCycle()

        assertEquals(CycleResult.COMPLETED, result, "an unreadable read is a clean no-op")
        assertEquals(
            eventId,
            w.marker.read(),
            "unreadable is not a leave: the marker of a device that never left must survive",
        )
        assertTrue(
            w.discoveryStore.loadToken().contentEquals(cursorAfterSettle),
            "the discovery cursor must not reset — resetting forces a full library re-enumeration",
        )
    }

    @Test
    fun an_unreadable_membership_uploads_nothing_and_touches_no_storage() = worldTest {
        val w = World()
        w.provision("E")
        w.addOwnAsset("A")
        w.membershipUnreadable = true

        w.runUploadCycle()

        assertEquals(emptyList(), w.platform.created.map { it.filename }, "no upload job")
        assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "no object landed")
        assertNull(w.ledgerBackend.get("A-primary.jpg"), "no ledger row")
        assertEquals(emptyList(), w.notified, "no notify")
    }

    // The other half of the gate: the fix must not turn a REAL leave into a skip. A leave that stopped
    // clearing the marker would leave the device claiming a membership it no longer has.
    @Test
    fun a_cleared_membership_still_drives_the_leave_path() = worldTest {
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("A")
        w.runUploadCycle()
        assertEquals(eventId, w.marker.read(), "precondition: the join is settled")

        // A real leave: the config is definitively gone, and readable.
        w.leave()
        w.runUploadCycle()

        assertNull(w.marker.read(), "a real leave still clears the join marker")
    }

    @Test
    fun a_readable_membership_still_uploads_so_the_gate_is_not_skipping_everything() = worldTest {
        // The control. A gate that declines every cycle is indistinguishable from a gate that works
        // unless the happy path is asserted alongside it — and a silently-skipped upload is this
        // project's defining failure mode.
        val w = World()
        w.provision("E")
        w.addOwnAsset("A")

        w.runUploadCycle()

        assertEquals(listOf("A-primary.jpg"), w.platform.created.map { it.filename })
    }

    @Test
    fun the_membership_is_re_read_each_cycle_so_the_skip_is_not_sticky() = worldTest {
        // The cycle is long-lived now. An unreadable read must not latch: the next cycle, once the device
        // is unlocked, has to resume normally.
        val w = World()
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
