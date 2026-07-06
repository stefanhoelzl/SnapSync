package app.snapsync.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class EventConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(config: EventConfig): EventConfig =
        json.decodeFromString(EventConfig.serializer(), json.encodeToString(EventConfig.serializer(), config))

    @Test
    fun `persists the cutoff across a serialization round-trip`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = "2026-07-06T14:32:11Z",
        )
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `an absent cutoff is null and means whole-library`() {
        val config = EventConfig(eventId = "11111111-1111-4111-8111-111111111111", name = "Birthday")
        assertEquals(null, config.minPhotoDate)
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `equality distinguishes a differing cutoff`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = "2026-07-06T14:32:11Z")
        assertEquals(base, base.copy())
        assertEquals(false, base == base.copy(minPhotoDate = "2026-07-06T14:32:12Z"))
        assertEquals(false, base == base.copy(minPhotoDate = null))
    }
}
