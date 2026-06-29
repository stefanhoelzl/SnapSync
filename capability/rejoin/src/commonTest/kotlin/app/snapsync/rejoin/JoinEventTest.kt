package app.snapsync.rejoin

import app.snapsync.config.ConfigSource
import app.snapsync.config.EventConfigPayload
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class JoinEventTest {

    private class FakeFiles(var result: Result<List<RemoteAsset>>) : EventFilesSource {
        var calls = 0
        override suspend fun list(eventId: String): Result<List<RemoteAsset>> {
            calls++
            return result
        }
    }

    private class FakeConfig(eventId: String?) : ConfigSource {
        val flow = MutableStateFlow(eventId?.let { EventConfigPayload(it) })
        override val config: StateFlow<EventConfigPayload?> = flow
    }

    private fun asset(assetId: String, vararg filenames: String) =
        RemoteAsset(assetId, filenames.map { RemoteResource(it) })

    private fun join(
        files: EventFilesSource,
        ledger: FakeLedgerBackend,
        config: FakeConfig,
        status: MutableEventStatusSource,
        onCursorClear: () -> Unit = {},
    ) = JoinEvent(files, ledger, config, status, { onCursorClear() })

    @Test
    fun `seeds every resource of each complete asset and nothing else`() = runTest {
        val files = FakeFiles(Result.success(listOf(asset("A", "A-primary.heic", "A-motion.mov"))))
        val ledger = FakeLedgerBackend()
        val status = MutableEventStatusSource()
        var cursorCleared = 0

        assertTrue(join(files, ledger, FakeConfig("E1"), status) { cursorCleared++ }.ensureJoined())

        val primary = ledger.get("A-primary.heic")!!
        assertEquals(LedgerState.COMPLETED, primary.state)
        assertEquals("A", primary.assetId) // carries the asset's id
        assertEquals(LedgerState.COMPLETED, ledger.get("A-motion.mov")!!.state)
        assertNull(ledger.get("B-primary.heic")) // an asset absent from the listing is not seeded
        assertEquals(EventStatus.Joined, status.status.value)
        assertEquals(1, cursorCleared)
    }

    @Test
    fun `non-empty ledger is a no-op without fetching and leaves status unchanged`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend().apply {
            put(LedgerEntry("X", "X", LedgerState.COMPLETED, 0))
        }
        val status = MutableEventStatusSource()

        assertTrue(join(files, ledger, FakeConfig("E1"), status).ensureJoined())

        assertEquals(0, files.calls)
        assertEquals(EventStatus.Idle, status.status.value)
    }

    @Test
    fun `list failure does not enable and does not auto-retry within the session`() = runTest {
        val files = FakeFiles(Result.failure(RuntimeException("net")))
        val ledger = FakeLedgerBackend()
        val status = MutableEventStatusSource()
        val j = join(files, ledger, FakeConfig("E1"), status)

        assertFalse(j.ensureJoined())
        assertEquals(EventStatus.JoinFailed, status.status.value)
        assertTrue(ledger.rows.isEmpty())

        assertFalse(j.ensureJoined()) // no auto-retry
        assertEquals(1, files.calls)
    }

    @Test
    fun `a re-scan clears the session flags so a failed join retries`() = runTest {
        val files = FakeFiles(Result.failure(RuntimeException("net")))
        val ledger = FakeLedgerBackend()
        val j = join(files, ledger, FakeConfig("E1"), MutableEventStatusSource())
        assertFalse(j.ensureJoined())

        j.onProvision(previousEventId = "E1", newEventId = "E1") // same event re-scan
        files.result = Result.success(listOf(asset("A", "A-primary.heic")))

        assertTrue(j.ensureJoined())
        assertEquals(2, files.calls)
        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")!!.state)
    }

    @Test
    fun `switching events resets the ledger and clears the cursor`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("old", "old", LedgerState.COMPLETED, 0)) }
        var cursorCleared = 0
        val j = join(FakeFiles(Result.success(emptyList())), ledger, FakeConfig("NEW"), MutableEventStatusSource()) { cursorCleared++ }

        j.onProvision(previousEventId = "OLD", newEventId = "NEW")

        assertTrue(ledger.rows.isEmpty())
        assertEquals(1, cursorCleared)
    }

    @Test
    fun `same-event provision leaves the ledger intact`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("k", "k", LedgerState.COMPLETED, 0)) }
        var cursorCleared = 0
        val j = join(FakeFiles(Result.success(emptyList())), ledger, FakeConfig("E1"), MutableEventStatusSource()) { cursorCleared++ }

        j.onProvision(previousEventId = "E1", newEventId = "E1")

        assertEquals(1, ledger.rows.size)
        assertEquals(0, cursorCleared)
    }

    @Test
    fun `no event configured does not enable or fetch`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        assertFalse(join(files, FakeLedgerBackend(), FakeConfig(null), MutableEventStatusSource()).ensureJoined())
        assertEquals(0, files.calls)
    }

    @Test
    fun `concurrent gate calls run the join at most once`() = runTest {
        // A list fetch that parks until released, so both ensureJoined coroutines are in flight at the
        // same time — the scenario where the grant collector and a deeplink both fire the gate at launch.
        val release = CompletableDeferred<Unit>()
        val gatedFiles = object : EventFilesSource {
            var calls = 0
            override suspend fun list(eventId: String): Result<List<RemoteAsset>> {
                calls++
                release.await()
                return Result.success(listOf(asset("A", "A-primary.heic")))
            }
        }
        val ledger = FakeLedgerBackend()
        val j = join(gatedFiles, ledger, FakeConfig("E1"), MutableEventStatusSource())

        val a = async { j.ensureJoined() }
        val b = async { j.ensureJoined() }
        runCurrent() // both start; the mutex lets only one past the gate into the fetch

        assertEquals(1, gatedFiles.calls) // the second is parked on the lock, not fetching in parallel

        release.complete(Unit)
        runCurrent()
        assertTrue(a.await())
        assertTrue(b.await())
        assertEquals(1, gatedFiles.calls) // still one: the second saw joinedThisSession and short-circuited
        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")!!.state)
    }

    @Test
    fun `a successful join with an empty remote still enables and stays settled`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend()
        val status = MutableEventStatusSource()
        val j = join(files, ledger, FakeConfig("E1"), status)

        assertTrue(j.ensureJoined())
        assertEquals(EventStatus.Joined, status.status.value)
        assertTrue(ledger.rows.isEmpty()) // nothing listed

        assertTrue(j.ensureJoined()) // joinedThisSession → still enable, no second fetch
        assertEquals(1, files.calls)
    }
}
