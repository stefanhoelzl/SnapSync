package app.snapsync.feature.membership

import app.snapsync.model.EventConfig
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Config seam + store as one fake: save writes the cell, exactly as the real Keychain adapter behaves.
private class FakeConfig(initial: EventConfig?) : ConfigSource, ConfigStore {
    private val flow = MutableStateFlow(initial)
    override val config: StateFlow<EventConfig?> = flow
    var saved: EventConfig? = null
    override suspend fun save(config: EventConfig) {
        saved = config
        flow.value = config
    }
    override suspend fun clear() {
        flow.value = null
    }
}

class EventNameTest {

    private val joined = EventConfig(
        eventId = "E",
        name = "",
        minPhotoDate = "2026-07-06T14:32:11Z",
        startsAt = "2026-07-06T14:32:11Z",
    )

    @Test
    fun `stores a changed name as the whole config with the cutoff preserved`() = runTest {
        val config = FakeConfig(joined)
        EventName(config, config).storeEventNameIfChanged("E", "Anna's Birthday")
        // The WHOLE config is saved with only `name` replaced — the cutoff (and every other
        // membership field) rides along untouched (capability `photo-selection-policy`).
        assertEquals(joined.copy(name = "Anna's Birthday"), config.saved)
    }

    @Test
    fun `an unchanged name saves nothing`() = runTest {
        val config = FakeConfig(joined.copy(name = "Anna's Birthday"))
        EventName(config, config).storeEventNameIfChanged("E", "Anna's Birthday")
        assertNull(config.saved)
    }

    @Test
    fun `a fetch resolving for a different event saves nothing`() = runTest {
        // A stale fetch landing after a switch must not resurrect the departed membership's name.
        val config = FakeConfig(joined)
        EventName(config, config).storeEventNameIfChanged("OTHER", "Someone Else's Party")
        assertNull(config.saved)
    }

    @Test
    fun `no membership saves nothing`() = runTest {
        val config = FakeConfig(null)
        EventName(config, config).storeEventNameIfChanged("E", "Anna's Birthday")
        assertNull(config.saved)
    }
}
