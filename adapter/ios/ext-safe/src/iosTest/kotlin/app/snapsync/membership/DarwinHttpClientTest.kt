package app.snapsync.membership

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one HTTP client every device request goes through (capabilities `device-attestation`,
 * `diagnostic-logging`).
 *
 * Attaching the credential *here* rather than at each call site is the whole design: create, event
 * fetch, join/manifest, union, device config, leave, notify and the extension's reconcile listing all
 * flow through this factory, so none of them can be forgotten and a future caller inherits the header
 * for free. Two properties of that arrangement are asserted below, both of which fail silently.
 *
 * **The token is read per request, never captured once.** The app renews in the background, so a
 * client holding a copy taken at construction would keep presenting a dead credential for the rest of
 * the process's life — and the symptom is a stream of `401`s that no wake ever heals, which reads as
 * a backend problem.
 *
 * **A transport failure propagates.** `DownloadController.reconcile` keeps last-good state on a
 * failure by contract; a swallowed error would instead present an empty union as the truth.
 *
 * No server is involved: the requests are aimed at a closed local port, which refuses immediately.
 * That is enough, because the interceptor attaches the header *before* it executes, so the read count
 * is observable regardless of what the socket does.
 */
class DarwinHttpClientTest {

    private val refusedUrl = "http://127.0.0.1:1/api/v1/events"

    @Test
    fun `the token is read once per request rather than captured at construction`() {
        var reads = 0
        val client = darwinHttpClient(token = { reads++; "token-$reads" })

        assertEquals(0, reads, "constructing the client must not read the token")

        runBlocking {
            repeat(2) { runCatching { client.get(refusedUrl) } }
        }

        assertEquals(
            2,
            reads,
            "a token captured once outlives its renewal, and every later request goes out with a dead " +
                "credential that no background wake can heal",
        )
    }

    /**
     * A null token still sends the request, deliberately: it will `401`, and a `401` is retryable —
     * whereas refusing to send strands the work with nothing to retry. (The three `/attest/…` routes
     * are ungated, so the bootstrap request that has no token yet is served regardless.)
     */
    @Test
    fun `a request with no token is still attempted`() {
        var reads = 0
        val client = darwinHttpClient(token = { reads++; null })

        runBlocking { runCatching { client.get(refusedUrl) } }

        assertEquals(1, reads, "the absence of a token must not short-circuit the request")
    }

    @Test
    fun `a transport failure propagates rather than being swallowed`() {
        val client = darwinHttpClient()

        assertFailsWith<Throwable> { runBlocking { client.get(refusedUrl) } }
    }

    /**
     * `onRejected` means "the backend rejected this token", which is not the same as "the request did
     * not arrive". Firing it on a transport failure would drop a perfectly good credential every time
     * the device was briefly offline — and re-attestation is throttled by Apple.
     */
    @Test
    fun `a transport failure is not mistaken for a rejected credential`() {
        var rejected = 0
        val client = darwinHttpClient(token = { "token" }, onRejected = { rejected++ })

        runBlocking { runCatching { client.get(refusedUrl) } }

        assertTrue(rejected == 0, "only a 401 means the backend rejected the token")
    }

    /**
     * **A 401 IS what fires `onRejected`** — the entry point of the whole credential-recovery loop.
     *
     * Everything downstream is covered elsewhere: `DeviceAttestationTest` proves the feature drops the
     * token and attests afresh, `:test:integration` proves a new credential re-registers the push token,
     * and `:test:architecture`'s `CredentialRejectionWiringTest` pins the shell handing this hook over.
     * This is the hop that starts it, and until now only its NEGATIVE case was asserted — and that one
     * (`rejected == 0` after a transport failure) would pass just as happily if the hook were never wired
     * at all, so nothing established the positive direction.
     *
     * Driven over a mock engine rather than the Darwin one: producing a response requires a server, and
     * this file deliberately stands up none. The interceptor under test is the SAME function
     * `darwinHttpClient` installs — `withCredentialInterceptor` — not a copy of its logic.
     */
    @Test
    fun `a 401 is what fires onRejected`() {
        var rejected = 0
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
            .withCredentialInterceptor(token = { "token" }, onRejected = { rejected++ })

        runBlocking { client.get("https://example.invalid/api/v1/events") }

        assertEquals(1, rejected, "a 401 must reach the trust feature, or no wake can heal the credential")
    }

    /**
     * The other side of the same line: a SUCCESSFUL response must not drop a working credential. Paired
     * with the test above so the branch is pinned in both directions — an interceptor that fired on every
     * response would re-attest constantly, on Apple's throttled path.
     */
    @Test
    fun `a successful response leaves the credential alone`() {
        var rejected = 0
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
            .withCredentialInterceptor(token = { "token" }, onRejected = { rejected++ })

        runBlocking { client.get("https://example.invalid/api/v1/events") }

        assertEquals(0, rejected, "only a 401 means the backend rejected the token")
    }
}
