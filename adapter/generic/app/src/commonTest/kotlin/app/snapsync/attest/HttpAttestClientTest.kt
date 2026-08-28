package app.snapsync.attest

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Ktor adapter behind the `AttestClient` port (capability `device-attestation`; the policy that
 * calls it — `DeviceAttestation` — is tested in `:adapter:generic:fake` over a fake port). Two things
 * are this adapter's own contract and are pinned here.
 *
 * **The three routes and their wire bodies.** These are the only routes reachable WITHOUT a token —
 * they are what issues it — so nothing downstream can catch a wrong path or a renamed field; the
 * device simply never attests. The `attestation`/`assertion` byte arrays go out **standard**-alphabet
 * Base64 with padding, because the backend decodes them with `b64ToBytes` (`api/src/app.ts`); the
 * literals below carry `+`, `/` and `=`, so a switch to `Base64.UrlSafe` fails here rather than at a
 * 401 nobody reads.
 *
 * **Every failure is `null`, never a throw.** This runs on background wakes, where an escaping error
 * would take down work that has nothing to do with attestation. A null leaves the old token in place
 * and the next wake retries — so refusal, transport failure and a malformed body deliberately collapse
 * to one answer, and each of those causes is exercised separately below.
 */
class HttpAttestClientTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val challenge = "1780000000.9f8e7d"

    /** Bytes chosen so their standard-alphabet encoding is `+/8=` — url-safe would read `-_8=`. */
    private val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte())

    private fun client(handler: MockEngine) =
        HttpAttestClient(HttpClient(handler), "https://edge.example/")

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    // ---- challenge -------------------------------------------------------------------------

    @Test
    fun `challenge GETs the challenge route and reads the nonce out of the body`() = runTest {
        var requested: String? = null
        var method: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            json("""{"challenge":"$challenge"}""", HttpStatusCode.OK)
        }

        assertEquals(challenge, client(engine).challenge())
        assertEquals("https://edge.example/attest/challenge", requested)
        assertEquals("GET", method)
    }

    @Test
    fun `challenge maps a refusal a transport failure and a malformed body alike to null`() = runTest {
        // One answer for every cause, by design: all of them mean "no attestation this wake".
        assertNull(client(MockEngine { respondError(HttpStatusCode.Unauthorized) }).challenge())
        assertNull(client(MockEngine { throw RuntimeException("offline") }).challenge())
        assertNull(client(MockEngine { json("not json", HttpStatusCode.OK) }).challenge())
    }

    @Test
    fun `a 200 without a challenge field yields null rather than an invented nonce`() = runTest {
        val engine = MockEngine { json("""{"nonce":"$challenge"}""", HttpStatusCode.OK) }
        assertNull(client(engine).challenge())
    }

    // ---- mintToken -------------------------------------------------------------------------

    @Test
    fun `mintToken POSTs the attestation to the token route and reads the minted token`() = runTest {
        var requested: String? = null
        var method: String? = null
        var body: String? = null
        var contentType: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            body = (request.body as TextContent).text
            contentType = (request.body as TextContent).contentType.toString()
            // 201, as the route answers (`api/src/app.ts`) — not merely 200.
            json("""{"token":"minted.token.value"}""", HttpStatusCode.Created)
        }

        val token = client(engine).mintToken(deviceId, "keyid-b64", bytes, challenge)

        assertEquals("minted.token.value", token)
        assertEquals("https://edge.example/attest/token", requested)
        assertEquals("POST", method)
        assertTrue(contentType!!.startsWith("application/json"), "content type was $contentType")
        // Field names and order are the wire contract; `+/8=` pins the standard alphabet and padding.
        assertEquals(
            """{"deviceId":"$deviceId","keyId":"keyid-b64","attestation":"+/8=","challenge":"$challenge"}""",
            body,
        )
    }

    @Test
    fun `mintToken maps a refusal a transport failure and a malformed body alike to null`() = runTest {
        // A stale challenge is a 401 here; the device keeps its old token and the next wake retries.
        val refused = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        assertNull(client(refused).mintToken(deviceId, "k", bytes, challenge))
        val offline = MockEngine { throw RuntimeException("offline") }
        assertNull(client(offline).mintToken(deviceId, "k", bytes, challenge))
        val garbled = MockEngine { json("not json", HttpStatusCode.Created) }
        assertNull(client(garbled).mintToken(deviceId, "k", bytes, challenge))
    }

    @Test
    fun `a 201 without a token field yields null rather than an empty credential`() = runTest {
        val engine = MockEngine { json("""{"ok":true}""", HttpStatusCode.Created) }
        assertNull(client(engine).mintToken(deviceId, "k", bytes, challenge))
    }

    // ---- renewToken ------------------------------------------------------------------------

    @Test
    fun `renewToken POSTs the assertion to the renew route and carries no keyId`() = runTest {
        var requested: String? = null
        var method: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            method = request.method.value
            body = (request.body as TextContent).text
            json("""{"token":"renewed.token.value"}""", HttpStatusCode.Created)
        }

        val token = client(engine).renewToken(deviceId, bytes, challenge)

        assertEquals("renewed.token.value", token)
        assertEquals("https://edge.example/attest/renew", requested)
        assertEquals("POST", method)
        // Renewal is an ASSERTION against the key the backend already stored, so no keyId rides along.
        assertEquals(
            """{"deviceId":"$deviceId","assertion":"+/8=","challenge":"$challenge"}""",
            body,
        )
    }

    @Test
    fun `renewToken maps a refusal a transport failure and a malformed body alike to null`() = runTest {
        val refused = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        assertNull(client(refused).renewToken(deviceId, bytes, challenge))
        val offline = MockEngine { throw RuntimeException("offline") }
        assertNull(client(offline).renewToken(deviceId, bytes, challenge))
        val garbled = MockEngine { json("not json", HttpStatusCode.Created) }
        assertNull(client(garbled).renewToken(deviceId, bytes, challenge))
    }

    // ---- host ------------------------------------------------------------------------------

    @Test
    fun `a host without a trailing slash addresses the same routes`() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            json("""{"challenge":"$challenge"}""", HttpStatusCode.OK)
        }
        HttpAttestClient(HttpClient(engine), "https://edge.example").challenge()
        assertEquals("https://edge.example/attest/challenge", requested)
    }
}
