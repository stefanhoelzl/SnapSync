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

    private fun notifier(handler: MockEngine, id: () -> String = { deviceId }) =
        HttpLeaveNotifier(HttpClient(handler), "https://edge.example/", id)

    @Test
    fun `leave issues a DELETE to the event-device route and succeeds on 2xx`() = runTest {
        var requested: String? = null
        var method: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            respond(content = "", status = HttpStatusCode.OK)
        }

        val result = notifier(engine).notifyLeaving(eventId)

        assertTrue(result.isSuccess)
        kotlin.test.assertEquals("https://edge.example/events/$eventId/devices/$deviceId", requested)
        kotlin.test.assertEquals("DELETE", method)
    }

    @Test
    fun `a non-2xx response is a failed Result and not a throw`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        assertTrue(notifier(engine).notifyLeaving(eventId).isFailure)
    }

    @Test
    fun `a transport failure is a failed Result and not a throw`() = runTest {
        val engine = MockEngine { throw RuntimeException("offline") }
        assertTrue(notifier(engine).notifyLeaving(eventId).isFailure)
    }

    @Test
    fun `the device id is resolved per call and never at construction`() = runTest {
        // The identity read is a Keychain hit on iOS and must not happen while composing a locked
        // background launch — so binding the notifier may not resolve it, and each call must see the
        // value as of that moment rather than a snapshot taken earlier.
        var resolved = 0
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.OK) }
        val notifier = notifier(engine) { resolved++; deviceId }

        kotlin.test.assertEquals(0, resolved, "constructing the notifier resolved the device identity")
        notifier.notifyLeaving(eventId)
        notifier.notifyLeaving(eventId)
        kotlin.test.assertEquals(2, resolved)
    }

    @Test
    fun `a device id that cannot be resolved is a failed Result and not a throw`() = runTest {
        // Same best-effort contract as the network half: a leave whose identity read throws (protected
        // data unavailable) must not take the caller's local teardown down with it.
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.OK) }
        val notifier = notifier(engine) { error("protected data unavailable") }
        assertTrue(notifier.notifyLeaving(eventId).isFailure)
    }
}
