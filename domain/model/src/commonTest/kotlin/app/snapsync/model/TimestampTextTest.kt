package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.time.Timestamp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The one text form of a timestamp, and the domain's values as the bare strings every JSON format carries
 * (capability `photo-sharing`). It must not disturb the **wire**: the persisted `EventConfig` is the only record of a
 * join, so a changed encoding strands every joined device.
 */
class TimestampTextTest {

    @Test
    fun `a timestamp's text is the exact UTC Z shape — to the second`() {
        assertEquals(
            "2026-07-06T14:32:11Z",
            TimestampText.format(Timestamp.of(Instant.parse("2026-07-06T14:32:11.987654Z"))),
        )
        assertEquals("2026-01-02T03:04:05Z", TimestampText.format(Timestamp.of(Instant.parse("2026-01-02T03:04:05Z"))))
        assertEquals("0001-01-01T00:00:00Z", TimestampText.format(Timestamp.of(Instant.parse("0001-01-01T00:00:00Z"))))
        assertEquals("9999-12-31T23:59:59Z", TimestampText.format(Timestamp.of(Instant.parse("9999-12-31T23:59:59Z"))))
    }

    @Test
    fun `a text from outside names a timestamp or none`() {
        assertEquals(Instant.parse("2026-07-06T14:32:11Z"), TimestampText.parse("2026-07-06T14:32:11.5Z").at)
        assertEquals(
            TimestampText.parse("2026-07-06T14:32:11Z"),
            TimestampText.parseOrNull("2026-07-06T16:32:11+02:00"),
        )
        assertNull(TimestampText.parseOrNull(""))
        assertNull(TimestampText.parseOrNull("not a date"))
        assertFailsWith<IllegalArgumentException> { TimestampText.parse("not a date") }
    }

    @Test
    fun `a library's capture text is a dated capture — or undated when it names no instant`() {
        assertEquals(
            CaptureDate.Dated(TimestampText.parse("2026-07-06T14:32:11Z")),
            TimestampText.captureDate("2026-07-06T14:32:11Z"),
        )
        assertEquals(CaptureDate.Undated, TimestampText.captureDate(""))
        assertEquals(CaptureDate.Undated, TimestampText.captureDate("not a date"))
        assertEquals(INSTANT, TimestampText.captureText(TimestampText.captureDate(INSTANT)))
        assertEquals("", TimestampText.captureText(CaptureDate.Undated))
    }

    @Test
    fun `every value serializes as its bare string and reads back`() {
        // THE compatibility property. `EventConfig` is the only record of a join and the invite QR is derived from it,
        // so an encoding that wrapped these in an object would strand every joined device permanently.
        val at = TimestampText.parse(INSTANT)
        assertBare(CaptureCutoffAsString, CaptureCutoff(at), INSTANT)
        assertBare(CaptureCeilingAsString, CaptureCeiling(at), INSTANT)
        assertBare(EventStartAsString, EventStart(at), INSTANT)
        assertBare(EventEndAsString, EventEnd(at), INSTANT)
        assertBare(OpenUntilAsString, OpenUntil(at), INSTANT)
        assertBare(DeletesAtAsString, DeletesAt(at), INSTANT)
        assertBare(AssetIdAsString, AssetId("IMG_0001"), "IMG_0001")
    }

    @Test
    fun `an id that is no event or device id does not decode`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(EventIdAsString, "\"E1\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(DeviceIdAsString, "\"D1\"") }
        assertBare(EventIdAsString, EventId.parse(UUID)!!, UUID)
        assertBare(DeviceIdAsString, DeviceId.parse(UUID)!!, UUID)
    }

    @Test
    fun `a config written before the vocabulary existed still decodes — and re-encodes to the same bytes`() {
        // The exact bytes an already-joined device has on disk: bare strings for every date.
        val persisted = """
            {"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday",
             "minPhotoDate":"2026-07-06T14:32:11Z","startsAt":"2026-07-01T09:00:00Z",
             "endsAt":"2026-07-08T09:00:00Z","maxPhotoDate":"2026-07-08T09:00:00Z",
             "deletesAt":"2026-07-31T09:00:00Z"}
        """.trimIndent()
        val decoded = Json.decodeFromString(EventConfig.serializer(), persisted)

        assertEquals(CaptureCutoff(TimestampText.parse("2026-07-06T14:32:11Z")), decoded.minPhotoDate)
        assertEquals(EventStart(TimestampText.parse("2026-07-01T09:00:00Z")), decoded.startsAt)
        assertEquals(EventEnd(TimestampText.parse("2026-07-08T09:00:00Z")), decoded.endsAt)
        assertEquals(CaptureCeiling(TimestampText.parse("2026-07-08T09:00:00Z")), decoded.maxPhotoDate)
        assertEquals(DeletesAt(TimestampText.parse("2026-07-31T09:00:00Z")), decoded.deletesAt)
        assertEquals(
            Json.parseToJsonElement(persisted),
            Json.parseToJsonElement(Json.encodeToString(EventConfig.serializer(), decoded)),
        )
    }

    private fun <T> assertBare(serializer: KSerializer<T>, value: T, text: String) {
        assertEquals("\"$text\"", Json.encodeToString(serializer, value))
        assertEquals(value, Json.decodeFromString(serializer, "\"$text\""))
    }

    private companion object {
        const val INSTANT = "2026-07-06T14:32:11Z"
        const val UUID = "7a3f9c21-0000-4000-8000-000000000001"
    }
}
