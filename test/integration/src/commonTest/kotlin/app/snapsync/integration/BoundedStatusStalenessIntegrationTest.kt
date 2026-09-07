package app.snapsync.integration

import app.snapsync.ports.AssetRef
import app.snapsync.ports.PlannedResource
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.Layer
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The **bounded staleness** pins (capability `sync-status`), over the REAL composed core.
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
 * These tests drive the real `StatusCountsPoller` from the composed graph at its real cadence, and assert
 * what a member actually sees. The load-bearing part of each is that **nothing else is called** between
 * the stale read and the assertion: no refresh, no trigger, no operator action.
 */
class BoundedStatusStalenessIntegrationTest {

    private val foreign = AssetRef("DEV-F", "FA")

    @Test
    fun a_burst_discovered_after_the_entry_read_does_not_leave_the_screen_settled() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E")
            val host = statusHost(w, scope)

            // The foreground entry read: counted zeros on both arms, so the screen legitimately settles.
            w.refreshStatus()
            assertIs<SyncHealth.InSync>(host.await { it.health() is SyncHealth.InSync }.health())

            // Discovery lands AFTER that read — the order `Foreground` actually produces, since the
            // status refresh does not wait for the reconcile's union fetch.
            w.planForeign()

            // From here NOTHING is called: no refreshStatus, no trigger, no operator action. The poll is
            // the only thing running, exactly as it is while a member watches the screen. Before this
            // change it ticked the ledger arm alone and this stayed "In sync" for the rest of the session.
            w.core.statusCountsPoller.start()
            assertIs<SyncHealth.Syncing>(host.await { it.health() is SyncHealth.Syncing }.health())
            w.core.statusCountsPoller.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_import_that_lands_mid_foreground_reaches_the_screen_within_the_bound() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E")
            w.planForeign()
            val host = statusHost(w, scope)

            // A read while the burst is outstanding: the download arm holds the screen at "Syncing".
            w.refreshStatus()
            assertIs<SyncHealth.Syncing>(host.await { it.health() is SyncHealth.Syncing }.health())

            w.core.statusCountsPoller.start()
            // The asset arrives. This is the download arm's own progress — no ledger change, no upload,
            // nothing the other arm's poll would ever have noticed.
            w.downloadStore.markImported(foreign, "LOCAL-FA")

            assertIs<SyncHealth.InSync>(host.await { it.health() is SyncHealth.InSync }.health())
            w.core.statusCountsPoller.stop()
        } finally {
            scope.cancel()
        }
    }

    /** One planned foreign asset — a burst of one, which is all the arrows need to leave "In sync". */
    private suspend fun World.planForeign() = downloadStore.plan(
        foreign,
        "2026-06-30T10:00:00Z",
        listOf(
            PlannedResource(
                resourceKey = "FA-primary.heic",
                url = "https://example.invalid/FA-primary.heic",
                role = "primary",
                contentType = "image/heic",
                originalFilename = "FA.heic",
            ),
        ),
    )

    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
            // The REAL store-backed projection, as the iOS shell injects it — the host's read-empty
            // default would settle the download arm for free and make both tests vacuous.
            download = w.downloadStatusSource,
        ),
        scope = scope,
        cutoffFormatter = fixedCutoffFormatter(),
    )

    private fun UiState.health(): SyncHealth? = (this.layer as? Layer.Joined)?.health

    /**
     * The timeout exceeds the poll's cadence on purpose: what is being asserted is that the screen
     * corrects itself **on a tick**, so a bound shorter than one cadence would assert the opposite.
     */
    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(10_000) { container.stateFlow.first(predicate) }
}

private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
