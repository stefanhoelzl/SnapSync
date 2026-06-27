package app.snapsync.rejoin

import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfigPayload
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class LeaveEventTest {

    private val t0 = Instant.fromEpochMilliseconds(5_000_000)

    private class FakeConfigStore(var cleared: Boolean = false) : ConfigStore {
        override suspend fun save(config: EventConfigPayload) {}
        override suspend fun clear() { cleared = true }
    }

    private suspend fun seededLedger() = FakeLedgerBackend().apply {
        put(LedgerEntry("A", "A", LedgerState.COMPLETED, 0, t0))
        put(LedgerEntry("B", "B", LedgerState.REQUESTED, 0, t0))
    }

    @Test
    fun `leave disables first then wipes ledger and cursor and clears config and idles`() = runTest {
        val ledger = seededLedger()
        val config = FakeConfigStore()
        val status = MutableEventStatusSource(EventStatus.Joined)
        var rowsAtDisable = -1
        var cursorCleared = 0

        LeaveEvent(
            config = config,
            ledger = ledger,
            status = status,
            // The ledger still holds its rows when disable runs → disable preceded the reset.
            disableExtension = { rowsAtDisable = ledger.rows.size },
            clearDiscoveryCursor = { cursorCleared++ },
        ).leave()

        assertEquals(2, rowsAtDisable)            // disable ran before the reset
        assertTrue(ledger.rows.isEmpty())         // ledger reset to empty
        assertEquals(1, cursorCleared)            // discovery cursor cleared
        assertTrue(config.cleared)                // config forgotten
        assertEquals(EventStatus.Idle, status.status.value)
    }

    @Test
    fun `a failing config clear still leaves the ledger empty and the producer disabled`() = runTest {
        val ledger = seededLedger()
        val status = MutableEventStatusSource(EventStatus.Joined)
        var disabled = false
        val throwingConfig = object : ConfigStore {
            override suspend fun save(config: EventConfigPayload) {}
            override suspend fun clear() { throw RuntimeException("keychain") }
        }

        LeaveEvent(
            config = throwingConfig,
            ledger = ledger,
            status = status,
            disableExtension = { disabled = true },
            clearDiscoveryCursor = {},
        ).leave()

        // Self-heal precondition: the wipe + disable held even though the config clear threw.
        assertTrue(disabled)
        assertTrue(ledger.rows.isEmpty())
        assertEquals(EventStatus.Idle, status.status.value)
    }

    @Test
    fun `a failing disable does not abort the rest of the teardown`() = runTest {
        val ledger = seededLedger()
        val config = FakeConfigStore()
        val status = MutableEventStatusSource(EventStatus.Joined)

        LeaveEvent(
            config = config,
            ledger = ledger,
            status = status,
            disableExtension = { throw RuntimeException("photokit") },
            clearDiscoveryCursor = {},
        ).leave()

        assertFalse(ledger.rows.isNotEmpty()) // reset still ran
        assertTrue(config.cleared)            // config still cleared
        assertEquals(EventStatus.Idle, status.status.value)
    }
}
