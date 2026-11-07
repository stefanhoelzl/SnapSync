package app.snapsync.data.push

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.push.PushRegistrationKey
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.BuildInfoMock
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.PushToken
import app.snapsync.testids.testDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The token the platform delivered, and the registration it makes for the device the identity names. */
class PushTokensTest {

    private val identity = FixedIdentity(DeviceIdRead.Ok(testDeviceId(DEVICE), DeviceIdRead.Via.READ))
    private class Service : PushNotifications {
        var asked = 0
        override val kind: String = "apns"

        @CompositionOnly
        override fun inbound(handlers: PushHandlers) = Unit

        override fun register() {
            asked++
        }
    }

    private val service = Service()
    private val tokens = PushTokens(service, BuildInfoMock(pushEnvironment = "sandbox").port(), identity)

    @Test
    fun `asking for the token asks the push service each time`() {
        tokens.request()
        tokens.request()
        assertEquals(2, service.asked)
    }

    @Test
    fun `before the platform delivers a token there is no registration`() {
        assertEquals(RegistrationRead.NoToken, tokens.current())
    }

    @Test
    fun `the latest token registers under the build's kind, environment and the device`() {
        tokens.store(PushToken("T1"))
        tokens.store(PushToken("T2"))
        val held = tokens.current() as RegistrationRead.Held
        assertEquals(PushEndpoint("apns", "T2", "sandbox"), held.endpoint)
        // The stored form every device's record was written in: kind, environment, device, token.
        assertEquals(PushRegistrationKey("apns\nsandbox\n${testDeviceId(DEVICE).value}\nT2"), held.key)
    }

    @Test
    fun `a device id that cannot be read now makes no registration`() {
        tokens.store(PushToken("T1"))
        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(RegistrationRead.NoIdentity, tokens.current())
        identity.read = DeviceIdRead.Absent
        assertEquals(RegistrationRead.NoIdentity, tokens.current())
    }

    @Test
    fun `the same token under another push service is another registration`() {
        val endpoint = PushEndpoint("apns", "T", "e")
        val device = testDeviceId(DEVICE)
        assertEquals(endpoint.keyFor(device), PushEndpoint("apns", "T", "e").keyFor(device))
        assertEquals(false, endpoint.keyFor(device) == PushEndpoint("fcm", "T", "e").keyFor(device))
    }

    private companion object {
        const val DEVICE = "11111111-0000-4000-8000-000000000002"
    }
}
