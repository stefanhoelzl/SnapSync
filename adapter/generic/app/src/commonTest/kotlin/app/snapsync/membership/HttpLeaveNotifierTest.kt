package app.snapsync.membership

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpLeaveNotifierTest {

    private val eventId = "7a3f9c21-0000-4000-8000-000000000001"
    private val deviceId = "11111111-0000-4000-8000-000000000002"

    private fun notifier(handler: MockEngine) =
        HttpLeaveNotifier(HttpClient(handler), "https://edge.example/")

    @Test
    fun `leave issues a DELETE to the event-device route and succeeds on 2xx`() = runTest {
        var requested: String? = null
        var method: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            respond(content = "", status = HttpStatusCode.OK)
        }

        val result = notifier(engine).leave(eventId, deviceId)

        assertTrue(result.isSuccess)
        kotlin.test.assertEquals("https://edge.example/events/$eventId/devices/$deviceId", requested)
        kotlin.test.assertEquals("DELETE", method)
    }

    @Test
    fun `a non-2xx response is a failed Result and not a throw`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertTrue(notifier(engine).leave(eventId, deviceId).isFailure)
    }

    @Test
    fun `a transport failure is a failed Result and not a throw`() = runTest {
        val engine = MockEngine { throw RuntimeException("offline") }
        assertTrue(notifier(engine).leave(eventId, deviceId).isFailure)
    }
}
