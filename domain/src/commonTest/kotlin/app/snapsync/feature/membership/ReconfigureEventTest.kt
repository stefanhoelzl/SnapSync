package app.snapsync.feature.membership

import app.snapsync.model.Direction
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.EventConfig
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class ReconfigureEventTest {

    private class FakeConfigStore : ConfigStore {
        var saved: EventConfig? = null
        override suspend fun save(config: EventConfig) { saved = config }
        override suspend fun clear() {}
    }

    private class FakeConfigSource(config: EventConfig?) : ConfigSource {
        override val config: StateFlow<EventConfig?> = MutableStateFlow(config)
    }

    // A joined membership on event E1, started 2026-07-06T12:00:00Z, cutoff already at the floor.
    private fun current(
        eventId: String = "E1",
        direction: Direction = Direction.Both,
        saveToAlbum: Boolean = false,
        minPhotoDate: CaptureCutoff = captureCutoff("2026-07-06T12:00:00Z"),
    ) = EventConfig(
        eventId = eventId,
        name = "Anna's Birthday",
        minPhotoDate = minPhotoDate,
        startsAt = eventStart("2026-07-06T12:00:00Z"),
        direction = direction,
        saveToAlbum = saveToAlbum,
    )

    private fun make(
        source: ConfigSource,
        store: ConfigStore,
        order: MutableList<String> = mutableListOf(),
    ) = ReconfigureEvent(
        configSource = source,
        store = store,
        refreshStatus = { order += "refresh" },
        armUpload = { order += "arm" },
        ensureAlbum = { order += "album" },
        startDownloads = { id -> order += "reconcile:$id" },
        cancelDownloads = { order += "cancelDownloads" },
        clearDiscoveryCursor = { order += "clearCursor" },
    )

    @Test
    fun `saves the whole config with only the three participation fields changed`() = runTest {
        val store = FakeConfigStore()
        make(FakeConfigSource(current()), store).reconfigure(
            eventId = "E1",
            direction = Direction.UploadOnly,
            chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
            chosenUpper = null,
            saveToAlbum = true,
        )

        val saved = store.saved!!
        // eventId / name / startsAt preserved; the three participation fields changed.
        assertEquals("E1", saved.eventId)
        assertEquals("Anna's Birthday", saved.name)
        assertEquals(eventStart("2026-07-06T12:00:00Z"), saved.startsAt)
        assertEquals(Direction.UploadOnly, saved.direction)
        assertEquals(captureCutoff("2026-07-06T18:00:00Z"), saved.minPhotoDate)
        assertTrue(saved.saveToAlbum)
    }

    @Test
    fun `a chosen cutoff below the floor is clamped up to startsAt`() = runTest {
        val store = FakeConfigStore()
        make(FakeConfigSource(current()), store).reconfigure(
            eventId = "E1",
            direction = Direction.Both,
            chosenCutoff = captureCutoff("2026-07-01T00:00:00Z"), // before the event start
            chosenUpper = null,
            saveToAlbum = false,
        )
        assertEquals(captureCutoff("2026-07-06T12:00:00Z"), store.saved!!.minPhotoDate)
    }

    @Test
    fun `a mismatched eventId is a no-op with no write and no effects`() = runTest {
        val store = FakeConfigStore()
        val order = mutableListOf<String>()
        // The surface was opened for E9, but the current membership is E1 (a switch landed).
        make(FakeConfigSource(current(eventId = "E1")), store, order).reconfigure(
            eventId = "E9",
            direction = Direction.DownloadOnly,
            chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
            chosenUpper = null,
            saveToAlbum = true,
        )
        assertNull(store.saved)
        assertTrue(order.isEmpty())
    }

    @Test
    fun `no config is a no-op`() = runTest {
        val store = FakeConfigStore()
        make(FakeConfigSource(null), store).reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T18:00:00Z"), null, false)
        assertNull(store.saved)
    }

    @Test
    fun `enabling upload arms the producer`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.DownloadOnly)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), null, false)
        assertTrue("arm" in order)
    }

    @Test
    fun `disabling upload does NOT stop the producer so in-flight drains`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.Both)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.DownloadOnly, captureCutoff("2026-07-06T12:00:00Z"), null, false)
        // Upload is being turned off: the arm is deliberately not driven, so nothing cancels in-flight.
        assertTrue("arm" !in order)
    }

    @Test
    fun `enabling download reconciles`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.UploadOnly)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), null, false)
        assertTrue("reconcile:E1" in order)
        assertTrue("cancelDownloads" !in order)
    }

    @Test
    fun `disabling download cancels in-flight downloads`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.Both)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.UploadOnly, captureCutoff("2026-07-06T12:00:00Z"), null, false)
        assertTrue("cancelDownloads" in order)
        assertTrue(order.none { it.startsWith("reconcile") })
    }

    // ---- the cutoff-lowering backfill fix (capability `reconfigure-membership`) -----------------------

    @Test
    fun `lowering the cutoff invalidates the discovery cursor before the arm kicks`() = runTest {
        val order = mutableListOf<String>()
        // Current cutoff is 20:00 (above the 12:00 floor); lower it to 15:00 (still above the floor).
        make(FakeConfigSource(current(minPhotoDate = captureCutoff("2026-07-06T20:00:00Z"))), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T15:00:00Z"), null, false)
        assertTrue("clearCursor" in order, "a lowered cutoff must invalidate the cursor so older photos re-enumerate")
        // Before the arm, so the next cycle re-enumerates at the new cutoff rather than off a settled cursor.
        assertTrue(order.indexOf("clearCursor") < order.indexOf("arm"))
    }

    @Test
    fun `raising the cutoff does not invalidate the discovery cursor`() = runTest {
        val order = mutableListOf<String>()
        // Current cutoff at the floor (12:00); raise it to 18:00 — narrowing, nothing new comes into scope.
        make(FakeConfigSource(current(minPhotoDate = captureCutoff("2026-07-06T12:00:00Z"))), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T18:00:00Z"), null, false)
        assertTrue("clearCursor" !in order, "raising the cutoff needs no re-enumeration and un-shares nothing")
    }

    @Test
    fun `an unchanged cutoff does not invalidate the discovery cursor`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(minPhotoDate = captureCutoff("2026-07-06T15:00:00Z"))), FakeConfigStore(), order)
            .reconfigure("E1", Direction.UploadOnly, captureCutoff("2026-07-06T15:00:00Z"), null, false)
        assertTrue("clearCursor" !in order)
    }

    @Test
    fun `always ensures the album and refreshes status`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current()), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), null, true)
        assertTrue("refresh" in order)
        assertTrue("album" in order)
    }
}
