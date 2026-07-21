package app.snapsync.feature.creation

import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.CreateEventPayload
import app.snapsync.model.decodeEventUrl
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.EventCreation
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HeadlessCreateTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val fixedNow = "2026-07-14T18:00:00Z"

    private class FakeClient(private val outcome: CreateOutcome) : EventCreation {
        var lastName: String? = null
        var lastStartsAt: String? = null
        override suspend fun create(name: String, startsAt: String): CreateOutcome {
            lastName = name
            lastStartsAt = startsAt
            return outcome
        }
    }

    /** A Kermit logger that captures its lines, so the mint-only oracle is assertable. */
    private class RecordingLog {
        val lines = mutableListOf<String>()
        val logger = Logger(
            config = StaticConfig(
                minSeverity = Severity.Verbose,
                logWriterList = listOf(
                    object : co.touchlab.kermit.LogWriter() {
                        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                            lines += message
                        }
                    },
                ),
            ),
            tag = "test",
        )
    }

    private fun exercise(
        payload: CreateEventPayload,
        outcome: CreateOutcome,
        now: () -> String = { fixedNow },
    ): Triple<FakeClient, RecordingLog, MutableList<String>> {
        val client = FakeClient(outcome)
        val rec = RecordingLog()
        val forwarded = mutableListOf<String>()
        runTest {
            HeadlessCreate(client, rec.logger, now).run(payload) { forwarded += it }
        }
        return Triple(client, rec, forwarded)
    }

    @Test
    fun `mint-only logs the created id and forwards no link`() {
        val (client, rec, forwarded) = exercise(
            CreateEventPayload(name = "  Party  "),
            CreateOutcome.Created(eventId),
        )
        assertEquals("Party", client.lastName) // trimmed
        assertEquals(fixedNow, client.lastStartsAt) // startsAt defaulted to now
        assertTrue(forwarded.isEmpty(), "mint-only must not forward a join link")
        assertTrue(rec.lines.any { it == "created eventId=$eventId" }, "oracle line missing: ${rec.lines}")
    }

    @Test
    fun `autoJoin forwards a link that round-trips to the minted id and overrides`() {
        val (_, _, forwarded) = exercise(
            CreateEventPayload(
                name = "Party",
                autoJoin = true,
                minPhotoDate = "2026-07-14T19:00:00Z",
                direction = "download",
                saveToAlbum = true,
            ),
            CreateOutcome.Created(eventId),
        )
        assertEquals(1, forwarded.size)
        val decoded = decodeEventUrl(forwarded.single())
        assertTrue(decoded is ConfigDecodeResult.Success, "synth link did not decode: $decoded")
        val p = decoded.payload
        assertEquals(eventId, p.eventId)
        assertEquals(true, p.autoJoin)
        assertEquals("2026-07-14T19:00:00Z", p.minPhotoDate)
        assertEquals("download", p.direction)
        assertEquals(true, p.saveToAlbum)
    }

    @Test
    fun `an explicit startsAt is passed through instead of now`() {
        val (client, _, _) = exercise(
            CreateEventPayload(name = "Party", startsAt = "2001-01-01T00:00:00Z"),
            CreateOutcome.Created(eventId),
            now = { throw AssertionError("now must not be read when startsAt is supplied") },
        )
        assertEquals("2001-01-01T00:00:00Z", client.lastStartsAt)
    }

    @Test
    fun `invalid name forwards no link`() {
        val (_, _, forwarded) = exercise(
            CreateEventPayload(name = "x", autoJoin = true),
            CreateOutcome.InvalidName,
        )
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun `transient failure forwards no link`() {
        val (_, _, forwarded) = exercise(
            CreateEventPayload(name = "x", autoJoin = true),
            CreateOutcome.Transient,
        )
        assertTrue(forwarded.isEmpty())
    }
}
