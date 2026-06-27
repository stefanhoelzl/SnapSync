package app.snapsync.rejoin

import app.snapsync.config.ConfigSource
import app.snapsync.config.EventConfigPayload
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.engine.Resource
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.gallery.InMemoryGalleryResourceEnumerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class JoinEventTest {

    private val t0 = Instant.fromEpochMilliseconds(5_000_000)
    private val fixedClock = object : Clock { override fun now(): Instant = t0 }

    private class FakeFiles(var result: Result<List<RemoteFile>>) : EventFilesSource {
        var calls = 0
        override suspend fun list(eventId: String): Result<List<RemoteFile>> {
            calls++
            return result
        }
    }

    private class FakeConfig(eventId: String?) : ConfigSource {
        val flow = MutableStateFlow(eventId?.let { EventConfigPayload(it) })
        override val config: StateFlow<EventConfigPayload?> = flow
    }

    private fun res(filename: String, assetId: String, version: String) =
        Resource(filename, assetId, "image/heic", version, emptyMap(), Unit)

    private fun join(
        files: EventFilesSource,
        enumerator: InMemoryGalleryResourceEnumerator,
        ledger: FakeLedgerBackend,
        config: FakeConfig,
        status: MutableEventStatusSource,
        onCursorClear: () -> Unit = {},
    ) = JoinEvent(files, enumerator, ledger, config, status, { onCursorClear() }, fixedClock)

    @Test
    fun `empty ledger seeds only filename matches as completed at the local version`() = runTest {
        val files = FakeFiles(Result.success(listOf(RemoteFile("A-ios.photo.heic", "2026-06-20T10:31:00Z"))))
        val enumerator = InMemoryGalleryResourceEnumerator(
            listOf(res("A-ios.photo.heic", "A", "v1"), res("B-ios.photo.heic", "B", "v2")),
        )
        val ledger = FakeLedgerBackend()
        val status = MutableEventStatusSource()
        var cursorCleared = 0

        assertTrue(join(files, enumerator, ledger, FakeConfig("E1"), status) { cursorCleared++ }.ensureJoined())

        val seeded = ledger.get("A-ios.photo.heic")!!
        assertEquals(LedgerState.COMPLETED, seeded.state)
        assertEquals("v1", seeded.version)
        assertEquals(Instant.parse("2026-06-20T10:31:00Z"), seeded.updatedAt)
        assertNull(ledger.get("B-ios.photo.heic")) // not in the remote list → not seeded
        assertEquals(EventStatus.Joined, status.status.value)
        assertEquals(1, cursorCleared)
    }

    @Test
    fun `seed falls back to join time when lastModified is absent or unparseable`() = runTest {
        val files = FakeFiles(Result.success(listOf(RemoteFile("A-ios.photo.heic", null))))
        val enumerator = InMemoryGalleryResourceEnumerator(listOf(res("A-ios.photo.heic", "A", "v1")))
        val ledger = FakeLedgerBackend()

        join(files, enumerator, ledger, FakeConfig("E1"), MutableEventStatusSource()).ensureJoined()

        assertEquals(t0, ledger.get("A-ios.photo.heic")!!.updatedAt)
    }

    @Test
    fun `non-empty ledger is a no-op without fetching and leaves status unchanged`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend().apply {
            put(LedgerEntry("X", "X", LedgerState.COMPLETED, 0, "v", t0))
        }
        val status = MutableEventStatusSource()

        assertTrue(join(files, InMemoryGalleryResourceEnumerator(), ledger, FakeConfig("E1"), status).ensureJoined())

        assertEquals(0, files.calls)
        assertEquals(EventStatus.Idle, status.status.value)
    }

    @Test
    fun `list failure does not enable and does not auto-retry within the session`() = runTest {
        val files = FakeFiles(Result.failure(RuntimeException("net")))
        val ledger = FakeLedgerBackend()
        val status = MutableEventStatusSource()
        val j = join(files, InMemoryGalleryResourceEnumerator(), ledger, FakeConfig("E1"), status)

        assertFalse(j.ensureJoined())
        assertEquals(EventStatus.JoinFailed, status.status.value)
        assertTrue(ledger.rows.isEmpty())

        assertFalse(j.ensureJoined()) // no auto-retry
        assertEquals(1, files.calls)
    }

    @Test
    fun `a re-scan clears the session flags so a failed join retries`() = runTest {
        val files = FakeFiles(Result.failure(RuntimeException("net")))
        val enumerator = InMemoryGalleryResourceEnumerator(listOf(res("A-ios.photo.heic", "A", "v1")))
        val ledger = FakeLedgerBackend()
        val j = join(files, enumerator, ledger, FakeConfig("E1"), MutableEventStatusSource())
        assertFalse(j.ensureJoined())

        j.onProvision(previousEventId = "E1", newEventId = "E1") // same event re-scan
        files.result = Result.success(listOf(RemoteFile("A-ios.photo.heic", null)))

        assertTrue(j.ensureJoined())
        assertEquals(2, files.calls)
        assertEquals(LedgerState.COMPLETED, ledger.get("A-ios.photo.heic")!!.state)
    }

    @Test
    fun `switching events resets the ledger and clears the cursor`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("old", "old", LedgerState.COMPLETED, 0, "v", t0)) }
        var cursorCleared = 0
        val j = join(FakeFiles(Result.success(emptyList())), InMemoryGalleryResourceEnumerator(), ledger, FakeConfig("NEW"), MutableEventStatusSource()) { cursorCleared++ }

        j.onProvision(previousEventId = "OLD", newEventId = "NEW")

        assertTrue(ledger.rows.isEmpty())
        assertEquals(1, cursorCleared)
    }

    @Test
    fun `same-event provision leaves the ledger intact`() = runTest {
        val ledger = FakeLedgerBackend().apply { put(LedgerEntry("k", "k", LedgerState.COMPLETED, 0, "v", t0)) }
        var cursorCleared = 0
        val j = join(FakeFiles(Result.success(emptyList())), InMemoryGalleryResourceEnumerator(), ledger, FakeConfig("E1"), MutableEventStatusSource()) { cursorCleared++ }

        j.onProvision(previousEventId = "E1", newEventId = "E1")

        assertEquals(1, ledger.rows.size)
        assertEquals(0, cursorCleared)
    }

    @Test
    fun `no event configured does not enable or fetch`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        assertFalse(join(files, InMemoryGalleryResourceEnumerator(), FakeLedgerBackend(), FakeConfig(null), MutableEventStatusSource()).ensureJoined())
        assertEquals(0, files.calls)
    }

    @Test
    fun `a successful join with an empty remote still enables and stays settled`() = runTest {
        val files = FakeFiles(Result.success(emptyList()))
        val ledger = FakeLedgerBackend()
        val status = MutableEventStatusSource()
        val j = join(files, InMemoryGalleryResourceEnumerator(listOf(res("A-ios.photo.heic", "A", "v1"))), ledger, FakeConfig("E1"), status)

        assertTrue(j.ensureJoined())
        assertEquals(EventStatus.Joined, status.status.value)
        assertTrue(ledger.rows.isEmpty()) // nothing matched

        assertTrue(j.ensureJoined()) // joinedThisSession → still enable, no second fetch
        assertEquals(1, files.calls)
    }
}
