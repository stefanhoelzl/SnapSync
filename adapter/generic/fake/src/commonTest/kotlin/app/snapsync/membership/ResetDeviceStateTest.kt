package app.snapsync.membership

import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.fake.InMemoryLedgerStore
import app.snapsync.feature.membership.ResetDeviceState
import app.snapsync.model.EventConfig
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.ports.AssetRef
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.PlannedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The `SNAPSYNC_RESET_STATE` reset (capability `ios-app-shell`), driven against the **honest** in-memory
 * ledger and download stores rather than stubs — which matters most for the imported-rows assertion,
 * where the whole question is whether real prune semantics keep them.
 */
class ResetDeviceStateTest {

    private class FakeConfigStore : ConfigStore {
        var cleared = false
        override suspend fun save(config: EventConfig) {}
        override suspend fun clear() {
            cleared = true
        }
    }

    private class Fixture {
        val config = FakeConfigStore()
        val ledger = InMemoryLedgerStore()
        val downloads = InMemoryDownloadStore()
        var cursorCleared = false

        fun reset() = ResetDeviceState(
            config = config,
            ledger = ledger,
            downloads = downloads,
            clearDiscoveryCursor = { cursorCleared = true },
        )
    }

    private fun resource(key: String) =
        PlannedResource(key, "https://x/$key", "photo", "image/heic", key)

    @Test
    fun `it clears the ledger and the cursor and the config`() = runTest {
        val f = Fixture()
        f.ledger.put(LedgerEntry("IMG_1.HEIC", "asset-1", LedgerState.COMPLETED, 0, "E1"))
        f.ledger.put(LedgerEntry("IMG_2.HEIC", "asset-2", LedgerState.COMPLETED, 0, "E1"))

        f.reset().reset()

        val aggregates = f.ledger.aggregates()
        assertEquals(0, aggregates.completed)
        assertEquals(0, aggregates.pending)
        assertTrue(f.cursorCleared, "the discovery cursor must be cleared")
        assertTrue(f.config.cleared, "the membership config must be cleared")
    }

    @Test
    fun `clearing the cursor is what actually restores enumeration`() = runTest {
        // The non-obvious half, pinned on its own: a ledger wipe with the change token still in place
        // means the next cycle observes no changes and enumerates nothing, so the device uploads zero
        // against the new backend — the exact silent failure this trigger exists to remove.
        val f = Fixture()
        f.reset().reset()
        assertTrue(f.cursorCleared)
    }

    @Test
    fun `imported downloads survive so no downloaded photo is re-uploaded`() = runTest {
        val f = Fixture()
        val imported = AssetRef("device-A", "asset-imported")
        val pending = AssetRef("device-B", "asset-pending")
        f.downloads.plan(imported, "2026-07-01T10:00:00Z", listOf(resource("a.HEIC")))
        f.downloads.markImported(imported, createdLocalId = "local-123")
        f.downloads.plan(pending, "2026-07-01T11:00:00Z", listOf(resource("b.HEIC")))

        f.reset().reset()

        assertEquals(1, f.downloads.importedCount(), "imported rows must survive the reset")
        assertEquals(1, f.downloads.assetCount(), "the non-terminal row must be gone")
        assertTrue(f.downloads.isImported(imported))
        // The suppression handle is the reason imported rows are kept: the upload path reads it to
        // avoid re-uploading a photo this device downloaded (the echo).
        assertEquals(setOf("local-123"), f.downloads.suppressedLocalIds())
    }

    @Test
    fun `a reset on a device holding nothing is a no-op that still completes`() = runTest {
        val f = Fixture()
        f.reset().reset()
        assertEquals(0, f.ledger.aggregates().completed)
        assertEquals(0, f.downloads.assetCount())
        assertTrue(f.config.cleared)
    }

    @Test
    fun `a failing step does not abort the rest`() = runTest {
        // Best-effort, like leave: a partial reset is strictly better than an aborted one, because
        // whatever was cleared can no longer mislead the next cycle.
        val f = Fixture()
        val throwingConfig = object : ConfigStore {
            override suspend fun save(config: EventConfig) {}
            override suspend fun clear() = throw IllegalStateException("config write failed")
        }
        f.ledger.put(LedgerEntry("IMG_1.HEIC", "asset-1", LedgerState.COMPLETED, 0, "E1"))

        ResetDeviceState(
            config = throwingConfig,
            ledger = f.ledger,
            downloads = f.downloads,
            clearDiscoveryCursor = { f.cursorCleared = true },
        ).reset()

        assertEquals(0, f.ledger.aggregates().completed, "the ledger clear still ran")
        assertTrue(f.cursorCleared, "the cursor clear still ran")
    }
}
