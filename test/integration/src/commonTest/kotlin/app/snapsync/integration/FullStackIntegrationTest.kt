package app.snapsync.integration

import app.snapsync.model.eventStart
import app.snapsync.model.captureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.LedgerState
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.model.Arrow
import app.snapsync.model.UserCommands
import app.snapsync.presentation.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.feature.status.LedgerCounts
import app.snapsync.model.PermissionStatus
import app.snapsync.model.normalizeAssetId
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
            w.provision("E", minPhotoDate = captureCutoff(future), startsAt = eventStart(future))
            w.addOwnAsset("A") // dated DEFAULT_DATE (2026) — long before the event begins
            w.refreshStatus()

            val host = statusHost(w, scope)
            assertEquals(SyncHealth.NotStarted(eventStart(future)), (host.await { it.health() is SyncHealth.NotStarted }).health())

            // Run a cycle anyway: the real stack, the real upload cycle, no special-casing.
            w.runUploadCycle()
            w.refreshStatus()

            // World outcomes, not UiState alone: nothing was admitted, nothing was queued, nothing landed.
            assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "no object may land before the event starts")
            assertNull(w.ledgerBackend.get("A-primary.jpg"), "the asset never even reached the ledger")
            assertEquals(SyncHealth.NotStarted(eventStart(future)), (host.await { it.health() is SyncHealth.NotStarted }).health())
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
            w.provision("E", minPhotoDate = captureCutoff(World.DEFAULT_CUTOFF), startsAt = eventStart(World.DEFAULT_STARTS_AT))
            w.addOwnAsset("A")
            w.refreshStatus()

            val host = statusHost(w, scope)
            host.await { it.health() is SyncHealth.Syncing } // NOT NotStarted

            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.refreshStatus()

            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertEquals(SyncHealth.InSync, (host.await { it.health() is SyncHealth.InSync }).health())
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
            assertEquals(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN), (host.await { it.health() is SyncHealth.Syncing }).health())

            // Run one cycle: the job is created → a REQUESTED (in-flight) ledger row → the up arrow pulses.
            w.runUploadCycle()
            w.ledgerCounts.refresh()
            host.await { (it.health() as? SyncHealth.Syncing)?.upload == Arrow.PULSING }

            // Operator completes the job (store-direct deposit) → next cycle acks → COMPLETED → settled.
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.refreshStatus()
            assertEquals(SyncHealth.InSync, (host.await { it.health() is SyncHealth.InSync }).health())

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
                StatusSources(
                    sync = w.syncStatusSource,
                    permission = w.permission.permission,
                    config = w.configSource.config,
                    creation = w.creationStatus,
                ),
                scope = scope,
                cutoffFormatter = fixedCutoffFormatter(),
                // The COMPOSED user-tap bundle (migration step 10): create routes through the real
                // `AppCore.eventCreator`; the world's default `onEventMinted` provisions directly.
                commands = w.userCommands,
            )
            assertEquals(UiState(Layer.CreateEvent()), host.container.stateFlow.value)

            host.onCreateEvent("Party", LocalDateTime(2026, 1, 1, 0, 0), LocalDateTime(2026, 1, 8, 0, 0)) // POST /events → provision → gate lifts
            // The gate lifts first; the counts are read separately. `refreshStatus` runs AFTER the
            // config exists, because the total is scoped by the membership and there is nothing to
            // count before one — the world's default `onEventMinted` writes the config cell directly
            // and, unlike the production `Provision` flow, does not refresh the status sources itself.
            // (This used to be called before the create and still passed, because the total was SEEDED
            // `0` and this membership's real total is also `0` — the un-counted state was
            // indistinguishable from the counted one. It no longer is.)
            host.await { it.layer is Layer.Joined }
            w.refreshStatus()
            // Await the SETTLED health, not merely "left the create layer": the snapshot's first read is
            // itself asynchronous (`LedgerBackedSyncStatusSource` seeds `Loading` and reaches `Ready`
            // only once every input has been READ), so `Joined(Loading)` — the neutral first frame — is a
            // legitimate state between the gate lifting and the counts landing. A predicate that
            // accepts it races the first read and asserts against a frame that is not settled yet.
            val after = host.await { it.health() is SyncHealth.InSync }
            assertEquals(SyncHealth.InSync, after.health()) // no photos in the library → settled
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
    fun an_imported_foreign_photo_carries_the_capturing_devices_filename() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ", filename = "IMG_4471.HEIC")))

        w.downloadController.reconcile("E")
        w.stageAllDownloads()

        // The whole point of the fix: what lands in the library is what the capturing device called it,
        // NOT the storage object key — which carries the assetId and the `-primary` role token and was
        // what PhotoKit picked up off the staged file when nobody named the resource.
        val ref = w.importer.imported.first()
        val importedLocalId = normalizeAssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}")
        val imported = w.gallery.current().first { it.assetId == importedLocalId }
        assertEquals(listOf("IMG_4471.HEIC"), imported.rawResources.map { it.originalFilename })
    }

    @Test
    fun a_limited_grant_receives_foreign_photos_and_never_reads_needs_access() = worldTest {
        // Receive-only under a LIMITED grant is a valid resting state (capability
        // `limited-photo-access`): imports work, no upload work is created (the read discipline keeps
        // every autonomous walk off), and the screen shows the ordinary health line — never NeedsAccess.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            // The test plays the HOST: the selection subscription is host-assembly wiring, and on a real
            // device it is what delivers the cold-launch baseline read.
            w.core.installPermissionSubscriptions()
            w.permission.set(PermissionStatus.LIMITED)
            w.provision("E")
            w.addOwnAsset("A") // present in the library — must NOT be enumerated or uploaded
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            // The baseline snapshot lands, and it is EMPTY — the member selected nothing. A real
            // `.limited` device always emits one when observation begins, empty or not, and that
            // emission is what turns "we hold no selection" into "the selection is empty". Only the
            // second is a counted zero, and only a counted zero may settle the screen: without this the
            // total stays un-counted, which is the correct answer to a question nobody has answered yet
            // (capability `gallery-status`).
            w.changeSelection()

            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isNotEmpty()) // world outcome: foreign photo imported under LIMITED

            // The autonomous own-device walk is gated off under LIMITED: refreshing the status
            // enumerates nothing, and no upload job or ledger row ever appears for the own asset.
            w.refreshStatus()
            w.ledgerCounts.refresh()
            assertEquals(LedgerCounts(completed = 0, pending = 0), w.ledgerCounts.counts.value)

            val host = statusHost(w, scope)
            // UI outcome: the settled zero-total health — not the permission-attention line.
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_selection_change_under_limited_raises_n_and_uploads_the_selected_photos() = worldTest {
        // The selection-driven upload path (capability `limited-photo-access`): one selection-change
        // emission serves N and the cycle's discovery; the cycle under LIMITED reads the snapshot cell
        // (never the library) and uploads through the ordinary engine/ledger.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            // The test plays the HOST: the selection subscription is host-assembly wiring
            // (installed by the iOS root at assembly; never on mere core construction).
            w.core.installPermissionSubscriptions()
            w.permission.set(PermissionStatus.LIMITED)
            w.provision("E")
            w.addOwnAsset("A")
            w.addOwnAsset("B")
            w.addOwnAsset("UNSELECTED") // in the library, never selected — must not upload

            w.changeSelection("A", "B")
            // One emission serves the total: N counts the selection, not the library. Awaiting it also
            // sequences the cycle below — the collector sets the discovery cell before recounting.
            withTimeout(5_000) { w.ownGallery.size.first { it == 2 } }

            // The operator plays the OS: run the cycle — discovery is the snapshot (no walk).
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.platform.completeJob("B-primary.jpg")
            w.runUploadCycle()
            w.ledgerCounts.refresh() // the operator's liveness re-read (the pump's onCycleComplete analogue)

            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertTrue("B-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
            // The unselected asset never entered the pipeline.
            assertTrue("UNSELECTED-primary.jpg" !in w.store.objectsOf(w.ownDeviceId))
            assertEquals(null, w.ledgerBackend.get("UNSELECTED-primary.jpg"))

            val host = statusHost(w, scope)
            // The joined layer under LIMITED carries the choose-more-photos resting affordance.
            val settled = host.await { it.health() is SyncHealth.InSync }.layer as Layer.Joined
            assertEquals(SyncHealth.InSync, settled.health)
            assertTrue(settled.canChoosePhotos)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_policy_applies_unchanged_to_a_limited_selection() = worldTest {
        // Cutoff and origin exclusions filter hand-picked photos exactly as a full-library walk
        // (capability `photo-selection-policy` over `limited-photo-access`): picking a pre-cutoff
        // photo or a screenshot does not smuggle it past the policy.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            // The test plays the HOST: the selection subscription is host-assembly wiring
            // (installed by the iOS root at assembly; never on mere core construction).
            w.core.installPermissionSubscriptions()
            w.permission.set(PermissionStatus.LIMITED)
            w.provision("E")
            w.addOwnAsset("OK")
            w.addOwnAsset("OLD", creationDate = "2001-01-01T00:00:00Z") // pre-cutoff
            w.addScreenshot("SHOT") // origin-excluded by subtype

            w.changeSelection("OK", "OLD", "SHOT")
            // N counts only the policy-admitted selection (1 of the 3 picked).
            withTimeout(5_000) { w.ownGallery.size.first { it == 1 } }

            w.runUploadCycle()
            w.platform.completeJob("OK-primary.jpg")
            w.runUploadCycle()

            assertTrue("OK-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertTrue("OLD-primary.jpg" !in w.store.objectsOf(w.ownDeviceId))
            assertTrue("SHOT-primary.jpg" !in w.store.objectsOf(w.ownDeviceId))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_imported_foreign_asset_in_the_selection_never_reuploads() = worldTest {
        // The app's own import auto-joins the platform selection (measured); the snapshot then carries
        // it, and echo-suppression drops it at the cycle — no debounce or self-caused-change filter
        // (capability `limited-photo-access`, "Change consumption ... dedups via the ledger").
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            // The test plays the HOST: the selection subscription is host-assembly wiring
            // (installed by the iOS root at assembly; never on mere core construction).
            w.core.installPermissionSubscriptions()
            w.permission.set(PermissionStatus.LIMITED)
            w.provision("E")
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))
            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isNotEmpty())

            // The imported asset lands in the gallery under its created local id and — as on iOS —
            // shows up in the next selection snapshot alongside a genuinely-selected own photo.
            val importedRef = w.importer.imported.first()
            val importedLocalId = normalizeAssetId("imported-${importedRef.sourceDeviceId}-${importedRef.sourceAssetId}")
            w.addOwnAsset("MINE")
            w.changeSelection("MINE", importedLocalId)
            // The echo is suppressed from the total: only MINE counts.
            withTimeout(5_000) { w.ownGallery.size.first { it == 1 } }

            w.runUploadCycle()
            w.platform.completeJob("MINE-primary.jpg")
            w.runUploadCycle()

            assertTrue("MINE-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            // The foreign import never re-uploaded under this device's id.
            assertEquals(1, w.store.objectsOf(w.ownDeviceId).size)
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
                StatusSources(
                    sync = w.syncStatusSource,
                    permission = w.permission.permission,
                    config = w.configSource.config,
                    creation = w.creationStatus,
                ),
                scope = scope,
                cutoffFormatter = fixedCutoffFormatter(),
                commands = w.userCommands,
            )
            host.await { it.layer is Layer.Joined }

            host.onLeaveEvent()

            // UiState reduces to the setup gate the instant the config clears...
            assertEquals(UiState(Layer.CreateEvent()), host.await { it.layer is Layer.CreateEvent })
            assertEquals(null, w.configSource.config.value)
            // ...and the backend outcome lands when the fire-and-forget DELETE does (awaited, not assumed
            // synchronous — the flip deliberately never waits on the network). Leaving is RENAME-ONLY now
            // (capability `event-leave-endpoint`): the device is departed, but the event and its bytes are
            // RETAINED until the nightly sweep reclaims them (capability `scheduled-cleanup`).
            withTimeout(5_000) {
                while (!w.store.isDeparted("E", w.ownDeviceId)) delay(10)
            }
            assertTrue(w.store.isRegistered("E")) // NOT reaped — leaving no longer deletes the event
            assertTrue(w.store.objectsOf(w.ownDeviceId).isNotEmpty()) // bytes NOT collected by leave
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
                StatusSources(
                    sync = w.syncStatusSource,
                    permission = w.permission.permission,
                    config = w.configSource.config,
                    creation = w.creationStatus,
                ),
                scope = scope,
                cutoffFormatter = fixedCutoffFormatter(),
                commands = UserCommands(leave = { leaveEvent.leave() }),
            )
            host.await { it.layer is Layer.Joined }

            host.onLeaveEvent()

            // The screen leaves the joined layer immediately — even though the DELETE is still pending.
            assertEquals(UiState(Layer.CreateEvent()), host.await { it.layer is Layer.CreateEvent })
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
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
        ),
        scope = scope,
        cutoffFormatter = fixedCutoffFormatter(),
    )

    private fun UiState.health(): SyncHealth? = (this.layer as? Layer.Joined)?.health

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

/** A real formatter on a fixed UTC instant — the host requires one since step 9 (no system default). */
private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
