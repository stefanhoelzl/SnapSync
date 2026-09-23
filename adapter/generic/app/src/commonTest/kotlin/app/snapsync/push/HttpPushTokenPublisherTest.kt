package app.snapsync.push

import app.snapsync.model.ApnsPushToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HTTP binding of `PushTokenPublisher`, against answers the real edge cannot be made to produce on
 * demand (a `500`, a transport failure). What the real edge answers is the port contract's
 * (`PushTokenPublisherContract`, bound live in `LiveEdgeContractsTest`).
 */
class HttpPushTokenPublisherTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun publish_puts_the_config_url_and_body() = runTest {
        var method: HttpMethod? = null
        var url = ""
        var body = ""
        val engine = MockEngine { request ->
            method = request.method
            url = request.url.toString()
            body = request.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.NoContent)
        }
        val res = HttpPushTokenPublisher(HttpClient(engine), "https://edge.example/", deviceId = { deviceId })
            .publish(ApnsPushToken("DEADBEEF", "sandbox"))

        assertTrue(res.isSuccess)
        assertEquals(HttpMethod.Put, method)
        assertEquals("https://edge.example/devices/$deviceId", url)
        assertEquals("""{"pushToken":{"kind":"apns","token":"DEADBEEF","env":"sandbox"}}""", body)
        assertFalse("event" in url || "event" in body, "registration is event-independent")
    }

    @Test
    fun a_non_2xx_answer_is_a_failed_result() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
        val res = HttpPushTokenPublisher(HttpClient(engine), "https://e", deviceId = { deviceId })
            .publish(ApnsPushToken("T", "production"))
        assertTrue(res.isFailure)
    }

    @Test
    fun a_transport_failure_is_a_failed_result_not_a_throw() = runTest {
        val engine = MockEngine { error("connection reset") }
        val res = HttpPushTokenPublisher(HttpClient(engine), "https://e", deviceId = { deviceId })
            .publish(ApnsPushToken("T", "production"))
        assertTrue(res.isFailure)
    }
}
