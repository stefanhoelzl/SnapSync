package app.snapsync.data.upload

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.FakeClient
import app.snapsync.data.Held
import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.crypto.FileNameCipher
import app.snapsync.data.crypto.UploadSeal
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.token
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.TokenText
import app.snapsync.data.walkClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.time.Timestamp
import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.upload.Discovery
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.inbound.Trigger
import app.snapsync.mock.fakeCrypto
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import app.snapsync.wire.api.Limits
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

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
    private val requests = UploadRequests(
        FakeClient().exchange(),
        held.store,
        Gated(held.store),
        identity,
        FileNameCipher(fakeCrypto()),
        walkClock,
    )

    private fun built(answer: RequestBuilt): BuiltRequest = assertIs<RequestBuilt.Built>(answer).request

    @Test
    fun `a first upload is addressed to the event, this device, the asset and its role, carrying the token`() = runTest {
        val request = built(requests.first(event, key, detail, UploadSeal.Plain))

        val path = "/events/${event.value}/files/devices/${device.value}/A1/live"
        assertEquals("${FakeClient.BASE}$path?filename=IMG_0001.MOV", request.url)
        assertEquals("/api/v2$path", request.destination.path)
        assertEquals("video/quicktime", request.headers["Content-Type"])
        assertEquals("Bearer ${token(30)}", request.headers["Authorization"])
        assertEquals("1.0", request.headers[Limits.VERSION_HEADER])
    }

    @Test
    fun `an upload carries when the photo was taken, found and queued, and the entry points that did so`() = runTest {
        val taken = Timestamp.of(Instant.parse("2026-07-14T09:00:00Z"))
        val found = Discovery(Timestamp.of(Instant.parse("2026-07-14T11:58:00Z")), "OnForeground")
        val dated = RowDetail(CaptureDate.Dated(taken), "image/heic", "IMG_0001.HEIC", found)

        val request = withContext(Trigger.of("OnUploadSessionEvents")) {
            built(requests.first(event, key, dated, UploadSeal.Plain))
        }

        assertEquals("2026-07-14T09:00:00Z", request.headers["x-snapsync-taken"])
        assertEquals("2026-07-14T11:58:00Z", request.headers["x-snapsync-discovered"])
        assertEquals("OnForeground", request.headers["x-snapsync-discovered-by"])
        assertEquals("2026-07-14T12:00:00Z", request.headers["x-snapsync-queued"])
        assertEquals("OnUploadSessionEvents", request.headers["x-snapsync-queued-by"])
    }

    @Test
    fun `a row recorded before discoveries were kept, undated and off an entry point, carries only its queueing`() =
        runTest {
            val request = built(
                requests.first(
                    event,
                    key,
                    RowDetail(
                        CaptureDate.Undated,
                        "image/heic",
                        "",
                        Discovery(
                            Timestamp.of(Instant.parse("2026-07-14T11:58:00Z")),
                            null,
                        ),
                    ),
                    UploadSeal.Plain,
                ),
            )
            val bare = built(requests.first(event, key, detail, UploadSeal.Plain))

            assertEquals(null, request.headers["x-snapsync-taken"])
            assertEquals(null, request.headers["x-snapsync-discovered-by"])
            assertEquals("2026-07-14T11:58:00Z", request.headers["x-snapsync-discovered"])
            assertEquals(null, bare.headers["x-snapsync-discovered"])
            assertEquals(null, bare.headers["x-snapsync-queued-by"])
            assertEquals("2026-07-14T12:00:00Z", bare.headers["x-snapsync-queued"])
        }

    @Test
    fun `a row that records no file name is named by its key, whose extension is the same`() = runTest {
        val request =
            built(requests.first(event, key, RowDetail(CaptureDate.Undated, "video/quicktime", ""), UploadSeal.Plain))

        assertEquals(true, request.url.endsWith("?filename=A1-live.mov"))
    }

    @Test
    fun `a phone that holds no proof obtains one first, and where it cannot nothing is built`() = runTest {
        val empty = Held().store
        val attesting = Gated(empty, obtains = token(31))
        val app =
            UploadRequests(FakeClient().exchange(), empty, attesting, identity, FileNameCipher(fakeCrypto()), walkClock)
        assertEquals(
            "Bearer ${token(31)}",
            built(app.first(event, key, detail, UploadSeal.Plain)).headers["Authorization"],
        )
        assertEquals(1, attesting.asked, "the app attests on the spot")

        val dropOnly = Gated(empty)
        val extension =
            UploadRequests(FakeClient().exchange(), empty, dropOnly, identity, FileNameCipher(fakeCrypto()), walkClock)
        assertIs<RequestBuilt.Unverified>(
            extension.first(event, key, detail, UploadSeal.Plain),
            "no request without a proof",
        )
        assertIs<RequestBuilt.Unverified>(extension.retry(event, key, detail, UploadSeal.Plain))
    }

    @Test
    fun `a proof that cannot be read is no proof - nothing is built, and nothing is obtained over it`() = runTest {
        val unreadable = object : Credential {
            var asked = 0
            override fun token(): TokenRead = TokenRead.Unavailable("keychain locked")
            override suspend fun rejected(sent: BearerToken): TokenRead = TokenRead.None
            override suspend fun missing(): TokenRead = TokenRead.None.also { asked++ }
        }
        val locked = UploadRequests(
            FakeClient().exchange(),
            held.store,
            unreadable,
            identity,
            FileNameCipher(fakeCrypto()),
            walkClock,
        )

        assertEquals(RequestBuilt.Unverified("keychain locked"), locked.first(event, key, detail, UploadSeal.Plain))
        assertEquals(0, unreadable.asked)
    }

    @Test
    fun `a retry carries the token the other process renewed since`() = runTest {
        requests.first(event, key, detail, UploadSeal.Plain)
        held.record.setToken(app.snapsync.data.bearer(token(29)))

        assertEquals(
            "Bearer ${token(30)}",
            built(requests.first(event, key, detail, UploadSeal.Plain)).headers["Authorization"],
        )
        assertEquals(
            "Bearer ${token(29)}",
            built(requests.retry(event, key, detail, UploadSeal.Plain)).headers["Authorization"],
        )
    }

    @Test
    fun `nothing is built for a device whose id this process cannot read or may not mint`() = runTest {
        identity.read = DeviceIdRead.Absent
        assertEquals(RequestBuilt.NoIdentity, requests.first(event, key, detail, UploadSeal.Plain))

        identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(RequestBuilt.IdentityUnreadable("locked"), requests.retry(event, key, detail, UploadSeal.Plain))
    }
}
