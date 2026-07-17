package app.snapsync.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class EventConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Every config carries a cutoff; it is required (capability `photo-selection-policy`). */
    private val cutoff = "2026-07-06T14:32:11Z"

    private fun roundTrip(config: EventConfig): EventConfig =
        json.decodeFromString(EventConfig.serializer(), json.encodeToString(EventConfig.serializer(), config))

    @Test
    fun `persists the cutoff across a serialization round-trip`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = cutoff,
        )
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `a legacy config JSON without a cutoff fails to decode`() {
        // The cutoff is required with no default: an item written before it existed must NOT decode to
        // some default, because every plausible default (notably "") means whole-library scope. The
        // Keychain store maps this failure to "no config", so the device re-joins.
        val legacy = """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday"}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString(EventConfig.serializer(), legacy)
        }
    }

    @Test
    fun `equality distinguishes a differing cutoff`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff)
        assertEquals(base, base.copy())
        assertEquals(false, base == base.copy(minPhotoDate = "2026-07-06T14:32:12Z"))
    }

    @Test
    fun `an absent direction defaults to Both and means bidirectional`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = cutoff,
        )
        assertEquals(Direction.Both, config.direction)
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `persists the direction across a serialization round-trip`() {
        for (direction in Direction.entries) {
            val config = EventConfig(
                eventId = "11111111-1111-4111-8111-111111111111",
                name = "Birthday",
                minPhotoDate = cutoff,
                direction = direction,
            )
            assertEquals(config, roundTrip(config))
        }
    }

    @Test
    fun `a legacy config JSON without a direction decodes to Both`() {
        // A Keychain item serialized before the field existed carries no `direction` key.
        val legacy =
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(Direction.Both, decoded.direction)
    }

    @Test
    fun `equality distinguishes a differing direction`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff, direction = Direction.Both)
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
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = cutoff,
        )
        assertEquals(false, config.saveToAlbum)
        assertEquals(config, roundTrip(config))
    }

    @Test
    fun `persists saveToAlbum across a serialization round-trip`() {
        for (flag in listOf(true, false)) {
            val config = EventConfig(
                eventId = "11111111-1111-4111-8111-111111111111",
                name = "Birthday",
                minPhotoDate = cutoff,
                saveToAlbum = flag,
            )
            assertEquals(config, roundTrip(config))
        }
    }

    @Test
    fun `a legacy config JSON without saveToAlbum decodes to false`() {
        // A Keychain item serialized before the field existed carries no `saveToAlbum` key.
        val legacy =
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(false, decoded.saveToAlbum)
    }

    @Test
    fun `equality distinguishes a differing saveToAlbum`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff, saveToAlbum = false)
        assertEquals(false, base == base.copy(saveToAlbum = true))
    }

    @Test
    fun `a legacy config JSON without a name decodes to a non-null empty name`() {
        // The name was nullable before; a legacy item may lack it. It must decode non-null, not crash.
        val legacy = """{"eventId":"11111111-1111-4111-8111-111111111111","minPhotoDate":"$cutoff"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals("", decoded.name)
    }

    @Test
    fun `a legacy config JSON without startsAt decodes to its cutoff`() {
        // THE migration this change rests on. Unlike `minPhotoDate`, `startsAt` DOES default — because
        // `EventConfig` is the only holder of the `eventId` and the invite QR derives from it, so a
        // decode failure would strand the member outside their own event with no way back in.
        // `minPhotoDate` is the only default guaranteed consistent with the floor invariant
        // (`minPhotoDate >= startsAt`, here with equality).
        val legacy =
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(cutoff, decoded.startsAt)
        assertEquals(cutoff, decoded.minPhotoDate)
    }

    @Test
    fun `persists startsAt across a serialization round-trip`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = "2026-07-14T21:00:00Z",
            startsAt = "2026-07-14T18:00:00Z",
        )
        assertEquals(config, roundTrip(config))
        // The two are independent facts: a member who joined late sits ABOVE the event's floor.
        assertEquals("2026-07-14T18:00:00Z", roundTrip(config).startsAt)
        assertEquals("2026-07-14T21:00:00Z", roundTrip(config).minPhotoDate)
    }

    @Test
    fun `equality distinguishes a differing startsAt`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff)
        assertEquals(false, base == base.copy(startsAt = "2001-01-01T00:00:00Z"))
    }

    @Test
    fun `an empty cutoff would admit every asset — it is never a valid value`() {
        // Guards the trap that makes `minPhotoDate = ""` an unsafe legacy default: the cutoff compare is
        // `creationDate >= minPhotoDate`, and every string is `>= ""`. The mirror case is the safe one an
        // empty-string default is easily confused with: an undated ASSET is excluded by any real cutoff.
        assertEquals(true, "2026-07-06T14:32:11Z" >= "")
        assertEquals(true, "" >= "")
        assertEquals(false, "" >= "2026-07-06T14:32:11Z")
    }
}
