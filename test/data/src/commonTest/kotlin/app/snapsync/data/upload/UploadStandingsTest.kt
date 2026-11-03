package app.snapsync.data.upload

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.UNAUTHORIZED
import app.snapsync.data.backend.appTooOld
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.UploadKey
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a failed upload stands, by the backend's word: the offset its bytes reached, that they all landed, or nothing —
 * and a question that cannot be asked defers, where an answer that is not about these bytes sends the file whole.
 */
class UploadStandingsTest {

    private val event = testEventId("E1")
    private val device = testDeviceId("D1")
    private val key = UploadKey.of(AssetId.parse("A1")!!, ResourceRole.PRIMARY, "IMG.MOV")

    private class Identity(var read: DeviceIdRead) : DeviceIdentity {
        override fun id(): DeviceIdRead = read
    }

    private val identity = Identity(DeviceIdRead.Ok(device, DeviceIdRead.Via.READ))
    private val transport = ScriptedTransport()
    private val standings = UploadStandings(BackendGateway(exchangeOver(transport), ScriptedCredential("T1")), identity)

    private suspend fun standing(answer: HttpResult): ResumePoint {
        transport.answer = { answer }
        return standings.of(event, key, "abc")
    }

    @Test
    fun `the backend is asked for these bytes of this device's resource in the event`() = runTest {
        standing(ok(204))

        assertEquals(
            listOf("GET /events/${event.value}/files/devices/${device.value}/A1/primary/upload T1"),
            transport.calls,
        )
        assertEquals("abc", transport.requests.single().headers["x-snapsync-sha256"])
    }

    @Test
    fun `stored bytes resume at their offset, landed ones are landed, and none start at zero`() = runTest {
        assertEquals(ResumePoint.From(1_048_576), standing(HttpResult.Response(200, mapOf("Upload-Offset" to "1048576"), "")))
        assertEquals(ResumePoint.Landed, standing(ok(204)))
        assertEquals(ResumePoint.From(0), standing(text(404, "")))
        assertEquals(ResumePoint.From(0), standing(HttpResult.Response(200, mapOf("Upload-Offset" to "not a number"), "")))
    }

    @Test
    fun `an answer that is not about these bytes sends the file whole`() = runTest {
        listOf(text(403, ""), UNAUTHORIZED, text(400, "bad"), text(502, "down"), text(418, "?"))
            .forEach { assertEquals(ResumePoint.From(0), standing(it), "$it") }
        assertEquals(ResumePoint.From(0), standing(HttpResult.Response(503, mapOf("Retry-After" to "60"), "")))
        assertEquals(ResumePoint.From(0), standing(appTooOld("9.9")))
    }

    @Test
    fun `a question that cannot be asked defers the upload`() = runTest {
        assertEquals(ResumePoint.Deferred, standing(HttpResult.Unreachable("offline")))

        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(ResumePoint.Deferred, standings.of(event, key, "abc"))
    }

    @Test
    fun `a process that holds no device id sends the file whole, asking nothing`() = runTest {
        identity.read = DeviceIdRead.Absent

        assertEquals(ResumePoint.From(0), standings.of(event, key, "abc"))
        assertEquals(emptyList(), transport.calls)
    }
}
