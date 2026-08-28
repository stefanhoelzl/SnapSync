package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.presentation.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The **un-read status** pins (capability `sync-status`), over the REAL composed core.
 *
 * These exist because the defect they cover was invisible to every other test in the repository. The
 * status projection reads three `StateFlow`s, and a `StateFlow` always has a value — so `combine`
 * emitted on its first dispatch, minting a snapshot from three placeholder zeros. `syncHealth` hides a
 * direction arrow when `synced >= total`, `0 >= 0` holds on BOTH arms, and the joined screen rendered a
 * check mark reading **"In sync"** on a device that had read nothing. Members reported it as the status
 * going backwards across launches, with no photos taken in between (`SNAPSYNC-14`, `SNAPSYNC-16`).
 *
 * No unit test could reach that state: the fakes seeded counts, so "never refreshed" was unreachable.
 * These tests therefore drive the composed graph — `snapSyncApp` over `:test:world` — and assert the
 * frame a member actually sees **before** any refresh has run, which is precisely the cold-launch frame.
 *
 * The load-bearing assertion in each is the NEGATIVE one: never `InSync` before a read.
 */
class UnreadStatusIntegrationTest {

    @Test
    fun a_joined_membership_with_photos_never_reads_in_sync_before_a_status_read() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E")
            w.addOwnAsset("A")
            // NO refreshStatus() — this is the cold-launch state: a joined membership, photos in the
            // library, and nothing read yet.

            val host = statusHost(w, scope)

            // The neutral first frame, and it STAYS neutral: nothing has been counted, so there is
            // nothing to be settled about. Before the fix the projection minted a snapshot from three
            // placeholder zeros and this read `Joined(InSync)` within a dispatch.
            host.neverSettlesWithin()
            assertEquals(SyncHealth.Loading, (host.container.stateFlow.value).health())

            // Still neutral after work happens — because work is not a READ.
            w.runUploadCycle()
            host.neverSettlesWithin()

            // Once the counts are read the truth arrives — and it is "still working", not "settled".
            // This also proves the collector was alive the whole time, so the waits above were a real
            // absence of `InSync` rather than a dead projection.
            w.refreshStatus()
            assertIs<SyncHealth.Syncing>(host.await { it.health() is SyncHealth.Syncing }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_counted_zero_still_settles_the_screen() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            // A download-only membership contributes nothing, so its upload total is a COUNTED zero —
            // reached on `SelectionPolicy.None`'s own branch without enumerating. The fix must not turn
            // that legitimate settled state into a permanent "Syncing…" (design D3).
            w.provision("E", direction = Direction.DownloadOnly)
            w.addOwnAsset("A") // in the library, contributes nothing — must not hold the screen open
            w.refreshStatus()

            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_un_read_download_arm_alone_holds_the_screen_out_of_in_sync() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            // Upload-only, no photos: the upload arm reads a counted zero the moment it is refreshed,
            // so the UPLOAD side is settled and only the download arm's read-ness is left to decide the
            // frame. If an un-read download projection could hide its arrow, this would read `InSync` —
            // the defect relocated to the other arm (design D2).
            w.provision("E", direction = Direction.UploadOnly)
            w.ownGallery.refresh(w.selectionPolicy())
            w.ledgerCounts.refresh()
            // Deliberately NOT w.refreshStatus(): the download projection stays un-read.

            val host = statusHost(w, scope)
            host.neverSettlesWithin()
            assertEquals(SyncHealth.Loading, (host.container.stateFlow.value).health())

            // Reading it settles the screen — the arm was empty all along, but that had to be READ.
            // The transition also proves the projection was live during the wait above.
            w.downloadStatusSource.refresh()
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed enumeration leaves the total unknown and does not take its siblings down`() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E")
            w.addOwnAsset("A")

            // A platform walk can throw. `refreshStatusSources` bounds it, because the refresh runs as
            // one child of the Foreground flow's `coroutineScope` — an escaping failure would cancel the
            // download reconcile, the staged-byte reclaim and the membership refresh, none of which have
            // anything to do with enumerating a library (design D5).
            w.gallery.failNextEnumeration = true
            w.refreshStatus() // must NOT throw

            // The consequence, named rather than hidden: the total stays UNKNOWN, so the screen holds
            // its neutral line. It is deliberately not collapsed to `0`, which would read "In sync".
            assertNull(w.ownGallery.size.value)
            val host = statusHost(w, scope)
            host.neverSettlesWithin()

            // The cheap sibling reads still landed — they run before the walk and are unaffected by it.
            assertTrue(w.ledgerCounts.counts.value.read)

            // And the next refresh recovers: the lever is one-shot, like a transient platform failure.
            w.refreshStatus()
            assertEquals(1, w.ownGallery.size.value)
            assertIs<SyncHealth.Syncing>(host.await { it.health() is SyncHealth.Syncing }.health())
        } finally {
            scope.cancel()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
            // The REAL store-backed download projection, as the iOS shell injects it — not the host's
            // read-empty default, which would settle the download arm for free and defeat the third test.
            download = w.downloadStatusSource,
        ),
        scope = scope,
        cutoffFormatter = fixedCutoffFormatter(),
    )

    private fun UiState.health(): SyncHealth? = (this.layer as? Layer.Joined)?.health

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }

    /**
     * Assert the screen does NOT reach the settled "In sync" frame within [millis].
     *
     * A bounded *negative* assertion, because `worldTest` runs on real time (no virtual clock) and the
     * projection publishes through a `combine` collector — so reading `stateFlow.value` immediately
     * after constructing the host proves nothing: it can be `Loading` merely because the collector has
     * not dispatched yet. That is the trap these tests exist to avoid, and it is why each one follows
     * this with a positive transition that proves the collector was alive.
     */
    private suspend fun StatusContainerHost.neverSettlesWithin(millis: Long = 500) {
        val settled = withTimeoutOrNull(millis) {
            container.stateFlow.first { it.health() is SyncHealth.InSync }
        }
        assertNull(settled, "the screen claimed \"In sync\" before any count was read")
    }
}

private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
