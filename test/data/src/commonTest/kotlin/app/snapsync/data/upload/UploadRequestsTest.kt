package app.snapsync.data.upload

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.FakeClient
import app.snapsync.data.Held
import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.token
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.TokenText
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import app.snapsync.wire.api.Limits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An upload's request as the backend's byte route takes it: one event's destination naming this device, the asset and
 * the role, the photo's name in the query, the MIME, the build's version and the device's proof — built only with a
 * proof (change `withhold-unverified-uploads`), and never where nothing can be addressed to this device.
 */
class UploadRequestsTest {

    private val event = testEventId("E1")
    private val device = testDeviceId("D1")
    private val key = UploadKey.of(AssetId.parse("A1")!!, ResourceRole.LIVE, "IMG_0001.MOV")
    private val detail = RowDetail(CaptureDate.Undated, "video/quicktime", "IMG_0001.MOV")

    /** This process's credential over [store]: holding none, it obtains [obtains] — `null` for one that cannot. */
    private class Gated(private val store: CachedCredentialStore, private val obtains: String? = null) : Credential {
        var asked = 0

        override fun token(): TokenRead = store.token()

        override suspend fun rejected(sent: BearerToken): TokenRead = TokenRead.None

        override suspend fun missing(): TokenRead {
            asked++
            return obtains?.let { TokenRead.Held(TokenText.parse(it)) } ?: TokenRead.None
        }
    }

    private val held = Held(token = token(30))
    private val identity = FixedIdentity(DeviceIdRead.Ok(device, DeviceIdRead.Via.READ))
    private val requests = UploadRequests(FakeClient().exchange(), held.store, Gated(held.store), identity)

    private fun built(answer: RequestBuilt): BuiltRequest = assertIs<RequestBuilt.Built>(answer).request

    @Test
    fun `a first upload is addressed to the event, this device, the asset and its role, carrying the token`() = runTest {
        val request = built(requests.first(event, key, detail))

        val path = "/events/${event.value}/files/devices/${device.value}/A1/live"
        assertEquals("${FakeClient.BASE}$path?filename=IMG_0001.MOV", request.url)
        assertEquals("/api/v2$path", request.destination.path)
        assertEquals("video/quicktime", request.headers["Content-Type"])
        assertEquals("Bearer ${token(30)}", request.headers["Authorization"])
        assertEquals("1.0", request.headers[Limits.VERSION_HEADER])
    }

    @Test
    fun `a row that records no file name is named by its key, whose extension is the same`() = runTest {
        val request = built(requests.first(event, key, RowDetail(CaptureDate.Undated, "video/quicktime", "")))

        assertEquals(true, request.url.endsWith("?filename=A1-live.mov"))
    }

    @Test
    fun `a phone that holds no proof obtains one first, and where it cannot nothing is built`() = runTest {
        val empty = Held().store
        val attesting = Gated(empty, obtains = token(31))
        val app = UploadRequests(FakeClient().exchange(), empty, attesting, identity)
        assertEquals("Bearer ${token(31)}", built(app.first(event, key, detail)).headers["Authorization"])
        assertEquals(1, attesting.asked, "the app attests on the spot")

        val dropOnly = Gated(empty)
        val extension = UploadRequests(FakeClient().exchange(), empty, dropOnly, identity)
        assertIs<RequestBuilt.Unverified>(extension.first(event, key, detail), "no request without a proof")
        assertIs<RequestBuilt.Unverified>(extension.retry(event, key, detail))
    }

    @Test
    fun `a proof that cannot be read is no proof - nothing is built, and nothing is obtained over it`() = runTest {
        val unreadable = object : Credential {
            var asked = 0
            override fun token(): TokenRead = TokenRead.Unavailable("keychain locked")
            override suspend fun rejected(sent: BearerToken): TokenRead = TokenRead.None
            override suspend fun missing(): TokenRead = TokenRead.None.also { asked++ }
        }
        val locked = UploadRequests(FakeClient().exchange(), held.store, unreadable, identity)

        assertEquals(RequestBuilt.Unverified("keychain locked"), locked.first(event, key, detail))
        assertEquals(0, unreadable.asked)
    }

    @Test
    fun `a retry carries the token the other process renewed since`() = runTest {
        requests.first(event, key, detail)
        held.record.setToken(app.snapsync.data.bearer(token(29)))

        assertEquals("Bearer ${token(30)}", built(requests.first(event, key, detail)).headers["Authorization"])
        assertEquals("Bearer ${token(29)}", built(requests.retry(event, key, detail)).headers["Authorization"])
    }

    @Test
    fun `nothing is built for a device whose id this process cannot read or may not mint`() = runTest {
        identity.read = DeviceIdRead.Absent
        assertEquals(RequestBuilt.NoIdentity, requests.first(event, key, detail))

        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(RequestBuilt.IdentityUnreadable("locked"), requests.retry(event, key, detail))
    }
}
