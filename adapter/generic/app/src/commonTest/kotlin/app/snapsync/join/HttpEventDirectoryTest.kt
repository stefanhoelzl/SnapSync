package app.snapsync.join

import app.snapsync.ports.EventDetails

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpEventDirectoryTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    private fun source(handler: MockEngine) =
        HttpEventDirectory(HttpClient(handler), "https://edge.example/")

    @Test
    fun `200 yields Found with the name startsAt endsAt and deletesAt from the event route`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content =
                    """{"eventId":"$eventId","name":"Anna's Birthday","createdAt":"2026-06-27T10:00:00.182Z","startsAt":"2026-07-14T18:00:00Z","endsAt":"2026-07-21T18:00:00Z","deletesAt":"2026-08-13T18:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = source(engine).fetch(eventId)

        assertEquals("https://edge.example/events/$eventId", requested)
        // `startsAt`/`endsAt` are the facts the gate needs — `createdAt` (millisecond-bearing) is ignored.
        assertEquals(
            EventDetails.Found(
                "Anna's Birthday",
                "2026-07-14T18:00:00Z",
                "2026-07-21T18:00:00Z",
                "2026-08-13T18:00:00Z",
            ),
            result,
        )
    }

    @Test
    fun `a legacy event's millisecond startsAt is normalized to the canonical cutoff shape`() = runTest {
        // The backend synthesizes a legacy marker's `startsAt` from `createdAt`, which `toISOString()`
        // mints WITH MILLISECONDS. Left raw, that value poisons two things downstream: the clamp is a
        // LEXICOGRAPHIC maxOf (`…00.182Z` sorts before `…00Z`), and were it to win the clamp it would be
        // persisted as the cutoff — which the iOS walk parses with a bare NSISO8601DateFormatter that
        // REJECTS a fractional second, silently costing the bounded PhotoKit fetch.
        val engine = MockEngine {
            respond(
                content =
                    """{"eventId":"$eventId","name":"Legacy","createdAt":"2026-06-27T10:00:00.182Z","startsAt":"2026-06-27T10:00:00.182Z","endsAt":"2026-07-27T10:00:00Z","deletesAt":"2026-07-27T10:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        // Truncated toward the EARLIER instant — the inclusive direction, so a photo taken within the
        // cutoff's own second is admitted rather than lost.
        assertEquals(
            EventDetails.Found("Legacy", "2026-06-27T10:00:00Z", "2026-07-27T10:00:00Z", "2026-07-27T10:00:00Z"),
            source(engine).fetch(eventId),
        )
    }

    @Test
    fun `404 yields NotFound`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        assertEquals(EventDetails.NotFound, source(engine).fetch(eventId))
    }

    @Test
    fun `a 5xx yields Failed`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a parse failure yields Failed`() = runTest {
        val engine = MockEngine {
            respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a 200 without a name yields Failed rather than a nameless Found`() = runTest {
        // The event-album title needs a name; a nameless 200 is malformed → retryable Failed.
        val engine = MockEngine {
            respond(
                content = """{"eventId":"$eventId","startsAt":"2026-07-14T18:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a 200 without a startsAt yields Failed rather than an invented floor`() = runTest {
        // `startsAt` is a FLOOR on this membership's cutoff. A client that defaulted a missing one — to
        // now, to createdAt, to anything — would be silently LOWERING that floor, which is the one
        // direction the design forbids. Failing loudly and offering Retry is the only safe reading.
        val engine = MockEngine {
            respond(
                content =
                    """{"eventId":"$eventId","name":"Anna's Birthday","createdAt":"2026-06-27T10:00:00Z","endsAt":"2026-07-21T18:00:00Z","deletesAt":"2026-08-13T18:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a 200 without an endsAt yields Failed rather than an invented ceiling`() = runTest {
        // `endsAt` is a CEILING on this membership's capture-date window. A client that defaulted a
        // missing one — to now, to startsAt + something, to anything — would be silently inventing that
        // ceiling. Failing loudly and offering Retry is the only safe reading (mirrors the startsAt floor).
        val engine = MockEngine {
            respond(
                content =
                    """{"eventId":"$eventId","name":"Anna's Birthday","createdAt":"2026-06-27T10:00:00Z","startsAt":"2026-07-14T18:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a 200 without a deletesAt yields Failed rather than an invented deadline`() = runTest {
        // `deletesAt` is one of the TWO witnesses the self-leave requires (capability `leave-event`). A
        // client that defaulted a missing one would be deciding, on its own authority, whether a
        // membership gets destroyed — and this config is the only record of the join. Failing loudly and
        // offering Retry is the only safe reading.
        val engine = MockEngine {
            respond(
                content =
                    """{"eventId":"$eventId","name":"Anna's Birthday","createdAt":"2026-06-27T10:00:00Z","startsAt":"2026-07-14T18:00:00Z","endsAt":"2026-07-21T18:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }

    @Test
    fun `a 200 with an unparseable startsAt yields Failed`() = runTest {
        val engine = MockEngine {
            respond(
                content =
                    """{"eventId":"$eventId","name":"Anna's Birthday","startsAt":"yesterday","endsAt":"2026-07-21T18:00:00Z","deletesAt":"2026-08-13T18:00:00Z"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertEquals(EventDetails.Failed, source(engine).fetch(eventId))
    }
}
