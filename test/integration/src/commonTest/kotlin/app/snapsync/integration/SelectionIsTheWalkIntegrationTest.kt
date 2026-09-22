package app.snapsync.integration

import app.snapsync.model.LedgerState
import app.snapsync.model.PermissionStatus
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
import kotlinx.datetime.TimeZone
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Under a partial grant the selection is the walk (capability `limited-photo-access`; decision record
 * `changes/selection-is-the-walk`), over the REAL composed core.
 *
 * The first test replays the downgrade measured on an SE2 (iOS 26.6, 2026-09-22): four uploads in flight under a
 * full grant, access narrowed to two of the four photos, and every object landing on the backend while the
 * withheld extension was presented nothing — so the rows stayed `REQUESTED` and the screen read `Syncing` until
 * full access returned. The world has no extension; the landing without an acknowledgement is modelled by
 * depositing the bytes store-direct and never completing the jobs.
 */
class SelectionIsTheWalkIntegrationTest {

    @Test
    fun a_downgrade_withdraws_the_unselected_and_the_foreground_settles_the_selected() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.core.installPermissionSubscriptions()
            w.permission.set(PermissionStatus.GRANTED)
            w.provision("E")
            for (id in listOf("A", "B", "C", "D")) w.addOwnAsset(id)

            // Four jobs in flight under the full grant.
            w.runUploadCycle()
            for (id in listOf("A", "B", "C", "D")) {
                assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("$id-primary.jpg")?.state, "$id in flight")
            }

            // The network returns: every object lands, and no acknowledgement ever reaches the ledger.
            for (id in listOf("A", "B", "C", "D")) w.store.deposit(w.ownDeviceId, "$id-primary.jpg")

            // Access narrows to two of the four, and the selection is read.
            w.permission.set(PermissionStatus.LIMITED)
            w.changeSelection("A", "B")
            withTimeout(5_000) { w.ownGallery.admitted.first { it == setOf("A", "B") } }

            // The next cycle walks the read selection: the de-selected photos leave, in flight or not.
            w.runUploadCycle()
            assertNull(w.ledgerBackend.get("C-primary.jpg"), "de-selecting is deleting")
            assertNull(w.ledgerBackend.get("D-primary.jpg"), "even while its upload is in flight")
            assertEquals(
                setOf("A", "B"),
                w.store.manifestOf("E", w.ownDeviceId)?.assets?.map { it.assetId }?.toSet(),
                "the manifest lists only the selection",
            )
            assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("A-primary.jpg")?.state, "still unacknowledged")

            // Foreground: the backend is asked, and the selected uploads whose bytes it holds settle.
            w.core.foregroundFlow.run()
            assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
            assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("B-primary.jpg")?.state)
            assertNull(w.ledgerBackend.get("C-primary.jpg"), "the settle creates no row for a withdrawn photo")

            // And the screen reaches In sync over the selection, instead of Syncing until access returns.
            w.refreshStatus()
            w.ledgerCounts.refresh()
            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_unread_selection_withholds_the_cycle_and_deletes_nothing() = worldTest {
        // A cold launch under a partial grant: the selection has not been read yet. Collapsed to an empty
        // selection, the enqueue resolved every waiting row against it and deleted each one as gone.
        val w = World(this)
        w.core.installPermissionSubscriptions()
        w.permission.set(PermissionStatus.GRANTED)
        w.provision("E")
        w.addOwnAsset("A")
        w.platform.jobLimit = 0 // recorded DISCOVERED, no job: the row the old collapse deleted
        w.runUploadCycle()
        assertEquals(LedgerState.DISCOVERED, w.ledgerBackend.get("A-primary.jpg")?.state)

        w.permission.set(PermissionStatus.LIMITED) // no selection read yet
        w.platform.jobLimit = Int.MAX_VALUE
        w.runUploadCycle()

        assertEquals(LedgerState.DISCOVERED, w.ledgerBackend.get("A-primary.jpg")?.state, "no row is deleted")
        assertTrue(w.platform.created.isEmpty(), "and nothing is created while the selection is unread")

        // Once the selection is read the cycle runs over it.
        w.changeSelection("A")
        withTimeout(5_000) { w.ownGallery.admitted.first { it == setOf("A") } }
        w.runUploadCycle()
        assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("A-primary.jpg")?.state)
    }

    @Test
    fun re_selecting_a_withdrawn_photo_shares_it_again() = worldTest {
        val w = World(this)
        w.core.installPermissionSubscriptions()
        w.permission.set(PermissionStatus.LIMITED)
        w.provision("E")
        w.addOwnAsset("A")
        w.addOwnAsset("B")

        w.changeSelection("A", "B")
        withTimeout(5_000) { w.ownGallery.admitted.first { it == setOf("A", "B") } }
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.platform.completeJob("B-primary.jpg")
        w.runUploadCycle()
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("B-primary.jpg")?.state)

        w.changeSelection("A")
        withTimeout(5_000) { w.ownGallery.admitted.first { it == setOf("A") } }
        w.runUploadCycle()
        assertNull(w.ledgerBackend.get("B-primary.jpg"), "withdrawn")
        assertEquals(setOf("A"), w.store.manifestOf("E", w.ownDeviceId)?.assets?.map { it.assetId }?.toSet())

        w.changeSelection("A", "B")
        withTimeout(5_000) { w.ownGallery.admitted.first { it == setOf("A", "B") } }
        w.runUploadCycle()
        assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("B-primary.jpg")?.state, "uploaded again")
        assertEquals(
            setOf("A", "B"),
            w.store.manifestOf("E", w.ownDeviceId)?.assets?.map { it.assetId }?.toSet(),
            "and listed again",
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
        ),
        scope = scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
    )

    private fun UiState.health(): SyncHealth? = (this.layer as? Layer.Joined)?.health

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}
