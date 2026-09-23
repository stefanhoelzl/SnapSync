package app.snapsync.integration

import app.snapsync.presentation.SyncHealth
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The **bounded staleness** pins (capability `sync-status`), over the real composed stack, driven through the
 * control protocol.
 *
 * These cover a defect that every other test in the repository was blind to, for a structural reason:
 * the world harness refreshes the status sources after every operator action, so a projection that goes
 * stale between refreshes is unreachable there. The screen therefore looked correct — before and after —
 * while it could hold a checkmarked **"In sync"** over an outstanding burst for a whole foreground
 * session.
 *
 * The mechanism, and why re-ordering is not the fix. `Foreground` launches the status refresh and the
 * download reconcile as **siblings**, deliberately: sequencing the refresh behind its siblings is what
 * `sync-status` forbids, because a cycle's discovery walk can stay outstanding for 774 s and a member's
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
class BoundedStatusStalenessIntegrationTest {

    @Test
    fun a_burst_discovered_after_the_entry_read_does_not_leave_the_screen_settled() = rigTest {
        createAndJoin()

        // The foreground entry read: counted zeros on both arms, so the screen legitimately settles. The entry
        // also starts the foreground-gated poll.
        os("app", "onForeground")
        awaitInSync()

        // Discovery lands AFTER that read — the order `Foreground` actually produces, since the status refresh
        // does not wait for the reconcile's union fetch. A fellow member's photo appears, and a reconcile plans
        // its download.
        foreignDevice("DEV-F", "FA")
        reconcile()

        // From here NOTHING reads the status: no refresh, no trigger. The poll is the only thing running,
        // exactly as it is while a member watches the screen. Before this change it ticked the ledger arm alone
        // and this stayed "In sync" for the rest of the session. The wait exceeds the poll's cadence on purpose:
        // what is asserted is that the screen corrects itself ON A TICK.
        assertIs<SyncHealth.Syncing>(awaitHealth { it is SyncHealth.Syncing })

        os("app", "onBackground") // the poll stops with the foreground
    }

    @Test
    fun an_import_that_lands_mid_foreground_reaches_the_screen_within_the_bound() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FA")

        // A read while the burst is outstanding: the download arm holds the screen at "Syncing". The foreground
        // entry reads the status, plans the download, and starts the poll.
        os("app", "onForeground")
        awaitHealth { it is SyncHealth.Syncing }

        // The asset arrives. This is the download arm's own progress — no ledger change, no upload, nothing the
        // other arm's poll would ever have noticed.
        stage()

        assertIs<SyncHealth.InSync>(awaitHealth { it is SyncHealth.InSync })

        os("app", "onBackground")
    }
}
