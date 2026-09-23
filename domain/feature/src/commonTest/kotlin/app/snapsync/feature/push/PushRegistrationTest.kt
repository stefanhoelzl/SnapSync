package app.snapsync.feature.push

import app.snapsync.model.ApnsPushToken
import app.snapsync.ports.PushTokenPublisher
import app.snapsync.ports.PushTokenSource

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The address and body are the adapter's (`HttpPushTokenPublisherTest`); this double records what was published. */
private class FakePushTokenPublisher(private val result: Result<Unit> = Result.success(Unit)) : PushTokenPublisher {
    val calls = mutableListOf<ApnsPushToken>()

    /** When set, the FIRST publish fails (the gated 401 a fresh install takes) and later ones succeed. */
    var failFirst = false
    private var publishes = 0

    override suspend fun publish(token: ApnsPushToken): Result<Unit> {
        calls.add(token)
        if (failFirst && publishes++ == 0) return Result.failure(IllegalStateException("HTTP 401 unattested"))
        return result
    }
}

class PushRegistrationTest {

    @Test
    fun failed_write_is_absorbed_not_thrown() = runTest {
        val client = FakePushTokenPublisher(Result.failure(RuntimeException("boom")))
        // Must not throw — a failed registration never disrupts the app.
        PushRegistration(client)
            .register(ApnsPushToken("T", "sandbox"))
        assertEquals(1, client.calls.size)
    }

    @Test
    fun re_register_same_token_is_idempotent() = runTest {
        val client = FakePushTokenPublisher()
        val reg = PushRegistration(client)
        val t = ApnsPushToken("SAME", "production")
        reg.register(t)
        reg.register(t)
        assertEquals(2, client.calls.size)
        assertEquals(client.calls[0], client.calls[1]) // identical token → overwrites
    }

    @Test
    fun run_registers_on_delivery_and_on_rotation() = runTest {
        val client = FakePushTokenPublisher()
        val source = PushTokenSource("sandbox")
        // Unconfined so each delivery synchronously drives the collector — no StateFlow conflation
        // between the two deliveries, so the rotation is observed deterministically.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            PushRegistration(client).run(source)
        }

        source.deliver("TOKEN1")
        source.deliver("TOKEN2") // rotation
        job.cancel()

        assertEquals(2, client.calls.size)
        assertEquals(ApnsPushToken("TOKEN1", "sandbox"), client.calls[0])
        // env is the source's compile-time value on every token.
        assertEquals(ApnsPushToken("TOKEN2", "sandbox"), client.calls[1])
    }

    @Test
    fun a_refused_registration_is_retried_when_a_new_credential_arrives() = runTest {
        // The regression this exists to prevent. `PUT /devices/<id>` is gated, and on a fresh install the
        // APNs token can arrive before the device has attested — so the registration takes a 401. The OS
        // delivers an APNs token ONCE and never re-delivers it, so without a retry the device would sit
        // PERMANENTLY unregistered: no silent pushes, no download wakes, and none of the wake-driven
        // token renewals this whole design leans on.
        val client = FakePushTokenPublisher().apply { failFirst = true }
        val source = PushTokenSource("sandbox")
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val registration = PushRegistration(client)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registration.run(source, credential)
        }

        source.deliver("DEADBEEF") // …lands before attestation → refused
        assertEquals(1, client.calls.size)

        credential.emit(Unit) // the app attests; a new token arrives

        assertEquals(2, client.calls.size) // …and the registration is re-sent
        assertTrue(client.calls.all { it.token == "DEADBEEF" })
    }

    @Test
    fun a_credential_change_with_no_apns_token_yet_registers_nothing() = runTest {
        val client = FakePushTokenPublisher()
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PushRegistration(client).run(PushTokenSource("sandbox"), credential)
        }

        credential.emit(Unit) // attested, but the OS has delivered no APNs token yet

        assertTrue(client.calls.isEmpty())
    }
}
