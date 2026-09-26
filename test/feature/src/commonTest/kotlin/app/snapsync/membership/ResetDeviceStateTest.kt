package app.snapsync.membership

import app.snapsync.fake.inMemoryDatabases
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.configCleared
import app.snapsync.feature.support.configService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.ledger.LedgerService
import app.snapsync.feature.membership.ResetDeviceState
import app.snapsync.model.AssetId
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.AssetRef
import app.snapsync.services.config.ConfigService
import app.snapsync.model.PlannedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The device reset (capability `sync-status`), driven against the **honest** in-memory
 * ledger and download stores rather than stubs — which matters most for the imported-rows assertion,
 * where the whole question is whether real prune semantics keep them.
 */
class ResetDeviceStateTest {

    /** The REAL services, over in-memory SQLite and an in-memory shared area; [configFiles] is the membership file. */
    private class Fixture {
        val configFiles = RecordingFiles()
        val config: ConfigService = configService(null, configFiles)
        private val databases = inMemoryDatabases()
        val ledger = LedgerService(databases)
        val downloads = DownloadService(databases)

        /**
         * Stands in for the download controller's lock-holding reset entry point. It prunes through the
         * same store the real one does; what it cannot model here is the lock, which is exactly why that
         * critical section is injected rather than performed in this feature.
         */
        var downloadsReset = false

        fun reset() = ResetDeviceState(
            config = config,
            ledger = ledger,
            downloads = downloads,
            resetDownloads = { downloadsReset = true; downloads.pruneNonTerminal(protecting = emptySet()) },
        )
    }

    private fun resource(key: String) =
        PlannedResource(key, "https://x/$key", "photo", "image/heic", key)

    @Test
    fun `it clears the ledger and the config`() = runTest {
        val f = Fixture()
        f.ledger.recordUnlessSettled(LedgerEntry("IMG_1.HEIC", AssetId("asset-1"), LedgerState.COMPLETED))
        f.ledger.recordUnlessSettled(LedgerEntry("IMG_2.HEIC", AssetId("asset-2"), LedgerState.COMPLETED))

        f.reset().reset()

        assertTrue(f.downloadsReset, "the injected download reset really ran")

        val aggregates = f.ledger.aggregates()
        assertEquals(0, aggregates.completed)
        assertEquals(0, aggregates.pending)
        assertTrue(f.configFiles.configCleared, "the membership config must be cleared")
    }

    @Test
    fun `imported downloads survive so no downloaded photo is re-uploaded`() = runTest {
        val f = Fixture()
        val imported = AssetRef("device-A", AssetId("asset-imported"))
        val pending = AssetRef("device-B", AssetId("asset-pending"))
        f.downloads.plan(imported, "2026-07-01T10:00:00Z", listOf(resource("a.HEIC")))
        f.downloads.markImported(imported, createdLocalId = AssetId("local-123"))
        f.downloads.plan(pending, "2026-07-01T11:00:00Z", listOf(resource("b.HEIC")))

        f.reset().reset()

        assertEquals(1, f.downloads.counts().imported, "imported rows must survive the reset")
        assertEquals(1, f.downloads.counts().stillArriving, "the non-terminal row must be gone")
        assertTrue(f.downloads.isSettled(imported))
        // The suppression handle is the reason imported rows are kept: the upload path reads it to
        // avoid re-uploading a photo this device downloaded (the echo).
        assertEquals(setOf(AssetId("local-123")), f.downloads.suppressedLocalIds())
    }

    @Test
    fun `a reset on a device holding nothing is a no-op that still completes`() = runTest {
        val f = Fixture()
        f.reset().reset()
        assertEquals(0, f.ledger.aggregates().completed)
        assertEquals(0, f.downloads.counts().stillArriving)
        assertTrue(f.configFiles.configCleared)
    }

    @Test
    fun `a failing step does not abort the rest`() = runTest {
        // Best-effort, like leave: a partial reset is strictly better than an aborted one, because
        // whatever was cleared can no longer mislead the next cycle.
        val f = Fixture()
        f.configFiles.failDeletes = true // the membership file cannot be deleted: the config clear throws
        f.ledger.recordUnlessSettled(LedgerEntry("IMG_1.HEIC", AssetId("asset-1"), LedgerState.COMPLETED))

        ResetDeviceState(
            config = f.config,
            ledger = f.ledger,
            downloads = f.downloads,
            resetDownloads = { f.downloadsReset = true },
        ).reset()

        assertEquals(0, f.ledger.aggregates().completed, "the ledger clear still ran")
        assertTrue(f.downloadsReset, "the download reset still ran")
    }
}
