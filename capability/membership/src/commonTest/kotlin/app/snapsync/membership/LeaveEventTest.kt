package app.snapsync.membership

import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LeaveEventTest {

    private class FakeConfigStore(private val order: MutableList<String>? = null) : ConfigStore {
        var cleared: Boolean = false
        override suspend fun save(config: EventConfig) {}
        override suspend fun clear() { cleared = true; order?.add("clear") }
    }

    @Test
    fun `leave disables the producer then notifies the backend then clears config`() = runTest {
        val order = mutableListOf<String>()
        val config = FakeConfigStore(order)

        LeaveEvent(
            config = config,
            disableExtension = { order += "disable" },
            notifyLeave = { order += "notify" },
        ).leave()

        // Disable precedes notify precedes clear, and the config is forgotten. No ledger or marker is
        // touched — the use-case constructs no ledger type at all (the extension resets on its next join).
        assertTrue(config.cleared)
        assertEquals(listOf("disable", "notify", "clear"), order)
    }

    @Test
    fun `a failing backend notify still completes the local teardown`() = runTest {
        val order = mutableListOf<String>()
        val config = FakeConfigStore(order)

        LeaveEvent(
            config = config,
            disableExtension = { order += "disable" },
            notifyLeave = { throw RuntimeException("offline") },
        ).leave()

        // Best-effort: the notify threw (network down), but the device still leaves locally — the
        // config is cleared. The un-removed backend membership is the accepted abandon-leak.
        assertTrue(config.cleared)
        assertEquals(listOf("disable", "clear"), order)
    }

    @Test
    fun `a failing config clear leaves the producer disabled and does not corrupt anything`() = runTest {
        var disabled = false
        val throwingConfig = object : ConfigStore {
            override suspend fun save(config: EventConfig) {}
            override suspend fun clear() { throw RuntimeException("keychain") }
        }

        LeaveEvent(
            config = throwingConfig,
            disableExtension = { disabled = true },
            notifyLeave = {},
        ).leave()

        // Self-heal precondition: the disable held even though the config clear threw; the event stays
        // configured (the user is simply still joined) and re-running leave retries the clear.
        assertTrue(disabled)
    }

    @Test
    fun `a failing disable does not abort the rest of the teardown`() = runTest {
        val config = FakeConfigStore()

        LeaveEvent(
            config = config,
            disableExtension = { throw RuntimeException("photokit") },
            notifyLeave = {},
        ).leave()

        assertTrue(config.cleared) // config still cleared despite the disable throwing
    }
}
