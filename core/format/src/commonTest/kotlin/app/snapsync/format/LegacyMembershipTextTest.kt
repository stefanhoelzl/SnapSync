package app.snapsync.format

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The membership as 0.4 kept it (capability `join-event`). The stakes: the file read decides "this device left the
 * event", so every text this build cannot **positively** interpret must land on the unreadable side — a future build's
 * file, or corruption, must never read as a leave.
 */
class LegacyMembershipTextTest {
    private val eventId = "8d4f0b6e-1a2b-4c3d-9e8f-0a1b2c3d4e5f"

    private fun at(text: String) = TimestampText.parse(text)

    private val minimal = LegacyMembership(
        eventId = EventId.parse(eventId)!!,
        name = "Anna's Birthday",
        minPhotoDate = CaptureCutoff(at("2026-07-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(at("2099-01-01T00:00:00Z")),
        endsAt = EventEnd(at("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(at("2099-12-31T00:00:00Z")),
    )

    private val required =
        """"eventId":"$eventId","name":"Anna's Birthday","minPhotoDate":"2026-07-01T00:00:00Z",""" +
            """"maxPhotoDate":"2099-01-01T00:00:00Z","endsAt":"2099-12-31T00:00:00Z",""" +
            """"deletesAt":"2099-12-31T00:00:00Z""""

    private fun decoded(payload: String) = LegacyMembershipText.decode(file(payload))

    private fun withField(field: String) = decoded("$required,$field")

    private fun file(payload: String) = """{"v":1,"payload":{$payload}}"""

    @Test
    fun `a file as 0_4 wrote it reads every field`() {
        val decoded = LegacyMembershipText.decode(
            file(
                "$required," + """"startsAt":"2026-06-30T00:00:00Z","direction":"UploadOnly","saveToAlbum":true,""" +
                    """"members":{"active":3,"settled":1,"waiting":2},"openUntil":"2099-06-01T00:00:00Z",""" +
                    """"deleted":true,"keyId":"0123456789abcdef"""",
            ),
        )

        assertEquals(
            LegacyMembershipDecode.Valid(
                minimal.copy(
                    startsAt = EventStart(at("2026-06-30T00:00:00Z")),
                    direction = Direction.UploadOnly,
                    saveToAlbum = true,
                    members = LegacyMemberCounts(active = 3, settled = 1, waiting = 2),
                    openUntil = OpenUntil(at("2099-06-01T00:00:00Z")),
                    deleted = true,
                    keyId = "0123456789abcdef",
                ),
            ),
            decoded,
        )
    }

    @Test
    fun `a field a file left out has the default it meant`() {
        assertEquals(LegacyMembershipDecode.Valid(minimal), LegacyMembershipText.decode(file(required)))
    }

    @Test
    fun `member counts from before the waiting count read without it`() {
        assertEquals(
            LegacyMembershipDecode.Valid(minimal.copy(members = LegacyMemberCounts(3, 1))),
            withField(""""members":{"active":3,"settled":1}"""),
        )
    }

    @Test
    fun `a future envelope version is Foreign — never Unusable`() {
        val decoded = LegacyMembershipText.decode("""{"v":2,"payload":{"something":"newer"}}""")

        assertIs<LegacyMembershipDecode.Foreign>(decoded)
    }

    @Test
    fun `bytes that are no text are Foreign — never a crash`() {
        val decoded = LegacyMembershipText.decode(byteArrayOf(0xC3.toByte(), 0x28))

        assertIs<LegacyMembershipDecode.Foreign>(decoded)
    }

    @Test
    fun `a version this build never wrote is Foreign`() {
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("""{"v":0,"payload":{}}"""))
    }

    @Test
    fun `text that is not an envelope is Foreign — never a crash`() {
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("not json at all"))
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode(""))
        // A bare membership (no envelope) lacks `v`, which has no default.
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("{$required}"))
    }

    @Test
    fun `a current-version payload without a cutoff is Unusable`() {
        // No default is substituted for the cutoff: an empty one would admit the whole library.
        assertEquals(
            LegacyMembershipDecode.Unusable,
            LegacyMembershipText.decode(file(""""eventId":"$eventId","name":"Party"""")),
        )
    }

    @Test
    fun `a field that is there and does not read is Unusable`() {
        assertEquals(LegacyMembershipDecode.Unusable, withField(""""direction":"Sideways""""))
        assertEquals(LegacyMembershipDecode.Unusable, withField(""""saveToAlbum":null"""))
        assertEquals(LegacyMembershipDecode.Unusable, withField(""""openUntil":"soon""""))
        assertEquals(LegacyMembershipDecode.Unusable, decoded(required.replace(eventId, "nope")))
    }

    @Test
    fun `a field that may hold nothing reads its null`() {
        assertEquals(
            LegacyMembershipDecode.Valid(minimal),
            withField(""""members":null,"openUntil":null,"keyId":null"""),
        )
    }

    @Test
    fun `a field with no default is required - every one`() {
        val fields = listOf("eventId", "name", "minPhotoDate", "maxPhotoDate", "endsAt", "deletesAt")
        for (left in fields) {
            val payload = required.split(",").filterNot { it.startsWith("\"$left\":") }.joinToString(",")
            assertEquals(LegacyMembershipDecode.Unusable, decoded(payload), "without $left")
        }
    }

    @Test
    fun `a field of the wrong kind is Unusable - every reader`() {
        val wrong = listOf(
            """"startsAt":"soon"""",
            """"startsAt":7""",
            """"direction":7""",
            """"saveToAlbum":"true"""",
            """"deleted":"no"""",
            """"keyId":7""",
            """"members":[]""",
            """"members":{"active":"3","settled":1}""",
            """"members":{"active":3,"settled":1,"waiting":"2"}""",
            """"members":{"active":3,"settled":"1"}""",
            """"members":{"active":{},"settled":1}""",
            """"saveToAlbum":{}""",
            """"keyId":{}""",
        )
        for (field in wrong) assertEquals(LegacyMembershipDecode.Unusable, withField(field), field)
        for (field in listOf("name", "endsAt", "maxPhotoDate", "deletesAt")) {
            val payload = required.split(",").joinToString(",") {
                if (it.startsWith("\"$field\":")) "\"$field\":7" else it
            }
            assertEquals(LegacyMembershipDecode.Unusable, decoded(payload), "$field as a number")
        }
    }

    @Test
    fun `a waiting count that is null reads as none`() {
        assertEquals(
            LegacyMembershipDecode.Valid(minimal.copy(members = LegacyMemberCounts(3, 1))),
            withField(""""members":{"active":3,"settled":1,"waiting":null}"""),
        )
    }

    @Test
    fun `an envelope whose version is no number, or that is no object, is Foreign`() {
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("""{"v":"1","payload":{}}"""))
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("""{"v":{},"payload":{}}"""))
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("""[1]"""))
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("""{"payload":{}}"""))
        assertIs<LegacyMembershipDecode.Foreign>(LegacyMembershipText.decode("""{"v":1.5,"payload":{}}"""))
        assertEquals(LegacyMembershipDecode.Unusable, LegacyMembershipText.decode("""{"v":1,"payload":[]}"""))
    }

    @Test
    fun `the file's bytes read as its text`() {
        val bytes = file(required).encodeToByteArray()

        assertEquals(LegacyMembershipDecode.Valid(minimal), LegacyMembershipText.decode(bytes))
    }

    @Test
    fun `a member count requires both counts`() {
        assertEquals(LegacyMembershipDecode.Unusable, withField(""""members":{"active":3}"""))
        assertEquals(LegacyMembershipDecode.Unusable, withField(""""members":{"settled":1}"""))
    }

    @Test
    fun `an empty name reads - the name is required, not non-empty`() {
        val decoded = decoded(required.replace("Anna's Birthday", ""))

        assertEquals(LegacyMembershipDecode.Valid(minimal.copy(name = "")), decoded)
    }

    @Test
    fun `a current-version envelope without a payload is Unusable`() {
        assertEquals(LegacyMembershipDecode.Unusable, LegacyMembershipText.decode("""{"v":1}"""))
    }

    @Test
    fun `unknown keys are ignored on envelope and payload — additive change needs no version bump`() {
        val decoded =
            LegacyMembershipText.decode("""{"v":1,"extra":"ignored","payload":{$required,"novel":"ignored"}}""")

        assertEquals(LegacyMembershipDecode.Valid(minimal), decoded)
    }
}
