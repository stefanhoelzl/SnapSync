package app.snapsync.http

import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What the contract's fixture cannot choose: the media type a body travels with when the request names none. */
class KtorHttpTransportTest {

    @Test
    fun a_body_with_no_content_type_travels_as_plain_text() = runTest {
        var sent: String? = null
        val engine = MockEngine { request ->
            sent = request.body.contentType?.toString()
            respond("", HttpStatusCode.NoContent)
        }
        val request = HttpRequest("PUT", "http://x/y", mapOf("x-snapsync-app-version" to "1.0"), "hi")
        val result = KtorHttpTransport(HttpClient(engine)).send(request)
        assertEquals(204, assertIs<HttpResult.Response>(result).status)
        assertEquals("text/plain", sent?.substringBefore(';'))
    }

    @Test
    fun a_named_content_type_is_the_body_s_and_no_plain_header() = runTest {
        var sent: String? = null
        val engine = MockEngine { request ->
            sent = request.body.contentType?.toString()
            respond("{}", HttpStatusCode.OK)
        }
        val headers = mapOf("x-snapsync-app-version" to "1.0", "content-type" to "application/json")
        KtorHttpTransport(HttpClient(engine)).send(HttpRequest("POST", "http://x/y", headers, "{}"))
        assertEquals("application/json", sent?.substringBefore(';'))
    }
}
