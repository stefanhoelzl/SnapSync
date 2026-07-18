package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.model.LedgerState
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.model.Arrow
import app.snapsync.model.UserCommands
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.presentation.SyncHealth
import app.snapsync.presentation.UiState
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam ↔ UI-state integration over the REAL `engine → status → presentation` stack driven against the
 * world (`:test:world`), asserting **`UiState` AND world outcomes** — objects landed in the store,
 * ledger rows `COMPLETED`, foreign photos imported — from world mutations + cycle invocations. Runs on
 * JVM and `iosSimulatorArm64`.
 */
class FullStackIntegrationTest {

    @Test
    fun a_future_start_event_uploads_nothing_and_reads_not_started() = worldTest {
        // THE THEOREM the whole design rests on (capability `photo-selection-policy`).
        //
        // Nothing syncs before the event starts — and NOT because a gate refuses. There is no gate. The
        // join-time clamp makes the effective cutoff `max(chosen, startsAt)`, and a photo's capture date
        // cannot lie in the future, so while `minPhotoDate >= startsAt > now` NO asset can satisfy
        // `creationDate >= minPhotoDate`. `UploadCycle` is untouched by this change; the emptiness below
        // is a consequence, not a feature.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            val future = "2099-12-31T23:59:59Z"
            // Pre-start, the clamp yields `minPhotoDate == startsAt` whatever the member chose.
            w.provision("E", minPhotoDate = future, startsAt = future)
            w.addOwnAsset("A") // dated DEFAULT_DATE (2026) — long before the event begins
            w.refreshStatus()

            val host = statusHost(w, scope)
            assertEquals(
                UiState.Joined(SyncHealth.NotStarted(future)),
                host.await { it.health() is SyncHealth.NotStarted },
            )

            // Run a cycle anyway: the real stack, the real upload cycle, no special-casing.
            w.runUploadCycle()
            w.refreshStatus()

            // World outcomes, not UiState alone: nothing was admitted, nothing was queued, nothing landed.
            assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "no object may land before the event starts")
            assertNull(w.ledgerBackend.get("A-primary.jpg"), "the asset never even reached the ledger")
            assertEquals(
                UiState.Joined(SyncHealth.NotStarted(future)),
                host.await { it.health() is SyncHealth.NotStarted },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_same_event_uploads_normally_once_its_start_is_in_the_past() = worldTest {
        // The mirror of the theorem: with the start in the past the floor binds nothing, and the very same
        // stack uploads exactly as it did before start dates existed.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", minPhotoDate = World.DEFAULT_CUTOFF, startsAt = World.DEFAULT_STARTS_AT)
            w.addOwnAsset("A")
            w.refreshStatus()

            val host = statusHost(w, scope)
            host.await { it.health() is SyncHealth.Syncing } // NOT NotStarted

            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.refreshStatus()

            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertEquals(UiState.Joined(SyncHealth.InSync), host.await { it.health() is SyncHealth.InSync })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun upload_completion_advances_uistate_and_world_outcomes() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E")
            w.addOwnAsset("A")
            w.refreshStatus()

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
            w.refreshStatus()
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
            val w = World(this) // no config → the create layer
            val host = StatusContainerHost(
                syncSource = w.syncStatusSource,
                permission = w.permission.permission,
                config = w.configSource.config,
                scope = scope,
                cutoffFormatter = fixedCutoffFormatter(),
                creationStatusSource = w.creationStatus,
                // The COMPOSED user-tap bundle (migration step 10): create routes through the real
                // `AppCore.eventCreator`; the world's default `onEventMinted` provisions directly.
                commands = w.userCommands,
            )
            assertEquals(UiState.CreateEvent(), host.container.stateFlow.value)

            w.refreshStatus()
            host.onCreateEvent("Party", LocalDateTime(2026, 1, 1, 0, 0)) // POST /events → provision → gate lifts
            // Await the SETTLED health, not merely "left the create layer": the snapshot's first read is
            // itself asynchronous (`LedgerBackedSyncStatusSource` seeds `Loading` and reaches `Ready`
            // only once its collector runs), so `Joined(Loading)` — the neutral first frame — is a
            // legitimate state between the gate lifting and the snapshot landing. A predicate that
            // accepts it races the first read and asserts against a frame that is not settled yet.
            val after = host.await { it.health() is SyncHealth.InSync }
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
            val w = World(this)
            w.provision("E")
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isNotEmpty()) // world outcome: foreign photo imported

            w.refreshStatus()
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
            val w = World(this)
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
            val w = World(this)
            w.provision("E")
            w.addOwnAsset("A")
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.refreshStatus()
            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))

            // The real container over the COMPOSED bundle (migration step 10): leave runs the
            // production ordering — cancel downloads, stop the producer, clear config, then notify
            // the backend FIRE-AND-FORGET on the composition scope.
            val host = StatusContainerHost(
                syncSource = w.syncStatusSource,
                permission = w.permission.permission,
                config = w.configSource.config,
                scope = scope,
                cutoffFormatter = fixedCutoffFormatter(),
                creationStatusSource = w.creationStatus,
                commands = w.userCommands,
            )
            host.await { it is UiState.Joined }

            host.onLeaveEvent()

            // UiState reduces to the setup gate the instant the config clears...
            assertEquals(UiState.CreateEvent(), host.await { it is UiState.CreateEvent })
            assertEquals(null, w.configSource.config.value)
            // ...and the backend outcomes land when the fire-and-forget DELETE does (awaited, not
            // assumed synchronous — the flip deliberately never waits on the network).
            withTimeout(5_000) { while (w.store.isRegistered("E")) delay(10) }
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
            val w = World(this)
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

            // Status: uploads complete, and the download total is 0 because an upload-only membership never
            // reconciles, so nothing was ever planned (capability `photo-download`) → both arrows hidden →
            // In sync. That total flows THROUGH the download gate, which is why this arm never needed a mask.
            w.refreshStatus()
            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun download_only_imports_foreign_and_reads_in_sync_through_a_zero_total() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", direction = Direction.DownloadOnly)
            w.addOwnAsset("A") // an un-uploaded own photo remains in the gallery
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            // Download arm runs and imports the foreign asset.
            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isNotEmpty(), "download-only imports foreign photos")

            // Upload arm: DRIVE it. This assertion used to stand with no `runUploadCycle()` above it at
            // all — "the producer never runs" (D3's assumption) used as the test's METHOD rather than its
            // finding, so it passed whether or not the gate existed. It did not exist, and this test was
            // green throughout.
            w.runUploadCycle()
            assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "download-only uploads nothing")

            // Status reads "In sync" because N is 0 — NOT because an arrow is masked. The masks are gone
            // (capability `sync-status-screen`), so this is now load-bearing: were the total to report the
            // un-uploaded gallery, the upload arrow would show and this would fail.
            w.refreshStatus()
            assertEquals(0, w.ownGallery.size.value, "a non-contributing membership counts nothing")
            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun download_only_uploads_nothing_when_the_cycle_actually_runs() = worldTest {
        // THE PRIVACY INVARIANT (capability `upload-lifecycle`). The join gate promises "Only receive the
        // event's photos — you won't share yours". This asserts the stack keeps that promise when the cycle
        // is DRIVEN — which is the only interesting case.
        //
        // Its sibling above asserts the same property without ever invoking the cycle, taking "the producer
        // never runs" as its method rather than its finding. That is D3's assumption
        // (`changes/archive/2026-07-07-add-join-direction-mode`) restated as a test, and it holds only where
        // the OS is the sole invoker. On the app-driven tier (iOS 18–26.0) the APP invokes the cycle —
        // foreground entry, the heartbeat, a silent push — and every one of those reaches exactly this call.
        val w = World(this)
        w.provision("E", direction = Direction.DownloadOnly)
        w.addOwnAsset("A")

        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg") // a no-op once the gate holds: no such job exists
        w.runUploadCycle()

        assertTrue(
            w.platform.created.isEmpty(),
            "download-only must create no upload job — the member was promised they would share nothing",
        )
        assertTrue(
            w.store.objectsOf(w.ownDeviceId).isEmpty(),
            "download-only must upload no bytes",
        )
        // The union leak, distinct from the bytes: a manifest listing the member's assets offers them to
        // every other member (capability `photo-selection-policy`, "One policy gates both byte upload and
        // manifest listing").
        assertTrue(
            w.store.manifestOf("E", w.ownDeviceId)?.assets.isNullOrEmpty(),
            "download-only must list no asset in its device manifest",
        )
    }

    @Test
    fun leaving_flips_the_screen_before_the_backend_delete_completes() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
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
                syncSource = w.syncStatusSource,
                permission = w.permission.permission,
                config = w.configSource.config,
                scope = scope,
                cutoffFormatter = fixedCutoffFormatter(),
                creationStatusSource = w.creationStatus,
                commands = UserCommands(leave = { leaveEvent.leave() }),
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
        syncSource = w.syncStatusSource,
        permission = w.permission.permission,
        config = w.configSource.config,
        scope = scope,
        cutoffFormatter = fixedCutoffFormatter(),
    )

    private fun UiState.health(): SyncHealth? = (this as? UiState.Joined)?.health

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

/** A real formatter on a fixed UTC instant — the host requires one since step 9 (no system default). */
private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
