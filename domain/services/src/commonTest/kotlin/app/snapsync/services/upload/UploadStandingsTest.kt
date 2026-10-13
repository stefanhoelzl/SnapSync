package app.snapsync.services.upload

import app.snapsync.model.DeviceIdentityRole
import app.snapsync.ports.HttpResult
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.backend.ScriptedCredential
import app.snapsync.services.backend.ScriptedTransport
import app.snapsync.services.backend.exchangeOver
import app.snapsync.services.backend.ok
import app.snapsync.services.backend.text
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.identity.identityOf
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a failed upload stands, as the backend's upload-state answer means it to the retry (`docs/architecture.md`,
 * "Resumable uploads"): only an unreachable backend withholds the transfer; every other answer sends something.
 */
class UploadStandingsTest {

    private val sha = "ab".repeat(32)
    private val transport = ScriptedTransport()

    private fun standings(
        joined: String? = "E",
        identity: PersistedDeviceIdentity = identityOf("D"),
    ) = UploadStandings(BackendGateway(exchangeOver(transport), ScriptedCredential("T")), {
        joined?.let(::testEventId)
    }, identity)

    private suspend fun answered(reply: HttpResult): ResumePoint {
        transport.answer = { reply }
        return standings().of(libraryResource(KEY), sha)
    }

    @Test
    fun `the backend is asked about the resource and its bytes`() = runTest {
        answered(ok(204))
        val asked = transport.requests.single()
        assertEquals("GET", asked.method)
        assertEquals(
            "/events/${testEventId("E")}/files/devices/${testDeviceId("D")}/A/primary/upload",
            asked.url.substringAfter("/api/v2"),
        )
        assertEquals(sha, asked.headers["x-snapsync-sha256"])
    }

    @Test
    fun `each answer is where the retry sends from`() = runTest {
        assertEquals(ResumePoint.Landed, answered(ok(204)))
        assertEquals(
            ResumePoint.From(6_000),
            answered(HttpResult.Response(200, mapOf("Upload-Offset" to "6000"), "")),
        )
        assertEquals(
            ResumePoint.From(0),
            answered(HttpResult.Response(200, mapOf("Upload-Offset" to "x"), "")),
            "an offset that is no number sends the file whole",
        )
        assertEquals(ResumePoint.From(0), answered(text(404, "nothing stored")))
        assertEquals(ResumePoint.From(0), answered(text(403, "not a member")), "a refusal sends the file whole")
        assertEquals(ResumePoint.Deferred, answered(HttpResult.Unreachable("offline")))
    }

    @Test
    fun `a device that is not joined or has no identity sends the file whole`() = runTest {
        assertEquals(ResumePoint.From(0), standings(joined = null).of(libraryResource(KEY), sha))
        val absent = PersistedDeviceIdentity(DeviceIdentityRole.READ_ONLY, MapSecureStore(), PlatformDeviceId { null })
        assertEquals(ResumePoint.From(0), standings(identity = absent).of(libraryResource(KEY), sha))
        assertEquals(emptyList(), transport.requests)
    }

    @Test
    fun `a device id that cannot be read now defers the transfer`() = runTest {
        val locked = identityOf("D", MapSecureStore(unavailable = true))
        assertEquals(ResumePoint.Deferred, standings(identity = locked).of(libraryResource(KEY), sha))
    }
}
