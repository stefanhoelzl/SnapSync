package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The **bounded staleness** pins, over the real composed stack, driven through the
 * control protocol.
 *
 * These cover a defect that every other test in the repository was blind to, for a structural reason:
 * the world harness refreshes the status sources after every operator action, so a projection that goes
 * stale between refreshes is unreachable there. The screen therefore looked correct — before and after —
 * while it could hold a checkmarked **"In sync"** over an outstanding burst for a whole foreground
 * session.
 *
 * The mechanism, and why re-ordering is not the fix. `Foreground` launches the status refresh and the
 * download reconcile as **siblings**, deliberately: sequencing the refresh behind its siblings is
 * forbidden, because a cycle's discovery walk can stay outstanding for 774 s and a member's
 * whole visit can be shorter than that. So the refresh typically reads the download projection *before*
 * the reconcile's union fetch has planned anything, and the only available repair is a later **re-read**.
 *
 * That re-read is the foreground-gated poll — and it used to tick the ledger arm alone. The arrows are
 * conjunctive, so bounding one arm never half-bounded the screen; it left the screen unbounded through
 * the other.
 *
 * These start the real poll the way the phone does — the foreground entry — at its real cadence, and assert
 * what a member actually sees. The load-bearing part of each is that **no status read** is requested between
 * the stale read and the assertion: no `status/refresh`, no second foreground.
 */
@Verifies(spec = "sync-status", requirement = "The status stays current while the app is open")
class BoundedStatusStalenessIntegrationTest {

    @Test
    fun a_burst_discovered_after_the_entry_read_does_not_leave_the_screen_settled() = behaviour {
        given {
            joinedEvent()
            // The foreground entry read: counted zeros on both arms, so the screen legitimately settles. The entry
            // also starts the foreground-gated poll.
            step("in front, in sync", act = { os.foreground() }, until = {
                screen<Layer.Joined> { it.health == SyncHealth.InSync }
                settled { true }
            })
        }

        // Discovery lands AFTER that read — the order `Foreground` actually produces, since the status refresh does not
        // wait for the reconcile's union fetch. A fellow member's photo appears, and a reconcile plans its download.
        //
        // From there NOTHING reads the status: no refresh, no trigger. The poll is the only thing running, exactly as
        // it is while a member watches the screen. Before this change it ticked the ledger arm alone and this stayed
        // "In sync" for the rest of the session. The clock moves past the poll's cadence on purpose: what is asserted
        // is that the screen corrects itself ON A TICK.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FA"))
            os.foreground()
            advance(COUNTS_POLL)
        }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } }
    }

    @Test
    fun an_import_that_lands_mid_foreground_reaches_the_screen_within_the_bound() = behaviour {
        given {
            joinedEvent()
            fellowMember(DEV_F, "FA")
            // A read while the burst is outstanding: the download arm holds the screen at "Syncing". The foreground
            // entry reads the status, plans the download, and starts the poll, whose next tick reads it again.
            step("in front, its download planned", act = {
                os.foreground()
                advance(COUNTS_POLL)
            }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.Syncing }
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        // The asset arrives. This is the download arm's own progress — no ledger change, no upload, nothing the other
        // arm's poll would ever have noticed — and nothing reads the status but the poll.
        whenever {
            downloadSession.finish()
            advance(COUNTS_POLL)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    private companion object {
        /** Past the joined screen's next read of the counts. */
        val COUNTS_POLL = 5.seconds
    }
}
