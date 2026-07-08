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

    @Test
    fun `an absent direction defaults to Both and means bidirectional`() {
        val config = EventConfig(eventId = "11111111-1111-4111-8111-111111111111", name = "Birthday")
        assertEquals(Direction.Both, config.direction)
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `persists the direction across a serialization round-trip`() {
        for (direction in Direction.entries) {
            val config = EventConfig(
                eventId = "11111111-1111-4111-8111-111111111111",
                name = "Birthday",
                minPhotoDate = "2026-07-06T14:32:11Z",
                direction = direction,
            )
            assertEquals(config, roundTrip(config))
        }
    }

    @Test
    fun `a legacy config JSON without a direction decodes to Both`() {
        // A Keychain item serialized before the field existed carries no `direction` key.
        val legacy = """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(Direction.Both, decoded.direction)
    }

    @Test
    fun `equality distinguishes a differing direction`() {
        val base = EventConfig(eventId = "e", name = "n", direction = Direction.Both)
        assertEquals(false, base == base.copy(direction = Direction.UploadOnly))
        assertEquals(false, base == base.copy(direction = Direction.DownloadOnly))
    }

    @Test
    fun `direction helpers gate the two arms`() {
        assertEquals(true, Direction.Both.includesUpload)
        assertEquals(true, Direction.Both.includesDownload)
        assertEquals(true, Direction.UploadOnly.includesUpload)
        assertEquals(false, Direction.UploadOnly.includesDownload)
        assertEquals(false, Direction.DownloadOnly.includesUpload)
        assertEquals(true, Direction.DownloadOnly.includesDownload)
    }

    @Test
    fun `an absent saveToAlbum defaults to false and means no album`() {
        val config = EventConfig(eventId = "11111111-1111-4111-8111-111111111111", name = "Birthday")
        assertEquals(false, config.saveToAlbum)
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `persists saveToAlbum across a serialization round-trip`() {
        for (flag in listOf(true, false)) {
            val config = EventConfig(
                eventId = "11111111-1111-4111-8111-111111111111",
                name = "Birthday",
                saveToAlbum = flag,
            )
            assertEquals(config, roundTrip(config))
        }
    }

    @Test
    fun `a legacy config JSON without saveToAlbum decodes to false`() {
        // A Keychain item serialized before the field existed carries no `saveToAlbum` key.
        val legacy = """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(false, decoded.saveToAlbum)
    }

    @Test
    fun `equality distinguishes a differing saveToAlbum`() {
        val base = EventConfig(eventId = "e", name = "n", saveToAlbum = false)
        assertEquals(false, base == base.copy(saveToAlbum = true))
    }

    @Test
    fun `a legacy config JSON without a name decodes to a non-null empty name`() {
        // The name was nullable before; a legacy item may lack it. It must decode non-null, not crash.
        val legacy = """{"eventId":"11111111-1111-4111-8111-111111111111"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals("", decoded.name)
    }
}
