@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.push

import app.snapsync.model.ApnsPushToken
import app.snapsync.ports.DeviceIdentity
import app.snapsync.ports.PushRegistrationRecord
import app.snapsync.ports.PushTokenPublisher
import app.snapsync.ports.PushTokenSource
import app.snapsync.ports.SecureStoreUnavailable

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

/** The record the adapter keeps in a file; here, a cell the test can read. */
private class FakeRecord(var value: String? = null) : PushRegistrationRecord {
    override fun loadLastRegistered(): String? = value
    override fun saveLastRegistered(value: String) {
        this.value = value
    }
}

/** A device identity the test can change (a device reset) or make unreadable (a locked device). */
private class FakeIdentity(var id: String = "DEVICE-1", var locked: Boolean = false) : DeviceIdentity {
    override fun deviceId(): String = if (locked) throw SecureStoreUnavailable("locked") else id
}

class PushRegistrationTest {

    private val record = FakeRecord()
    private val identity = FakeIdentity()

    private fun registration(publisher: PushTokenPublisher) = PushRegistration(publisher, record, identity)

    @Test
    fun failed_write_is_absorbed_not_thrown() = runTest {
        val client = FakePushTokenPublisher(Result.failure(RuntimeException("boom")))
        // Must not throw — a failed registration never disrupts the app.
        registration(client).register(ApnsPushToken("T", "sandbox"))
        assertEquals(1, client.calls.size)
    }

    /**
     * B11: an unreadable device identity (a locked device) used to throw OUT of `register`, which ends `run`'s
     * collector for the rest of the process — no later token or credential change would ever re-register. The
     * publisher now reports it as a failed result, and even a publisher that throws is absorbed: the NEXT trigger
     * registers once it can.
     */
    @Test
    fun a_throwing_publish_is_absorbed_and_the_next_trigger_registers() = runTest {
        var locked = true
        val calls = mutableListOf<ApnsPushToken>()
        val registration = registration(
            PushTokenPublisher { token ->
                if (locked) throw IllegalStateException("secure store unavailable")
                calls += token
                Result.success(Unit)
            },
        )
        val source = PushTokenSource("sandbox")
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val job = launch { registration.run(source, credential) }

        source.deliver("DEADBEEF")
        testScheduler.runCurrent()
        assertEquals(0, calls.size, "nothing published while it throws")

        locked = false
        credential.tryEmit(Unit)
        testScheduler.runCurrent()
        assertEquals(1, calls.size, "the collector survived and registered on the next trigger")
        job.cancel()
    }

    @Test
    fun an_unconditional_register_publishes_even_when_the_record_matches() = runTest {
        // The join trigger: whatever the record believes, a join re-PUTs — it is what heals a warm rejoin whose
        // backend registration is absent.
        val client = FakePushTokenPublisher()
        val reg = registration(client)
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
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(source) }

        source.deliver("TOKEN1")
        source.deliver("TOKEN2") // rotation
        job.cancel()

        assertEquals(2, client.calls.size)
        assertEquals(ApnsPushToken("TOKEN1", "sandbox"), client.calls[0])
        // env is the source's compile-time value on every token.
        assertEquals(ApnsPushToken("TOKEN2", "sandbox"), client.calls[1])
    }

    @Test
    fun an_unchanged_triple_is_not_re_published() = runTest {
        // The OS answers every app entry's ask, mostly with the same token. Only the first answer publishes.
        val client = FakePushTokenPublisher()
        val source = PushTokenSource("sandbox")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(source) }

        source.deliver("TOKEN1")
        source.deliver("TOKEN1") // the next foreground entry's answer
        source.deliver("TOKEN1")
        job.cancel()

        assertEquals(listOf(ApnsPushToken("TOKEN1", "sandbox")), client.calls)
    }

    @Test
    fun a_record_from_an_earlier_process_suppresses_the_launch_publish() = runTest {
        // A cold start — a background wake included — delivers the token the backend already holds.
        val client = FakePushTokenPublisher()
        registration(client).register(ApnsPushToken("TOKEN1", "sandbox"))
        client.calls.clear()

        val relaunched = PushTokenSource("sandbox")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(relaunched) }
        relaunched.deliver("TOKEN1")
        job.cancel()

        assertTrue(client.calls.isEmpty(), "an unchanged launch publishes nothing: ${client.calls}")
    }

    @Test
    fun a_changed_env_is_published() = runTest {
        val client = FakePushTokenPublisher()
        registration(client).register(ApnsPushToken("TOKEN1", "sandbox"))
        client.calls.clear()

        val production = PushTokenSource("production")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(production) }
        production.deliver("TOKEN1")
        job.cancel()

        assertEquals(listOf(ApnsPushToken("TOKEN1", "production")), client.calls)
    }

    @Test
    fun a_changed_device_identity_is_published() = runTest {
        val client = FakePushTokenPublisher()
        registration(client).register(ApnsPushToken("TOKEN1", "sandbox"))
        client.calls.clear()

        identity.id = "DEVICE-2" // a device reset minted a new identity
        val source = PushTokenSource("sandbox")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(source) }
        source.deliver("TOKEN1")
        job.cancel()

        assertEquals(listOf(ApnsPushToken("TOKEN1", "sandbox")), client.calls, "the backend never saw this device")
    }

    @Test
    fun a_failed_publish_is_not_recorded_and_the_next_delivery_re_sends_it() = runTest {
        val client = FakePushTokenPublisher().apply { failFirst = true }
        val source = PushTokenSource("sandbox")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(source) }

        source.deliver("TOKEN1") // refused
        assertNull(record.value, "a refused registration records nothing")

        source.deliver("TOKEN1") // the next app entry's answer: the same token, re-sent
        job.cancel()

        assertEquals(2, client.calls.size)
        assertTrue(record.value != null, "the accepted one is recorded")
    }

    @Test
    fun an_unreadable_identity_publishes_nothing_on_a_delivery() = runTest {
        val client = FakePushTokenPublisher()
        identity.locked = true
        val source = PushTokenSource("sandbox")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(source) }

        source.deliver("TOKEN1")
        assertTrue(client.calls.isEmpty(), "the publisher could not address the device either")

        identity.locked = false
        source.deliver("TOKEN1") // the next entry's answer
        job.cancel()

        assertEquals(1, client.calls.size)
    }

    @Test
    fun a_refused_registration_is_retried_when_a_new_credential_arrives() = runTest {
        // The regression this exists to prevent. `PUT /devices/<id>` is gated, and on a fresh install the
        // APNs token can arrive before the device has attested — so the registration takes a 401. The next
        // app entry would re-send it, but a device that receives no silent pushes gets few entries; a new
        // credential is what makes the refused PUT acceptable, so it re-sends at once.
        val client = FakePushTokenPublisher().apply { failFirst = true }
        val source = PushTokenSource("sandbox")
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val registration = registration(client)

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
    fun a_periodic_renewal_re_publishes_an_unchanged_registration() = runTest {
        // A fresh credential publishes unconditionally — a renewal cannot be told from a mint, and must not be.
        val client = FakePushTokenPublisher()
        val source = PushTokenSource("sandbox")
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registration(client).run(source, credential)
        }

        source.deliver("DEADBEEF")
        assertEquals(1, client.calls.size)

        credential.emit(Unit) // a renewal: token, env and identity all unchanged
        assertEquals(2, client.calls.size)
    }

    @Test
    fun a_credential_change_with_no_apns_token_yet_registers_nothing() = runTest {
        val client = FakePushTokenPublisher()
        val credential = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registration(client).run(PushTokenSource("sandbox"), credential)
        }

        credential.emit(Unit) // attested, but the OS has delivered no APNs token yet

        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun a_delivery_before_the_collector_starts_is_still_seen() = runTest {
        // The OS may answer before the registration is installed; the last answer is replayed to it.
        val client = FakePushTokenPublisher()
        val source = PushTokenSource("sandbox")
        source.deliver("EARLY")

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { registration(client).run(source) }
        job.cancel()

        assertEquals(listOf(ApnsPushToken("EARLY", "sandbox")), client.calls)
    }
}
