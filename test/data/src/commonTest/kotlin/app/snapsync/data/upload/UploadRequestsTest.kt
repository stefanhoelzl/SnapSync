package app.snapsync.data.upload

import app.snapsync.data.FakeClient
import app.snapsync.data.Held
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.token
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import app.snapsync.wire.api.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * An upload's request as the backend's byte route takes it: one event's destination naming this device, the asset and
 * the role, the photo's name in the query, the MIME, the build's version and the token held — and no request where
 * nothing can be addressed to this device.
 */
class UploadRequestsTest {

    private val event = testEventId("E1")
    private val device = testDeviceId("D1")
    private val key = UploadKey.of(AssetId.parse("A1")!!, ResourceRole.LIVE, "IMG_0001.MOV")
    private val detail = RowDetail(CaptureDate.Undated, "video/quicktime", "IMG_0001.MOV")

    private class Identity(var read: DeviceIdRead) : DeviceIdentity {
        override fun id(): DeviceIdRead = read
    }

    private val held = Held(token = token(30))
    private val identity = Identity(DeviceIdRead.Ok(device, DeviceIdRead.Via.READ))
    private val requests = UploadRequests(FakeClient().exchange(), held.store, identity)

    private fun built(answer: RequestBuilt): BuiltRequest = assertIs<RequestBuilt.Built>(answer).request

    @Test
    fun `a first upload is addressed to the event, this device, the asset and its role, carrying the token`() {
        val request = built(requests.first(event, key, detail))

        val path = "/events/${event.value}/files/devices/${device.value}/A1/live"
        assertEquals("${FakeClient.BASE}$path?filename=IMG_0001.MOV", request.url)
        assertEquals("/api/v2$path", request.destination.path)
        assertEquals("video/quicktime", request.headers["Content-Type"])
        assertEquals("Bearer ${token(30)}", request.headers["Authorization"])
        assertEquals("1.0", request.headers[Limits.VERSION_HEADER])
    }

    @Test
    fun `a row that records no file name is named by its key, whose extension is the same`() {
        val request = built(requests.first(event, key, RowDetail(CaptureDate.Undated, "video/quicktime", "")))

        assertEquals(true, request.url.endsWith("?filename=A1-live.mov"))
    }

    @Test
    fun `a device that holds no token still gets its request, without one`() {
        val none = UploadRequests(FakeClient().exchange(), Held().store, identity)

        assertNull(built(none.first(event, key, detail)).headers["Authorization"])
    }

    @Test
    fun `a retry carries the token the other process renewed since`() {
        requests.first(event, key, detail)
        held.record.setToken(app.snapsync.data.bearer(token(29)))

        assertEquals("Bearer ${token(30)}", built(requests.first(event, key, detail)).headers["Authorization"])
        assertEquals("Bearer ${token(29)}", built(requests.retry(event, key, detail)).headers["Authorization"])
    }

    @Test
    fun `nothing is built for a device whose id this process cannot read or may not mint`() {
        identity.read = DeviceIdRead.Absent
        assertEquals(RequestBuilt.NoIdentity, requests.first(event, key, detail))

        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(RequestBuilt.IdentityUnreadable("locked"), requests.retry(event, key, detail))
    }
}
