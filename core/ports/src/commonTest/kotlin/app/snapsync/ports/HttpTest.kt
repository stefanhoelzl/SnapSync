package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals

/** The `HttpTransport` port's carriers hold what they were given — the port's behaviour is its contract's. */
class HttpTest {

    @Test
    fun a_request_carries_its_method_url_headers_and_body() {
        val request = HttpRequest("PUT", "https://x/events", mapOf("Content-Type" to "application/json"), "{}")
        assertEquals(
            listOf("PUT", "https://x/events", "application/json", "{}"),
            listOf(request.method, request.url, request.headers["Content-Type"], request.body),
        )
    }

    @Test
    fun a_result_is_an_answer_or_none() {
        val response = HttpResult.Response(409, mapOf("Content-Type" to "text/plain"), "event full")
        assertEquals(
            listOf<Any?>(409, "text/plain", "event full"),
            listOf(response.status, response.headers["Content-Type"], response.body),
        )
        assertEquals("refused", HttpResult.Unreachable("refused").reason)
    }
}
