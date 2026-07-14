package app.snapsync.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePushHttpClient(private val result: Result<Unit> = Result.success(Unit)) : PushHttpClient {
    data class Call(val url: String, val body: String)

    val calls = mutableListOf<Call>()

    /** When set, the FIRST put fails (the gated 401 a fresh install takes) and later ones succeed. */
    var failFirstPut = false
    private var puts = 0

    override suspend fun put(url: String, jsonBody: String): Result<Unit> {
        calls.add(Call(url, jsonBody))
        if (failFirstPut && puts++ == 0) return Result.failure(IllegalStateException("HTTP 401 unattested"))
        return result
    }

    override suspend fun post(url: String): Result<Unit> {
        calls.add(Call(url, ""))
        return result
    }
}

class PushRegistrationTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun register_puts_the_config_url_and_body() = runTest {
        val client = FakePushHttpClient()
        PushRegistration(client, "https://edge.example", deviceId)
            .register(ApnsPushToken("DEADBEEF", "sandbox"))

        assertEquals(1, client.calls.size)
        assertEquals("https://edge.example/devices/$deviceId", client.calls[0].url)
        assertEquals(
            """{"pushToken":{"kind":"apns","token":"DEADBEEF","env":"sandbox"}}""",
            client.calls[0].body,
        )
    }

    @Test
    fun trailing_slash_on_host_is_normalized() = runTest {
        val client = FakePushHttpClient()
        PushRegistration(client, "https://edge.example/", deviceId)
            .register(ApnsPushToken("T", "production"))
        assertEquals("https://edge.example/devices/$deviceId", client.calls[0].url)
    }

    @Test
    fun request_carries_no_event_id() = runTest {
        val client = FakePushHttpClient()
        PushRegistration(client, "https://edge.example", deviceId)
            .register(ApnsPushToken("T", "sandbox"))
        assertFalse(client.calls[0].url.contains("event"))
        assertFalse(client.calls[0].body.contains("event"))
    }

    @Test
    fun failed_write_is_absorbed_not_thrown() = runTest {
        val client = FakePushHttpClient(Result.failure(RuntimeException("boom")))
        // Must not throw — a failed registration never disrupts the app.
        PushRegistration(client, "https://edge.example", deviceId)
            .register(ApnsPushToken("T", "sandbox"))
        assertEquals(1, client.calls.size)
    }

    @Test
    fun re_register_same_token_is_idempotent() = runTest {
        val client = FakePushHttpClient()
        val reg = PushRegistration(client, "https://edge.example", deviceId)
        val t = ApnsPushToken("SAME", "production")
        reg.register(t)
        reg.register(t)
        assertEquals(2, client.calls.size)
        assertEquals(client.calls[0], client.calls[1]) // identical URL+body → overwrites
    }

    @Test
    fun run_registers_on_delivery_and_on_rotation() = runTest {
        val client = FakePushHttpClient()
        val source = PushTokenSource("sandbox")
        // Unconfined so each delivery synchronously drives the collector — no StateFlow conflation
        // between the two deliveries, so the rotation is observed deterministically.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            PushRegistration(client, "https://edge.example", deviceId).run(source)
        }

        source.deliver("TOKEN1")
        source.deliver("TOKEN2") // rotation
        job.cancel()

        assertEquals(2, client.calls.size)
        assertTrue(client.calls[0].body.contains("TOKEN1"))
        assertTrue(client.calls[1].body.contains("TOKEN2"))
        // env is the source's compile-time value on every token.
        assertTrue(client.calls[0].body.contains("\"env\":\"sandbox\""))
    }

    @Test
    fun ktor_client_maps_2xx_to_success() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Created) }
        val res = KtorPushHttpClient(HttpClient(engine)).put("https://e/devices/x", "{}")
        assertTrue(res.isSuccess)
    }

    @Test
    fun ktor_client_maps_non_2xx_to_failure() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
        val res = KtorPushHttpClient(HttpClient(engine)).put("https://e/devices/x", "{}")
        assertTrue(res.isFailure)
    }

    @Test
    fun ktor_client_post_maps_2xx_to_success_and_non_2xx_to_failure() = runTest {
        val ok = MockEngine { respond("", HttpStatusCode.Accepted) }
        assertTrue(KtorPushHttpClient(HttpClient(ok)).post("https://e/events/x/notify").isSuccess)
        val bad = MockEngine { respond("nope", HttpStatusCode.BadGateway) }
        assertTrue(KtorPushHttpClient(HttpClient(bad)).post("https://e/events/x/notify").isFailure)
    }

    @Test
    fun a_refused_registration_is_retried_when_a_new_credential_arrives() = runTest {
        // The regression this exists to prevent. `PUT /devices/<id>` is gated, and on a fresh install the
        // APNs token can arrive before the device has attested — so the registration takes a 401. The OS
        // delivers an APNs token ONCE and never re-delivers it, so without a retry the device would sit
        // PERMANENTLY unregistered: no silent pushes, no download wakes, and none of the wake-driven
        // token renewals this whole design leans on.
        val client = FakePushHttpClient().apply { failFirstPut = true }
        val source = PushTokenSource("sandbox")
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val registration = PushRegistration(client, "https://edge.example", deviceId)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registration.run(source, credential)
        }

        source.deliver("DEADBEEF") // …lands before attestation → refused
        assertEquals(1, client.calls.size)

        credential.emit(Unit) // the app attests; a new token arrives

        assertEquals(2, client.calls.size) // …and the registration is re-sent
        assertTrue(client.calls.all { it.body.contains("DEADBEEF") })
    }

    @Test
    fun a_credential_change_with_no_apns_token_yet_registers_nothing() = runTest {
        val client = FakePushHttpClient()
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PushRegistration(client, "https://edge.example", deviceId).run(PushTokenSource("sandbox"), credential)
        }

        credential.emit(Unit) // attested, but the OS has delivered no APNs token yet

        assertTrue(client.calls.isEmpty())
    }
}
