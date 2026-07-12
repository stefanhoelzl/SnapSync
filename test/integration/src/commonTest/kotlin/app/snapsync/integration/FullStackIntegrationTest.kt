package app.snapsync.integration

import app.snapsync.config.ConfigStore
import app.snapsync.config.Direction
import app.snapsync.config.EventConfig
import app.snapsync.engine.LedgerState
import app.snapsync.membership.LeaveEvent
import app.snapsync.permission.PermissionRequester
import app.snapsync.presentation.Arrow
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.status.LedgerCounts
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlinx.coroutines.CompletableDeferred
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
            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()

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
            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()
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

            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()
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

            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()
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
            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()
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

    @Test
    fun upload_only_uploads_own_but_imports_no_foreign() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E", direction = Direction.UploadOnly)
            w.addOwnAsset("A")
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            // Upload arm runs (the operator invokes the cycle): the own object lands and completes.
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId), "upload-only still uploads own photos")

            // Download arm is gated off: reconcile is a no-op, so nothing foreign is enqueued or imported.
            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isEmpty(), "upload-only imports no foreign photos")

            // Status: uploads complete + download masked → In sync.
            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()
            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun download_only_imports_foreign_but_uploads_nothing_and_masks_the_upload_arrow() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E", direction = Direction.DownloadOnly)
            w.addOwnAsset("A") // an un-uploaded own photo remains in the gallery
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            // Download arm runs and imports the foreign asset.
            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isNotEmpty(), "download-only imports foreign photos")

            // Upload arm is disabled — the producer never runs, so no own object lands.
            assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "download-only uploads nothing")

            // Status: the upload arrow is masked, so an un-uploaded gallery does NOT keep it out of sync.
            w.ownGallery.refresh(World.DEFAULT_CUTOFF); w.ledgerCounts.refresh()
            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun leaving_flips_the_screen_before_the_backend_delete_completes() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision("E")

            // The REAL leave use-case, with the backend DELETE gated so it never completes — the flip
            // must not wait on it. `stopUploads` is a no-op (local); the clear drives the reduction.
            val deleteGate = CompletableDeferred<Unit>()
            val notifyStarted = CompletableDeferred<String>()
            val leaveEvent = LeaveEvent(
                config = w.configStore,
                configSource = w.configSource,
                stopUploads = {},
                notifyLeave = { id -> notifyStarted.complete(id); deleteGate.await() /* hangs */ },
                scope = scope,
            )
            val host = StatusContainerHost(
                syncSource = w.syncStatusSource(scope),
                permissionSource = w.permission,
                requester = NoOpRequester,
                configSource = w.configSource,
                store = NoOpConfigStore,
                scope = scope,
                creationStatusSource = w.creationStatus,
                creator = w.createEvent(scope),
                leave = { leaveEvent.leave() },
            )
            host.await { it is UiState.Joined }

            host.onLeaveEvent()

            // The screen leaves the joined layer immediately — even though the DELETE is still pending.
            assertEquals(UiState.CreateEvent(), host.await { it is UiState.CreateEvent })
            assertEquals(null, w.configSource.config.value)
            // The notify WAS dispatched (with the snapshotted eventId) — but the flip did not wait on it.
            assertEquals("E", withTimeout(5_000) { notifyStarted.await() })
            assertFalse(deleteGate.isCompleted)
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
