@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.membership

import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.configCleared
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.persistedConfig
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest


/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class LeaveEventTest {

    /** The membership's file, recording the clear into [order] as it reaches the port. */
    private fun membershipFiles(order: MutableList<String>? = null) = RecordingFiles().apply {
        onOperation = { if (it.startsWith("delete")) order?.add("clear") }
    }

    // A membership always carries a cutoff (capability `photo-sharing`); leave ignores it.
    private fun joined(eventId: String?) =
        eventId?.let { EventConfig(it, name = "Anna's Birthday", minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"), maxPhotoDate = FIXTURE_CEILING) }

    @Test
    fun `leave stops the producer clears the ledger clears config then notifies with the snapshotted eventId`() = runTest {
        val order = mutableListOf<String>()
        val files = membershipFiles(order)
        var notifiedWith: String? = null

        LeaveEvent(
            config = configService(joined("E1"), files),
            stopUploads = { order += "disable" },
            clearLedger = { order += "ledger" },
            notifyLeave = { id -> order += "notify"; notifiedWith = id },
            scope = backgroundScope,
        ).leave()
        runCurrent() // let the fire-and-forget notify run

        // Stop precedes every clear (no mechanism starts work against rows about to vanish); the upload
        // ledger goes before the config, so a device that has left never shows a stale share set; the
        // notify is dispatched AFTER the clears with the eventId snapshotted before them.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertEquals(listOf("disable", "ledger", "clear", "notify"), order)
        assertEquals("E1", notifiedWith)
    }

    @Test
    fun `a failing ledger clear still clears the config and notifies`() = runTest {
        val order = mutableListOf<String>()
        val files = membershipFiles(order)

        LeaveEvent(
            config = configService(joined("E5"), files),
            stopUploads = { order += "disable" },
            clearLedger = { throw RuntimeException("sqlite busy") },
            notifyLeave = { order += "notify" },
            scope = backgroundScope,
        ).leave()
        runCurrent()

        // Best-effort and independent: the device still leaves; the next join clears the ledger anyway.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertEquals(listOf("disable", "clear", "notify"), order)
    }

    @Test
    fun `the local teardown returns without waiting on the backend notify`() = runTest {
        val files = membershipFiles()
        var notifyStartedWith: String? = null
        val neverCompletes = CompletableDeferred<Unit>() // the DELETE hangs forever

        LeaveEvent(
            config = configService(joined("E7"), files),
            stopUploads = {},
            clearLedger = {},
            notifyLeave = { id -> notifyStartedWith = id; neverCompletes.await() /* hangs */ },
            scope = backgroundScope,
        ).leave() // returns promptly despite the notify below never completing
        runCurrent() // let the backgrounded notify start (and then hang)

        // The local teardown completed and the config is cleared even though the DELETE is still pending
        // — the screen flip never waits on the network. The notify was dispatched with the snapshot id.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertEquals("E7", notifyStartedWith)
        assertFalse(neverCompletes.isCompleted)
    }

    @Test
    fun `a failing config clear still dispatches the notify unconditionally`() = runTest {
        var disabled = false
        var notified = false
        val files = membershipFiles().apply { failDeletes = true }

        LeaveEvent(
            config = configService(joined("E2"), files),
            stopUploads = { disabled = true },
            clearLedger = {},
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
        val files = membershipFiles(order)

        LeaveEvent(
            config = configService(joined("E3"), files),
            stopUploads = { order += "disable" },
            clearLedger = {},
            notifyLeave = { throw RuntimeException("offline") },
            scope = backgroundScope,
        ).leave()
        runCurrent()

        // Best-effort: the notify threw (network down), but the device still leaves locally — the
        // config is cleared. The un-removed backend membership is the accepted abandon-leak.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertEquals(listOf("disable", "clear"), order)
    }

    @Test
    fun `a failing disable does not abort the rest of the teardown`() = runTest {
        val files = membershipFiles()

        LeaveEvent(
            config = configService(joined("E4"), files),
            stopUploads = { throw RuntimeException("photokit") },
            clearLedger = {},
            notifyLeave = {},
            scope = backgroundScope,
        ).leave()

        assertTrue(files.configCleared)
        assertNull(files.persistedConfig()) // config still cleared despite the disable throwing
    }

    @Test
    fun `with no event configured the notify is not dispatched`() = runTest {
        val files = membershipFiles()
        var notified = false

        LeaveEvent(
            config = configService(joined(null), files),
            stopUploads = {},
            clearLedger = {},
            notifyLeave = { notified = true },
            scope = backgroundScope,
        ).leave()
        runCurrent()

        // No eventId to target: the clear still runs, but there is no backend DELETE to fire.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertFalse(notified)
    }
}
