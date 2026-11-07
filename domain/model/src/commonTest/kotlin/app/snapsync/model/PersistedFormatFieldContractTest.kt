package app.snapsync.model

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the documents a device keeps or exchanges may leave out: the membership it stores, the invite link it reads,
 * and the manifest it publishes (the memory readings it carries into a report are `FootprintTrailTextTest`'s). Each
 * is written by one build and read by another, so which fields may be absent is the compatibility promise between
 * them — a field with a default is optional and reads as that default, every other field is required.
 */
class PersistedFormatFieldContractTest {

    @Test
    fun `a stored membership requires its identity and its bounds`() {
        assertFieldContract(
            EventConfig.serializer(),
            EventConfig(
                eventId = testEventId("11111111-1111-4111-8111-111111111111"),
                name = "Picnic",
                minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T14:00:00Z")),
                startsAt = EventStart(TimestampText.parse("2026-07-06T00:00:00Z")),
                endsAt = EventEnd(TimestampText.parse("2026-07-07T00:00:00Z")),
                maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-07T00:00:00Z")),
                deletesAt = DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
                direction = Direction.UploadOnly,
                saveToAlbum = true,
                members = MemberCounts(active = 2, settled = 1, waiting = 1),
                keyId = "k1",
                openUntil = OpenUntil(TimestampText.parse("2026-07-14T00:00:00Z")),
                deleted = true,
            ),
            optional = setOf(
                "startsAt",
                "direction",
                "saveToAlbum",
                "members",
                "keyId",
                "openUntil",
                "deleted",
            ),
        )
    }

    @Test
    fun `a stored membership's member counts require both counts and may lack the waiting one`() {
        assertFieldContract(
            MemberCounts.serializer(),
            MemberCounts(active = 2, settled = 1, waiting = 1),
            optional = setOf("waiting"),
        )
    }

    @Test
    fun `an envelope without a version is not a config file this build wrote`() {
        assertIs<ConfigFileDecode.Foreign>(decodeConfigFile("""{"payload":{}}"""))
    }

    @Test
    fun `the membership derives its start from its cutoff only when the document has none`() {
        val decoded = assertIs<ConfigFileDecode.Valid>(
            decodeConfigFile(
                """{"v":1,"payload":{"eventId":"${testEventId("e").value}","name":"n","minPhotoDate":"2026-07-06T14:00:00Z",""" +
                    """"endsAt":"2026-07-07T00:00:00Z","maxPhotoDate":"2026-07-07T00:00:00Z",""" +
                    """"deletesAt":"2026-08-05T00:00:00Z"}}""",
            ),
        )
        assertEquals(EventStart(TimestampText.parse("2026-07-06T14:00:00Z")), decoded.config.startsAt)
    }
}
