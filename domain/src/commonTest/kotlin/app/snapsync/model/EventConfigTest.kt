package app.snapsync.model

import app.snapsync.model.EventStart
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json


/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class EventConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Every config carries a cutoff; it is required (capability `photo-selection-policy`). */
    private val cutoff = captureCutoff("2026-07-06T14:32:11Z")

    private fun roundTrip(config: EventConfig): EventConfig =
        json.decodeFromString(EventConfig.serializer(), json.encodeToString(EventConfig.serializer(), config))

    @Test
    fun `persists the cutoff across a serialization round-trip`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = cutoff,
            maxPhotoDate = FIXTURE_CEILING,
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
    fun `a config JSON without a ceiling fails to decode`() {
        // THE deliberate reversal (capability `join-event`). A membership persisted before the capture-date
        // ceiling existed used to decode with a `null` ceiling meaning *unbounded*, pending the reconcile
        // backfill. It no longer decodes at all: the ceiling is required, and an unbounded membership is
        // not a representable state — the same posture `minPhotoDate` has always had, for the same reason
        // (a bound that silently means "everything" is the dangerous direction).
        //
        // Safe only by sequencing, and the sequence has run: the ceiling and its reconcile backfill shipped
        // together in `add-event-date-range`, so a device that has foregrounded since already persisted a
        // concrete ceiling. One that has not loses its membership on update — the accepted cost on this
        // controlled install base, with a device reset as the escape hatch.
        val preCeiling =
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff"}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString(EventConfig.serializer(), preCeiling)
        }
    }

    @Test
    fun `a config JSON carrying a ceiling decodes to a concrete upper bound`() {
        val current = """{"eventId":"e","name":"B","minPhotoDate":"$cutoff","maxPhotoDate":"2099-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), current)
        assertEquals(captureCeiling("2099-01-01T00:00:00Z"), decoded.maxPhotoDate)
    }

    @Test
    fun `equality distinguishes a differing cutoff`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff, maxPhotoDate = FIXTURE_CEILING)
        assertEquals(base, base.copy())
        assertEquals(false, base == base.copy(minPhotoDate = captureCutoff("2026-07-06T14:32:12Z")))
    }

    @Test
    fun `an absent direction defaults to Both and means bidirectional`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = cutoff,
            maxPhotoDate = FIXTURE_CEILING,
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
                minPhotoDate = cutoff, maxPhotoDate = FIXTURE_CEILING,
                direction = direction,
            )
            assertEquals(config, roundTrip(config))
        }
    }

    @Test
    fun `a legacy config JSON without a direction decodes to Both`() {
        // A Keychain item serialized before the field existed carries no `direction` key.
        val legacy =
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff","maxPhotoDate":"2099-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(Direction.Both, decoded.direction)
    }

    @Test
    fun `equality distinguishes a differing direction`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff, maxPhotoDate = FIXTURE_CEILING, direction = Direction.Both)
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
            maxPhotoDate = FIXTURE_CEILING,
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
                minPhotoDate = cutoff, maxPhotoDate = FIXTURE_CEILING,
                saveToAlbum = flag,
            )
            assertEquals(config, roundTrip(config))
        }
    }

    @Test
    fun `a legacy config JSON without saveToAlbum decodes to false`() {
        // A Keychain item serialized before the field existed carries no `saveToAlbum` key.
        val legacy =
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff","maxPhotoDate":"2099-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(false, decoded.saveToAlbum)
    }

    @Test
    fun `equality distinguishes a differing saveToAlbum`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff, maxPhotoDate = FIXTURE_CEILING, saveToAlbum = false)
        assertEquals(false, base == base.copy(saveToAlbum = true))
    }

    @Test
    fun `a config JSON without a name does not decode`() {
        // `name` carries NO default, so the key is required. The point is not the decode failure itself
        // but what having no default buys: `name` is a required CONSTRUCTOR parameter, so no construction
        // site can omit it. The two are one knob — the @Serializable plugin derives the decode default
        // from the constructor default.
        val nameless = """{"eventId":"11111111-1111-4111-8111-111111111111","minPhotoDate":"$cutoff","maxPhotoDate":"2099-01-01T00:00:00Z"}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString(EventConfig.serializer(), nameless)
        }
    }

    @Test
    fun `a config JSON whose name is the empty string DOES decode`() {
        // Pinned deliberately, as a weakness rather than a strength: requiring the key is not requiring a
        // non-blank value, so nothing in this type stops a blank name. The ONE guard is
        // `HttpEventDirectory`, which maps a blank name to `EventDetails.Failed` — do not read the
        // declaration above as though it made a blank name unrepresentable.
        val blank = """{"eventId":"11111111-1111-4111-8111-111111111111","name":"","minPhotoDate":"$cutoff","maxPhotoDate":"2099-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), blank)
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
            """{"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday","minPhotoDate":"$cutoff","maxPhotoDate":"2099-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString(EventConfig.serializer(), legacy)
        assertEquals(EventStart(cutoff.at), decoded.startsAt)
        assertEquals(cutoff, decoded.minPhotoDate)
    }

    @Test
    fun `persists startsAt across a serialization round-trip`() {
        val config = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Birthday",
            minPhotoDate = captureCutoff("2026-07-14T21:00:00Z"), maxPhotoDate = FIXTURE_CEILING,
            startsAt = eventStart("2026-07-14T18:00:00Z"),
        )
        assertEquals(config, roundTrip(config))
        // The two are independent facts: a member who joined late sits ABOVE the event's floor.
        assertEquals(eventStart("2026-07-14T18:00:00Z"), roundTrip(config).startsAt)
        assertEquals(captureCutoff("2026-07-14T21:00:00Z"), roundTrip(config).minPhotoDate)
    }

    @Test
    fun `equality distinguishes a differing startsAt`() {
        val base = EventConfig(eventId = "e", name = "n", minPhotoDate = cutoff, maxPhotoDate = FIXTURE_CEILING)
        assertEquals(false, base == base.copy(startsAt = eventStart("2001-01-01T00:00:00Z")))
    }

    @Test
    fun `an empty cutoff would admit every asset — it is never a valid value`() {
        // Guards the trap that makes `minPhotoDate = captureCutoff("")` an unsafe legacy default: the cutoff compare is
        // `creationDate >= minPhotoDate`, and every string is `>= ""`. The mirror case is the safe one an
        // empty-string default is easily confused with: an undated ASSET is excluded by any real cutoff.
        assertEquals(true, "2026-07-06T14:32:11Z" >= "")
        assertEquals(true, "" >= "")
        assertEquals(false, "" >= "2026-07-06T14:32:11Z")
    }
}
