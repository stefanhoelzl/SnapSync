package app.snapsync.application.push

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.FixedAppIdentity
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.push.PushEndpointPublisher
import app.snapsync.data.push.PushRegistrationRecord
import app.snapsync.data.push.PushTokens
import app.snapsync.domain.push.PushTrigger
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.Preferences
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.PushToken
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The push registration (decision record `changes/own-work-per-wake`, D12): an OS delivery publishes only a changed
 * registration, a new credential and a join publish whatever the record holds, and only what the backend accepted is
 * recorded — so a failed publish is re-sent at the next trigger.
 */
@OptIn(EntryPointCall::class)
class RegisterPushTest {

    private object Service : PushNotifications {
        override val kind: String = "apns"

        @CompositionOnly
        override fun inbound(handlers: PushHandlers) = Unit

        override fun register() = Unit
    }

    private val transport = ScriptedTransport { ok(201) }
    private val values = mutableMapOf<String, String>()
    private val identity = FixedAppIdentity(DeviceIdRead.Ok(testDeviceId(DEVICE), DeviceIdRead.Via.READ))
    private val tokens = PushTokens(Service, BuildInfoMock().port(), identity)

    private fun register(preferences: Preferences = inMemoryPreferences(values)) = RegisterPush(
        tokens,
        PushRegistrationRecord(preferences, inMemoryFiles()),
        PushEndpointPublisher(BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))),
    )

    private val puts get() = transport.calls.count { it.startsWith("PUT /devices/") }

    @Test
    fun `nothing is registered before the platform delivers a token`() = runTest {
        for (trigger in PushTrigger.entries) assertEquals(PushRegistrationOutcome.NoToken, register()(trigger))
        assertEquals(0, puts)
    }

    @Test
    fun `a delivered token is published once, and an unchanged re-delivery not again`() = runTest {
        tokens.store(PushToken("A"))
        assertEquals(PushRegistrationOutcome.Registered, register()(PushTrigger.DELIVERED))
        assertEquals(PushRegistrationOutcome.Unchanged, register()(PushTrigger.DELIVERED))
        assertEquals(1, puts)

        // A rotation is a delivery that differs.
        tokens.store(PushToken("B"))
        assertEquals(PushRegistrationOutcome.Registered, register()(PushTrigger.DELIVERED))
        assertEquals(2, puts)
    }

    @Test
    fun `a new credential and a join publish whatever the record holds`() = runTest {
        tokens.store(PushToken("A"))
        register()(PushTrigger.DELIVERED)
        assertEquals(PushRegistrationOutcome.Registered, register()(PushTrigger.CREDENTIAL))
        assertEquals(PushRegistrationOutcome.Registered, register()(PushTrigger.JOINED))
        assertEquals(3, puts)
    }

    @Test
    fun `a refused publish is not recorded, so the next delivery sends it again`() = runTest {
        tokens.store(PushToken("A"))
        transport.answer = { text(502, "down") }
        assertEquals(PushRegistrationOutcome.NotRegistered, register()(PushTrigger.DELIVERED))
        transport.answer = { ok(201) }
        assertEquals(PushRegistrationOutcome.Registered, register()(PushTrigger.DELIVERED))
        assertEquals(2, puts)
    }

    @Test
    fun `an accepted registration the record cannot keep is registered, and published again next time`() = runTest {
        tokens.store(PushToken("A"))
        val refusing = object : Preferences by inMemoryPreferences(values) {
            override fun set(key: String, value: String): WriteOutcome = WriteOutcome.Failed("locked")
        }
        assertEquals(PushRegistrationOutcome.Registered, register(refusing)(PushTrigger.DELIVERED))
        assertEquals(PushRegistrationOutcome.Registered, register()(PushTrigger.DELIVERED))
        assertEquals(2, puts)
    }

    @Test
    fun `an unreadable device id defers the registration`() = runTest {
        tokens.store(PushToken("A"))
        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(PushRegistrationOutcome.Deferred, register()(PushTrigger.DELIVERED))
        assertEquals(0, puts)
    }

    private companion object {
        const val DEVICE = "11111111-0000-4000-8000-000000000002"
    }
}
