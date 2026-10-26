package app.snapsync.model

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.TimestampText
import app.snapsync.testids.testAssetId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The domain's values as the bare strings every JSON format carries (capability `photo-sharing`). It must not disturb
 * the **wire**: the persisted `EventConfig` is the only record of a join, so a changed encoding strands every joined
 * device.
 */
class DomainSerializersTest {

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
        assertBare(AssetIdAsString, testAssetId("IMG_0001"), "IMG_0001")
        assertBare(ResourceRoleAsString, ResourceRole.LIVE, "live")
    }

    @Test
    fun `an id that is no event or device id does not decode`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(EventIdAsString, "\"E1\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(DeviceIdAsString, "\"D1\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(AssetIdAsString, "\"a/b\"") }
        assertFailsWith<SerializationException> { Json.decodeFromString(ResourceRoleAsString, "\"PRIMARY\"") }
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
