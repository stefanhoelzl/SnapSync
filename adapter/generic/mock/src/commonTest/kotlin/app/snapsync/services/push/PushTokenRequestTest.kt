package app.snapsync.services.push

import app.snapsync.mock.PushServiceMock
import kotlin.test.Test
import kotlin.test.assertEquals

/** Asking the push service for this device's token: the request the foreground makes at every return. */
class PushTokenRequestTest {

    @Test
    fun `each request asks the push service once`() {
        val service = PushServiceMock()
        val request = PushTokenRequest(service.port())
        request.request()
        request.request()
        assertEquals(2, service.registrations)
    }
}
