package app.snapsync.feature.membership

import app.snapsync.model.captureCeiling
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.model.captureCutoff
import app.snapsync.model.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest


/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class LeaveEventTest {

    private class FakeConfigStore(private val order: MutableList<String>? = null) : ConfigStore {
        var cleared: Boolean = false
        override suspend fun save(config: EventConfig) {}
        override suspend fun clear() { cleared = true; order?.add("clear") }
    }

    private class FakeConfigSource(eventId: String?) : ConfigSource {
        override val config: StateFlow<EventConfig?> =
            // A membership always carries a cutoff (capability `photo-selection-policy`); leave ignores it.
            MutableStateFlow(eventId?.let { EventConfig(it, minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"), maxPhotoDate = FIXTURE_CEILING) })
    }

    @Test
    fun `leave disables the producer then clears config then notifies with the snapshotted eventId`() = runTest {
        val order = mutableListOf<String>()
        val config = FakeConfigStore(order)
        var notifiedWith: String? = null

        LeaveEvent(
            config = config,
            configSource = FakeConfigSource("E1"),
            stopUploads = { order += "disable" },
            notifyLeave = { id -> order += "notify"; notifiedWith = id },
            scope = backgroundScope,
        ).leave()
        runCurrent() // let the fire-and-forget notify run

        // Disable precedes clear (the producer-race invariant), the config is forgotten, and the notify
        // is dispatched AFTER the clear with the eventId snapshotted before it. No ledger/marker touched.
        assertTrue(config.cleared)
        assertEquals(listOf("disable", "clear", "notify"), order)
        assertEquals("E1", notifiedWith)
    }

    @Test
    fun `the local teardown returns without waiting on the backend notify`() = runTest {
        val config = FakeConfigStore()
        var notifyStartedWith: String? = null
        val neverCompletes = CompletableDeferred<Unit>() // the DELETE hangs forever

        LeaveEvent(
            config = config,
            configSource = FakeConfigSource("E7"),
            stopUploads = {},
            notifyLeave = { id -> notifyStartedWith = id; neverCompletes.await() /* hangs */ },
            scope = backgroundScope,
        ).leave() // returns promptly despite the notify below never completing
        runCurrent() // let the backgrounded notify start (and then hang)

        // The local teardown completed and the config is cleared even though the DELETE is still pending
        // — the screen flip never waits on the network. The notify was dispatched with the snapshot id.
        assertTrue(config.cleared)
        assertEquals("E7", notifyStartedWith)
        assertFalse(neverCompletes.isCompleted)
    }

    @Test
    fun `a failing config clear still dispatches the notify unconditionally`() = runTest {
        var disabled = false
        var notified = false
        val throwingConfig = object : ConfigStore {
            override suspend fun save(config: EventConfig) {}
            override suspend fun clear() { throw RuntimeException("keychain") }
        }

        LeaveEvent(
            config = throwingConfig,
            configSource = FakeConfigSource("E2"),
            stopUploads = { disabled = true },
            notifyLeave = { notified = true },
            scope = backgroundScope,
        ).leave()
        runCurrent()

        // Self-heal precondition: the disable held even though the config clear threw, and the notify is
        // dispatched regardless (each best-effort step is independent; a failed clear does not gate it).
        assertTrue(disabled)
        assertTrue(notified)
    }

    @Test
    fun `a failing backend notify still completes the local teardown`() = runTest {
        val order = mutableListOf<String>()
        val config = FakeConfigStore(order)

        LeaveEvent(
            config = config,
            configSource = FakeConfigSource("E3"),
            stopUploads = { order += "disable" },
            notifyLeave = { throw RuntimeException("offline") },
            scope = backgroundScope,
        ).leave()
        runCurrent()

        // Best-effort: the notify threw (network down), but the device still leaves locally — the
        // config is cleared. The un-removed backend membership is the accepted abandon-leak.
        assertTrue(config.cleared)
        assertEquals(listOf("disable", "clear"), order)
    }

    @Test
    fun `a failing disable does not abort the rest of the teardown`() = runTest {
        val config = FakeConfigStore()

        LeaveEvent(
            config = config,
            configSource = FakeConfigSource("E4"),
            stopUploads = { throw RuntimeException("photokit") },
            notifyLeave = {},
            scope = backgroundScope,
        ).leave()

        assertTrue(config.cleared) // config still cleared despite the disable throwing
    }

    @Test
    fun `with no event configured the notify is not dispatched`() = runTest {
        val config = FakeConfigStore()
        var notified = false

        LeaveEvent(
            config = config,
            configSource = FakeConfigSource(null), // nothing to leave
            stopUploads = {},
            notifyLeave = { notified = true },
            scope = backgroundScope,
        ).leave()
        runCurrent()

        // No eventId to target: the clear still runs, but there is no backend DELETE to fire.
        assertTrue(config.cleared)
        assertFalse(notified)
    }
}
