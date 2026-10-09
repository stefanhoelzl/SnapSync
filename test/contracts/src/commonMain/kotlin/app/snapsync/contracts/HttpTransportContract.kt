package app.snapsync.contracts

import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult
import app.snapsync.ports.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Whether anything answers at the address a clause sends to. */
enum class HttpTransportState {
    /** The loopback transfer fixture answers ([TransferFixture]): every answer is the one the clause's route asks for. */
    PEER,

    /** Nothing listens there: a connection is refused. */
    NO_PEER,
}

/** A clause's subject: the transport, and the base URL its state answers at. */
class TransportUnderTest(val transport: HttpTransport, val base: String)

/**
 * What `HttpTransport` promises (the port's KDoc carries why): any answer is a [HttpResult.Response] carrying its
 * status, headers and body — a `4xx` as much as a `2xx`; a sent body and its media type arrive; and no answer at all —
 * nothing listening, a body cut short — is [HttpResult.Unreachable], never a throw.
 *
 * Bound for every engine the app ships (`docs/testing.md`): CIO and OkHttp on the JVM, OkHttp on the Android emulator,
 * Darwin on the iOS simulator — each against the loopback transfer fixture (`scripts/transfer-fixture.py`).
 */
object HttpTransportContract : Contract<HttpTransportState, TransportUnderTest>("HttpTransport") {

    private fun TransportUnderTest.url(clauseId: String, answer: FixtureAnswer) =
        base.trimEnd('/') + TransferFixture.path(name, clauseId, "exchange", answer)

    override val clauses = clauses {

        clause(
            "A_SUCCESS_IS_A_RESPONSE_WITH_ITS_HEADERS_AND_BODY",
            HttpTransportState.PEER,
            covers = cells { on<HttpTransport>().answers(HttpTransport::send).with(HttpResult.Response::class) },
        ) { s ->
            val url = s.url(
                "A_SUCCESS_IS_A_RESPONSE_WITH_ITS_HEADERS_AND_BODY",
                FixtureAnswer.Respond(200, length = 64),
            )
            val response = assertIs<HttpResult.Response>(s.transport.send(HttpRequest("GET", url, emptyMap(), null)))
            assertEquals(200, response.status)
            assertEquals(64, response.body.length, "the whole body is read")
            assertEquals(
                "no-store",
                response.headers.entries.single { it.key.equals("Cache-Control", ignoreCase = true) }.value,
                "the answer's headers are carried",
            )
        }

        clause(
            "A_REFUSAL_IS_A_RESPONSE_NOT_A_FAILURE",
            HttpTransportState.PEER,
            covers = cells { on<HttpTransport>().answers(HttpTransport::send).with(HttpResult.Response::class) },
        ) { s ->
            val url = s.url("A_REFUSAL_IS_A_RESPONSE_NOT_A_FAILURE", FixtureAnswer.Respond(404, declaresLength = false))
            val response = assertIs<HttpResult.Response>(s.transport.send(HttpRequest("GET", url, emptyMap(), null)))
            assertEquals(404, response.status, "a 4xx is the server's answer, which the gateway reads")
        }

        clause(
            "A_SENT_BODY_ARRIVES_WITH_ITS_MEDIA_TYPE",
            HttpTransportState.PEER,
            covers = cells { on<HttpTransport>().answers(HttpTransport::send).with(HttpResult.Response::class) },
        ) { s ->
            val path = TransferFixture.path(
                name,
                "A_SENT_BODY_ARRIVES_WITH_ITS_MEDIA_TYPE",
                "put",
                FixtureAnswer.Respond(201),
            )
            val body = """{"name":"Anna's Birthday"}"""
            val sent = s.transport.send(
                HttpRequest("PUT", s.base.trimEnd('/') + path, mapOf("Content-Type" to "application/json"), body),
            )
            assertEquals(201, assertIs<HttpResult.Response>(sent).status)
            val landed = assertIs<HttpResult.Response>(
                s.transport.send(HttpRequest("GET", s.base.trimEnd('/') + "/_landed" + path, emptyMap(), null)),
            )
            val record = Json.parseToJsonElement(landed.body).jsonObject
            assertEquals("application/json", record.getValue("contentType").jsonPrimitive.content.substringBefore(';'))
            assertEquals(body.encodeToByteArray().size, record.getValue("length").jsonPrimitive.int, "the whole body")
        }

        clause(
            "A_BODY_CUT_SHORT_IS_UNREACHABLE",
            HttpTransportState.PEER,
            covers = cells { on<HttpTransport>().answers(HttpTransport::send).with(HttpResult.Unreachable::class) },
        ) { s ->
            val url = s.url("A_BODY_CUT_SHORT_IS_UNREACHABLE", FixtureAnswer.Respond(200, length = 64, short = true))
            assertIs<HttpResult.Unreachable>(
                s.transport.send(HttpRequest("GET", url, emptyMap(), null)),
                "half an answer is no answer — and never a throw",
            )
        }

        clause(
            "NOTHING_LISTENING_IS_UNREACHABLE",
            HttpTransportState.NO_PEER,
            covers = cells { on<HttpTransport>().answers(HttpTransport::send).with(HttpResult.Unreachable::class) },
        ) { s ->
            val url = s.url("NOTHING_LISTENING_IS_UNREACHABLE", FixtureAnswer.Respond(200))
            assertIs<HttpResult.Unreachable>(
                s.transport.send(HttpRequest("GET", url, emptyMap(), null)),
                "a refused connection is no answer — and never a throw",
            )
        }
    }
}
