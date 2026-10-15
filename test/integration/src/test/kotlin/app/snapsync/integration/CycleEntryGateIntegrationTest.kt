package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cycle's **entry gate** over the real stack — an unreadable config is not an absent config: the real
 * `UploadCycle` and ledger over the backend mock, driven through the control protocol, with the phone locked since it
 * restarted (`device.lock`) — the Keychain, the databases the membership lives in and the files, all sealed at once.
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

    /**
     * Before the first unlock after a boot every protected store is closed: the databases the membership lives in, the
     * files and the Keychain with them.
     */
    private suspend fun Given.membershipUnreadable() =
        step("the membership unreadable", act = { device.lock() }, until = {})

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A locked phone delays uploads and never ends the membership",
        scenario = "A wake before the first unlock after a restart",
    )
    @Verifies(
        spec = "manage-membership",
        requirement = "A membership survives updates, restarts, and a locked phone",
        scenario = "A locked phone never looks like a leave",
    )
    fun an_unreadable_membership_leaves_the_ledger_untouched() = behaviour {
        // Weakened from "the ledger rows are byte-identical" (the ledger is internal) to its observable twins: the
        // device stays joined, nothing is uploaded and no job is created while unreadable — and once readable again,
        // the settled photo is NOT re-queued, which a cleared ledger would do.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            uploaded("A") // settle the join: a first readable cycle records and uploads
            // The device is now woken while its membership cannot be read — a boot with no unlock since.
            membershipUnreadable()
            photos("B")
        }

        var answer = ""
        whenever { answer = cycle() }
        then {
            never<WorldEvent.UploadJobCreated>() // no upload job while the membership is unreadable
            never<WorldEvent.BytesStored>() // nothing uploaded while the membership is unreadable
            never<WorldEvent.MemberLeft>() // unreadable is not a leave
            neverScreen<Layer.CreateEvent>()
        }
        // The whole App Group is closed, the ledger too, so the extension's after-cycle read of what is still in flight
        // fails and it answers `failed` — what a device locked since boot answers: it uploaded and claimed nothing.
        assertEquals("failed", answer, "an unreadable device uploads nothing and claims nothing")

        // The device of a membership that never left keeps what it settled: the next readable cycle queues only the
        // new photo, never the one already uploaded.
        whenever {
            device.unlock()
            cycle()
        }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() }
            settled { it.state.inFlight?.jobs == listOf(primaryJob("B")) } // the settled photo was not re-queued
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "A locked phone delays uploads and never ends the membership")
    fun an_unreadable_membership_uploads_nothing_and_touches_no_storage() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            membershipUnreadable()
        }

        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>() // no upload job
            never<WorldEvent.BytesStored>() // no object landed
            // The publish is what announces a device's asset set now (there is no notify route on the
            // versioned device API), so "touched no storage" has to mean the cycle published nothing — not
            // merely that the resulting manifest looks unchanged, which a republish would satisfy too.
            never<WorldEvent.ManifestPublished>()
        }
    }

    // The other half of the gate: the fix must not turn a REAL leave into a skip. A definitively absent
    // membership reads as not joined, and the cycle uploads nothing for it.
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Leaving or switching stops every transfer for the old event",
        scenario = "Leaving stops uploads in progress",
    )
    fun a_cleared_membership_reads_as_not_joined_and_uploads_nothing() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            step("A requested", act = { cycle() }, until = {
                event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
                settled { true }
            })
            // A real leave: the config is definitively gone, and readable.
            step("left", act = { tap(UiIntent.LeaveEvent) }, until = {
                event<WorldEvent.MemberLeft>()
                settled { true }
            })
            photos("B")
        }

        var answer = ""
        whenever { answer = cycle() }
        then { never<WorldEvent.UploadJobCreated>() } // a device that left uploads nothing
        // Declined, so the system is told to rest: with no membership nothing re-arms a wake for no event.
        assertEquals("completed", answer)
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Photos travel without the app being opened")
    fun a_readable_membership_still_uploads_so_the_gate_is_not_skipping_everything() = behaviour {
        // The control. A gate that declines every cycle is indistinguishable from a gate that works
        // unless the happy path is asserted alongside it — and a silently-skipped upload is this
        // project's defining failure mode.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
        }

        whenever { cycle() }
        then { settled { it.state.inFlight?.jobs == listOf(primaryJob("A")) } }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A locked phone delays uploads and never ends the membership",
        scenario = "A wake before the first unlock after a restart",
    )
    fun the_membership_is_re_read_each_cycle_so_the_skip_is_not_sticky() = behaviour {
        // The cycle is long-lived now. An unreadable read must not latch: the next cycle, once the device
        // is unlocked, has to resume normally.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            membershipUnreadable()
            step("a skipped cycle", act = { cycle() }, until = {
                never<WorldEvent.UploadJobCreated>() // precondition: skipped
            })
        }

        whenever {
            device.unlock() // the user unlocked
            cycle()
        }
        then { settled { it.state.inFlight?.jobs == listOf(primaryJob("A")) } } // the next cycle resumes
    }
}
