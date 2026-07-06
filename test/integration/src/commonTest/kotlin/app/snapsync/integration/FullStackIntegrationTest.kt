package app.snapsync.integration

import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import app.snapsync.engine.LedgerState
import app.snapsync.permission.PermissionRequester
import app.snapsync.presentation.Arrow
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.LedgerCounts
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam ↔ UI-state integration over the REAL `engine → status → presentation` stack driven against the
 * world (`:test:world`), asserting **`UiState` AND world outcomes** — objects landed in the store,
 * ledger rows `COMPLETED`, foreign photos imported — from world mutations + cycle invocations. Runs on
 * JVM and `iosSimulatorArm64`.
 */
class FullStackIntegrationTest {

    @Test
    fun upload_completion_advances_uistate_and_world_outcomes() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E")
            w.addOwnAsset("A")
            w.ownGallery.refresh(); w.ledgerCounts.refresh()

            val host = statusHost(w, scope)
            // total = 1, nothing uploaded yet, no job created → Syncing with a static up arrow.
            assertEquals(
                UiState.Joined(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN)),
                host.await { it.health() is SyncHealth.Syncing },
            )

            // Run one cycle: the job is created → a REQUESTED (in-flight) ledger row → the up arrow pulses.
            w.runUploadCycle()
            w.ledgerCounts.refresh()
            host.await { (it.health() as? SyncHealth.Syncing)?.upload == Arrow.PULSING }

            // Operator completes the job (store-direct deposit) → next cycle acks → COMPLETED → settled.
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.ownGallery.refresh(); w.ledgerCounts.refresh()
            assertEquals(UiState.Joined(SyncHealth.InSync), host.await { it.health() is SyncHealth.InSync })

            // World outcomes (not UiState alone):
            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun create_event_lifts_the_setup_gate() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World() // no config → the create layer
            val host = StatusContainerHost(
                syncSource = w.syncStatusSource(scope),
                permissionSource = w.permission,
                requester = NoOpRequester,
                configSource = w.configSource,
                store = NoOpConfigStore,
                scope = scope,
                creationStatusSource = w.creationStatus,
                creator = w.createEvent(scope),
            )
            assertEquals(UiState.CreateEvent(), host.container.stateFlow.value)

            w.ownGallery.refresh(); w.ledgerCounts.refresh()
            host.onCreateEvent("Party") // POST /events via the mini-edge → provision → gate lifts
            val after = host.await { it !is UiState.CreateEvent && it !is UiState.CreatingEvent }
            assertEquals(UiState.Joined(SyncHealth.InSync), after) // no photos in the library → settled
            assertTrue(w.configSource.config.value != null) // world outcome: config provisioned
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun foreign_download_imports_and_own_status_excludes_it() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E")
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isNotEmpty()) // world outcome: foreign photo imported

            w.ownGallery.refresh(); w.ledgerCounts.refresh()
            val host = statusHost(w, scope)
            // The imported foreign asset is suppressed from the OWN upload universe → own status settled.
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun upload_completeness_is_ledger_local_and_backend_independent() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E")
            w.addOwnAsset("A")
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.ledgerCounts.refresh()
            assertEquals(LedgerCounts(completed = 1, pending = 0), w.ledgerCounts.counts.value)

            // Upload completeness is the local ledger, not a storage LIST — backend-offline changes
            // nothing (the read never touches the network).
            w.backendOffline = true
            w.ledgerCounts.refresh()
            assertEquals(LedgerCounts(completed = 1, pending = 0), w.ledgerCounts.counts.value)
            // The download union read still fails offline, without throwing (keeps last state).
            w.downloadController.reconcile("E")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun leaving_as_the_last_member_returns_to_the_setup_gate_and_reaps_the_event() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E")
            w.addOwnAsset("A")
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.ownGallery.refresh(); w.ledgerCounts.refresh()
            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))

            // The real container with leave wired to the world's faithful leave (real DELETE seam).
            val host = StatusContainerHost(
                syncSource = w.syncStatusSource(scope),
                permissionSource = w.permission,
                requester = NoOpRequester,
                configSource = w.configSource,
                store = NoOpConfigStore,
                scope = scope,
                creationStatusSource = w.creationStatus,
                creator = w.createEvent(scope),
                leave = { w.leave() },
            )
            host.await { it is UiState.Joined }

            host.onLeaveEvent()

            // UiState reduces to the setup gate; world outcomes: the event is reaped (own was the last
            // member) and its orphaned bytes are GC'd.
            assertEquals(UiState.CreateEvent(), host.await { it is UiState.CreateEvent })
            assertEquals(null, w.configSource.config.value)
            assertFalse(w.store.isRegistered("E"))
            assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        syncSource = w.syncStatusSource(scope),
        permissionSource = w.permission,
        requester = NoOpRequester,
        configSource = w.configSource,
        store = NoOpConfigStore,
        scope = scope,
    )

    private fun UiState.health(): SyncHealth? = (this as? UiState.Joined)?.health

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

private object NoOpRequester : PermissionRequester {
    override fun request() = Unit
    override fun openSettings() = Unit
}

private object NoOpConfigStore : ConfigStore {
    override suspend fun save(config: EventConfig) = Unit
    override suspend fun clear() = Unit
}
