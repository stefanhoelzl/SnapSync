package app.snapsync.feature.membership

import app.snapsync.model.Direction
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
        minPhotoDate: String = "2026-07-06T12:00:00Z",
    ) = EventConfig(
        eventId = eventId,
        name = "Anna's Birthday",
        minPhotoDate = minPhotoDate,
        startsAt = "2026-07-06T12:00:00Z",
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
    )

    @Test
    fun `saves the whole config with only the three participation fields changed`() = runTest {
        val store = FakeConfigStore()
        make(FakeConfigSource(current()), store).reconfigure(
            eventId = "E1",
            direction = Direction.UploadOnly,
            chosenCutoff = "2026-07-06T18:00:00Z",
            saveToAlbum = true,
        )

        val saved = store.saved!!
        // eventId / name / startsAt preserved; the three participation fields changed.
        assertEquals("E1", saved.eventId)
        assertEquals("Anna's Birthday", saved.name)
        assertEquals("2026-07-06T12:00:00Z", saved.startsAt)
        assertEquals(Direction.UploadOnly, saved.direction)
        assertEquals("2026-07-06T18:00:00Z", saved.minPhotoDate)
        assertTrue(saved.saveToAlbum)
    }

    @Test
    fun `a chosen cutoff below the floor is clamped up to startsAt`() = runTest {
        val store = FakeConfigStore()
        make(FakeConfigSource(current()), store).reconfigure(
            eventId = "E1",
            direction = Direction.Both,
            chosenCutoff = "2026-07-01T00:00:00Z", // before the event start
            saveToAlbum = false,
        )
        assertEquals("2026-07-06T12:00:00Z", store.saved!!.minPhotoDate)
    }

    @Test
    fun `a mismatched eventId is a no-op with no write and no effects`() = runTest {
        val store = FakeConfigStore()
        val order = mutableListOf<String>()
        // The surface was opened for E9, but the current membership is E1 (a switch landed).
        make(FakeConfigSource(current(eventId = "E1")), store, order).reconfigure(
            eventId = "E9",
            direction = Direction.DownloadOnly,
            chosenCutoff = "2026-07-06T18:00:00Z",
            saveToAlbum = true,
        )
        assertNull(store.saved)
        assertTrue(order.isEmpty())
    }

    @Test
    fun `no config is a no-op`() = runTest {
        val store = FakeConfigStore()
        make(FakeConfigSource(null), store).reconfigure("E1", Direction.Both, "2026-07-06T18:00:00Z", false)
        assertNull(store.saved)
    }

    @Test
    fun `enabling upload arms the producer`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.DownloadOnly)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, "2026-07-06T12:00:00Z", false)
        assertTrue("arm" in order)
    }

    @Test
    fun `disabling upload does NOT stop the producer so in-flight drains`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.Both)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.DownloadOnly, "2026-07-06T12:00:00Z", false)
        // Upload is being turned off: the arm is deliberately not driven, so nothing cancels in-flight.
        assertTrue("arm" !in order)
    }

    @Test
    fun `enabling download reconciles`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.UploadOnly)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, "2026-07-06T12:00:00Z", false)
        assertTrue("reconcile:E1" in order)
        assertTrue("cancelDownloads" !in order)
    }

    @Test
    fun `disabling download cancels in-flight downloads`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current(direction = Direction.Both)), FakeConfigStore(), order)
            .reconfigure("E1", Direction.UploadOnly, "2026-07-06T12:00:00Z", false)
        assertTrue("cancelDownloads" in order)
        assertTrue(order.none { it.startsWith("reconcile") })
    }

    @Test
    fun `always ensures the album and refreshes status`() = runTest {
        val order = mutableListOf<String>()
        make(FakeConfigSource(current()), FakeConfigStore(), order)
            .reconfigure("E1", Direction.Both, "2026-07-06T12:00:00Z", true)
        assertTrue("refresh" in order)
        assertTrue("album" in order)
    }
}
