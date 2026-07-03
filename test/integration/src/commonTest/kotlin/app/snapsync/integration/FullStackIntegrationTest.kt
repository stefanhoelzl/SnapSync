package app.snapsync.integration

import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.engine.LedgerState
import app.snapsync.permission.PermissionRequester
import app.snapsync.presentation.StatusContainerHost
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
            w.completed.refresh(); w.inFlight.refresh()

            val host = statusHost(w, scope)
            // total = 1, nothing uploaded yet → InProgress(0 of 1).
            assertEquals(UiState.InProgress(synced = 0, total = 1, inProgress = 0), host.await { it is UiState.InProgress })

            // Run one cycle: the job is created → a REQUESTED (in-flight) ledger row.
            w.runUploadCycle()
            w.inFlight.refresh()
            host.await { it is UiState.InProgress && it.inProgress == 1 }

            // Operator completes the job (store-direct deposit) → next cycle acks → COMPLETED.
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.completed.refresh(); w.inFlight.refresh()
            assertEquals(UiState.Completed(total = 1), host.await { it is UiState.Completed })

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

            w.completed.refresh(); w.inFlight.refresh()
            host.onCreateEvent("Party") // POST /event via the mini-edge → provision → gate lifts
            val after = host.await { it !is UiState.CreateEvent && it !is UiState.CreatingEvent }
            assertTrue(after is UiState.NothingToSync) // no photos in the library
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

            w.completed.refresh(); w.inFlight.refresh()
            val host = statusHost(w, scope)
            // The imported foreign asset is suppressed from the OWN upload universe → nothing to sync.
            assertTrue(host.await { it is UiState.NothingToSync || it is UiState.InProgress } is UiState.NothingToSync)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backend_offline_keeps_last_good_completed() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E")
            w.addOwnAsset("A")
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.completed.refresh()
            assertEquals(setOf("A"), w.completed.completed.value)

            // Backend-offline: the listing fails, the last-good completed set is kept (never blanked).
            w.backendOffline = true
            w.completed.refresh()
            assertEquals(setOf("A"), w.completed.completed.value)
            // The download union read also fails offline, without throwing (keeps last state).
            w.downloadController.reconcile("E")
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

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

private object NoOpRequester : PermissionRequester {
    override fun request() = Unit
    override fun openSettings() = Unit
}

private object NoOpConfigStore : ConfigStore {
    override suspend fun save(config: EventConfigPayload) = Unit
    override suspend fun clear() = Unit
}
