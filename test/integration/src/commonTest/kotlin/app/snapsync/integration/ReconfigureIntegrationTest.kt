package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.model.PermissionStatus
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
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
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam ↔ UI-state integration for the in-place reconfigure (capability `reconfigure-membership`), driven
 * against the REAL core over the world (`:test:world`) through the composed `UserCommands.reconfigure` —
 * asserting **`UiState` AND world outcomes**: enabling share uploads, album-on is forward-only, and
 * turning receive off cancels in-flight downloads. Runs on JVM and `iosSimulatorArm64`.
 */
class ReconfigureIntegrationTest {

    @Test
    fun enabling_share_on_a_download_only_membership_starts_uploading_in_place() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", direction = Direction.DownloadOnly)
            w.addOwnAsset("A")

            // Download-only: the cycle uploads nothing (the privacy invariant).
            w.runUploadCycle()
            assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "download-only uploads nothing")

            val host = statusHost(w, scope)

            // Reconfigure IN PLACE to Both — no leave, same eventId.
            w.userCommands.reconfigure("E", Direction.Both, World.DEFAULT_CUTOFF, null, false)
            assertEquals(Direction.Both, w.configSource.config.value?.direction, "config changed in place")
            assertEquals("E", w.configSource.config.value?.eventId, "same membership, never left")

            // Now the very same stack uploads the own photo.
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            w.refreshStatus()

            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId), "enabling share uploads the own photo")
            assertEquals(UiState.Joined(SyncHealth.InSync), host.await { it.health() is SyncHealth.InSync })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun turning_the_album_on_is_forward_only_and_does_not_backfill() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.permission.set(PermissionStatus.GRANTED) // ensureAlbum needs a usable grant
            // A named membership — ensureAlbum is a no-op for a nameless one (capability `event-album`).
            w.provision("E", name = "Anna's Birthday", saveToAlbum = false)
            w.addOwnAsset("A")

            // A uploads while the album is OFF — so it is a genuinely already-synced photo.
            w.runUploadCycle()
            w.platform.completeJob("A-primary.jpg")
            w.runUploadCycle()
            assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
            assertTrue(w.albumManager.created.isEmpty(), "no album while opted out")

            // Reconfigure the album ON: the album is ensured but A is NOT retroactively gathered.
            w.userCommands.reconfigure("E", Direction.Both, World.DEFAULT_CUTOFF, null, true)
            val albumId = w.albumManager.created.single().first
            assertTrue(w.albumManager.assetsIn(albumId).isEmpty(), "album-on does not backfill already-synced A")

            // A NEW photo synced after the toggle IS placed (forward-only).
            w.addOwnAsset("B")
            w.runUploadCycle()
            w.platform.completeJob("B-primary.jpg")
            w.runUploadCycle()
            assertTrue(w.albumManager.assetsIn(albumId).isNotEmpty(), "photos synced after album-on are placed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun turning_receive_off_cancels_in_flight_downloads_and_imports_nothing() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E") // Both
            w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))

            // Enqueue an in-flight download, then turn RECEIVE off before it is staged.
            w.downloadController.reconcile("E")
            w.userCommands.reconfigure("E", Direction.UploadOnly, World.DEFAULT_CUTOFF, null, false)
            assertEquals(Direction.UploadOnly, w.configSource.config.value?.direction)

            // The in-flight download was cancelled, so staging imports nothing, and the now-gated
            // reconcile enqueues nothing further.
            w.stageAllDownloads()
            w.downloadController.reconcile("E")
            w.stageAllDownloads()
            assertTrue(w.importer.imported.isEmpty(), "receive-off cancels in-flight downloads; nothing imports")

            w.refreshStatus()
            val host = statusHost(w, scope)
            assertEquals(SyncHealth.InSync, host.await { it.health() is SyncHealth.InSync }.health())
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

private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
