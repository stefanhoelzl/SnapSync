@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.membership

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.inertPendingLeaves
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.EventConfig
import app.snapsync.model.TimestampText
import app.snapsync.services.crypto.EventKeys
import app.snapsync.testids.testEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = CaptureCeiling(TimestampText.parse("2099-01-01T00:00:00Z"))

class LeaveEventTest {

    /** The membership's file, recording the clear into [order] as it reaches the port. */
    private fun membershipFiles(order: MutableList<String>? = null) = SharedStore().apply {
        onCleared = { order?.add("clear") }
    }

    // A membership always carries a cutoff (capability `photo-sharing`); leave ignores it.
    private fun joined(eventId: String?) =
        eventId?.let {
            EventConfig(
                testEventId(it),
                name = "Anna's Birthday",
                minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T14:32:11Z")),
                maxPhotoDate = FIXTURE_CEILING,
                endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
                deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
            )
        }

    @Test
    fun `leave stops the producer clears config then notifies with the snapshotted eventId`() = runTest {
        val order = mutableListOf<String>()
        val files = membershipFiles(order)
        var notifiedWith: EventId? = null

        LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(joined("E1"), files),
            stopUploads = { order += "disable" },
            notifyLeave = { id, _ ->
                order += "notify"
                notifiedWith = id
            },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
        ).leave()
        runCurrent() // let the fire-and-forget notify run

        // Stop precedes the clear (no mechanism starts work for a membership about to go); no ledger row is
        // touched — the left event's rows are inert once the config is gone; the notify is dispatched AFTER the
        // clear with the eventId snapshotted before it.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertEquals(listOf("disable", "clear", "notify"), order)
        assertEquals(testEventId("E1"), notifiedWith)
    }

    @Test
    fun `the local teardown returns without waiting on the backend notify`() = runTest {
        val files = membershipFiles()
        var notifyStartedWith: EventId? = null
        val neverCompletes = CompletableDeferred<Unit>() // the DELETE hangs forever

        LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(joined("E7"), files),
            stopUploads = {},
            notifyLeave = { id, _ ->
                notifyStartedWith = id
                neverCompletes.await() // hangs
            },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
        ).leave() // returns promptly despite the notify below never completing
        runCurrent() // let the backgrounded notify start (and then hang)

        // The local teardown completed and the config is cleared even though the DELETE is still pending
        // — the screen flip never waits on the network. The notify was dispatched with the snapshot id.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertEquals(testEventId("E7"), notifyStartedWith)
        assertFalse(neverCompletes.isCompleted)
    }

    @Test
    fun `a failing config clear still dispatches the notify unconditionally`() = runTest {
        var disabled = false
        var notified = false
        val files = membershipFiles().apply { failClears() }

        LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(joined("E2"), files),
            stopUploads = { disabled = true },
            notifyLeave = { _, _ -> notified = true },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
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
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(joined("E3"), files),
            stopUploads = { order += "disable" },
            notifyLeave = { _, _ -> throw RuntimeException("offline") },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
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
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(joined("E4"), files),
            stopUploads = { throw RuntimeException("photokit") },
            notifyLeave = { _, _ -> },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
        ).leave()

        assertTrue(files.configCleared)
        assertNull(files.persistedConfig()) // config still cleared despite the disable throwing
    }

    @Test
    fun `with no event configured the notify is not dispatched`() = runTest {
        val files = membershipFiles()
        var notified = false

        LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(joined(null), files),
            stopUploads = {},
            notifyLeave = { _, _ -> notified = true },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
        ).leave()
        runCurrent()

        // No eventId to target: the clear still runs, but there is no backend DELETE to fire.
        assertTrue(files.configCleared)
        assertNull(files.persistedConfig())
        assertFalse(notified)
    }

    // ── Every leave says whether it has everything (capability `manage-membership`) ─────────────────────────────

    /** [joined], with a range that ended before the test clock's now. */
    private fun ended(
        eventId: String,
    ) = joined(eventId)!!.copy(endsAt = EventEnd(TimestampText.parse("2026-06-10T12:00:00Z")))

    private suspend fun kotlinx.coroutines.test.TestScope.leaveAnswering(
        config: EventConfig,
        everythingReceived: suspend (EventConfig) -> Boolean,
    ): Pair<Boolean?, EventConfig?> {
        var sent: Boolean? = null
        var asked: EventConfig? = null
        LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(config, membershipFiles()),
            stopUploads = {},
            notifyLeave = { _, received -> sent = received },
            scope = backgroundScope,
            everythingReceived = { cfg ->
                asked = cfg
                everythingReceived(cfg)
            },
            pendingLeaves = inertPendingLeaves(),
        ).leave()
        runCurrent()
        return sent to asked
    }

    @Test
    fun `after the end the leave says what the downloads answer about the membership it left`() = runTest {
        val (sent, asked) = leaveAnswering(ended("E8")) { true }
        assertEquals(true, sent)
        // Asked with the snapshot, though the config is cleared by the time the background notify runs.
        assertEquals(testEventId("E8"), asked?.eventId)
        assertFalse(leaveAnswering(ended("E9")) { false }.first!!)
    }

    @Test
    fun `before the end the leave says no without asking`() = runTest {
        val (sent, asked) = leaveAnswering(joined("E10")!!) { true }
        assertEquals(false, sent)
        assertNull(asked)
    }

    @Test
    fun `a doubt about the downloads is a no`() = runTest {
        val (sent, _) = leaveAnswering(ended("E11")) { throw RuntimeException("union unreadable") }
        assertEquals(false, sent)
    }

    @Test
    fun a_leave_forgets_an_encrypted_events_key() = runTest {
        val store = inMemorySecureStore()
        val keys = EventKeys(fakeCrypto(), store)
        keys.keep(keys.mint().linkKey)
        LeaveEvent(
            keys = keys,
            config = configService(joined("E1")),
            stopUploads = {},
            notifyLeave = { _, _ -> },
            scope = backgroundScope,
            everythingReceived = { false },
            pendingLeaves = inertPendingLeaves(),
        ).leave()
        assertEquals(
            app.snapsync.ports.SecureStoreRead.Absent,
            store.read(app.snapsync.services.secure.SecureSlots.EVENT_KEY),
        )
    }

    // ── A switch's leave of the previous membership says whether it had everything (capability `manage-membership`) ──

    /** What [LeaveEvent.notifySwitchLeave] for [eventId] sent, with [saved] the saved membership. */
    private suspend fun kotlinx.coroutines.test.TestScope.switchLeave(
        saved: EventConfig?,
        eventId: String,
        everythingReceived: suspend (EventConfig) -> Boolean,
    ): List<Pair<EventId, Boolean>> {
        val sent = mutableListOf<Pair<EventId, Boolean>>()
        LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = configService(saved, membershipFiles()),
            stopUploads = {},
            notifyLeave = { id, received -> sent += id to received },
            scope = backgroundScope,
            everythingReceived = everythingReceived,
            pendingLeaves = inertPendingLeaves(),
        ).notifySwitchLeave(testEventId(eventId))
        return sent
    }

    @Test
    fun `a switch leave of an event that is not the saved one says no without asking`() = runTest {
        var asked = false
        val ask: suspend (EventConfig) -> Boolean = {
            asked = true
            true
        }
        assertEquals(listOf(testEventId("E12") to false), switchLeave(ended("OTHER"), "E12", ask))
        assertEquals(listOf(testEventId("E12") to false), switchLeave(null, "E12", ask))
        assertFalse(asked)
    }

    @Test
    fun `a switch leave before the saved event ended says no`() = runTest {
        assertEquals(listOf(testEventId("E13") to false), switchLeave(joined("E13"), "E13") { true })
    }

    @Test
    fun `a switch leave after the end says what the downloads answer`() = runTest {
        assertEquals(listOf(testEventId("E14") to true), switchLeave(ended("E14"), "E14") { true })
        assertEquals(listOf(testEventId("E14") to false), switchLeave(ended("E14"), "E14") { false })
    }

    @Test
    fun `a doubt about the downloads makes a switch leave a no`() = runTest {
        assertEquals(
            listOf(testEventId("E15") to false),
            switchLeave(ended("E15"), "E15") { throw RuntimeException("unreadable") },
        )
    }
}
