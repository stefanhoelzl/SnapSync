package app.snapsync.http

import app.snapsync.model.APP_VERSION_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The four cross-cutting rules every backend request carries, asserted against the SAME function the
 * device installs (`darwinHttpClient` applies this one) rather than a copy of it.
 *
 * They run over a `MockEngine` because producing a response is the whole point and no real engine can be
 * made to produce these without a server. They live HERE, in the technology-neutral adapter's
 * `commonTest`, rather than in the iOS-only test source set they came from: these are response-handling
 * rules with nothing iOS about them, and asserting them on Linux means they gate every PR instead of
 * waiting for a macOS runner.
 */
class CredentialInterceptorTest {

    private fun client(
        status: HttpStatusCode,
        body: String = "",
        token: () -> String? = { "token" },
        appVersion: () -> String = { "9.9" },
        onRejected: () -> Unit = {},
        onVersionRefused: (String?) -> Unit = {},
        onServed: () -> Unit = {},
        record: ((Map<String, List<String>>) -> Unit)? = null,
    ) = HttpClient(
        MockEngine { request ->
            record?.invoke(request.headers.entries().associate { it.key to it.value })
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ).withCredentialInterceptor(token, onRejected, appVersion, onVersionRefused, onServed)

    // ── the credential ─────────────────────────────────────────────────────────────────────────────

    /**
     * **A 401 IS what fires `onRejected`** — the entry point of the whole credential-recovery loop.
     *
     * Everything downstream is covered elsewhere: `DeviceAttestationTest` proves the feature drops the
     * token and attests afresh, `:test:integration` proves a new credential re-registers the push token,
     * and `:test:architecture`'s `CredentialRejectionWiringTest` pins the shell handing this hook over.
     * This is the hop that starts it, and only its NEGATIVE case used to be asserted — which would pass
     * just as happily if the hook were never wired at all.
     */
    @Test
    fun a_401_is_what_fires_onRejected() = runTest {
        var rejected = 0
        client(HttpStatusCode.Unauthorized, onRejected = { rejected++ })
            .get("https://example.invalid/api/v2/events")
        assertEquals(1, rejected, "a 401 must reach the trust feature, or no wake can heal the credential")
    }

    /**
     * The other side of the same line: a SUCCESSFUL response must not drop a working credential. Paired
     * with the test above so the branch is pinned in both directions — an interceptor that fired on every
     * response would re-attest constantly, on Apple's throttled path.
     */
    @Test
    fun a_successful_response_leaves_the_credential_alone() = runTest {
        var rejected = 0
        client(HttpStatusCode.OK, onRejected = { rejected++ })
            .get("https://example.invalid/api/v2/events")
        assertEquals(0, rejected, "only a 401 means the backend rejected the token")
    }

    // ── the declared version (capability `min-app-version`) ────────────────────────────────────────

    @Test
    fun every_request_declares_this_builds_version() = runTest {
        var seen: Map<String, List<String>> = emptyMap()
        client(HttpStatusCode.OK, record = { seen = it })
            .get("https://example.invalid/api/v2/events")
        assertEquals(listOf("9.9"), seen[APP_VERSION_HEADER])
    }

    @Test
    fun a_426_reports_the_minimum_the_backend_named() = runTest {
        val refusals = mutableListOf<String?>()
        val client = client(
            HttpStatusCode.UpgradeRequired,
            body = """{"error":"app too old","minAppVersion":"0.4"}""",
            onVersionRefused = { refusals += it },
        )
        client.get("https://example.invalid/api/v2/events")
        assertEquals<List<String?>>(listOf("0.4"), refusals)
    }

    @Test
    fun a_426_carrying_no_minimum_still_reports_the_refusal() = runTest {
        // The refusal is the STATUS; the version is a courtesy. Collapsing "no version" into "not
        // refused" would leave the build silently unable to do anything, with no screen explaining it.
        val refusals = mutableListOf<String?>()
        client(HttpStatusCode.UpgradeRequired, body = "not json at all", onVersionRefused = { refusals += it })
            .get("https://example.invalid/api/v2/events")
        assertEquals(1, refusals.size, "the refusal must be reported whatever the body said")
        assertNull(refusals.single(), "and it must not invent a version nobody sent")
    }

    /**
     * The caller can still read a 426's body.
     *
     * The interceptor reads it to find the minimum, and a naive read would CONSUME the channel — leaving
     * whichever seam issued the request holding an empty body on the one status whose payload is the
     * whole point. `save()` is what makes both reads possible, and this is the assertion that keeps it.
     */
    @Test
    fun reading_the_refusal_does_not_consume_it() = runTest {
        val body = """{"error":"app too old","minAppVersion":"0.4"}"""
        val response = client(HttpStatusCode.UpgradeRequired, body = body)
            .get("https://example.invalid/api/v2/events")
        assertEquals(body, response.bodyAsText())
    }

    @Test
    fun a_served_response_clears_a_refusal_and_a_failure_does_not() = runTest {
        // What heals the update screen. `onServed` fires on success ONLY: a 500 or a 401 says nothing
        // about whether this build is too old, and clearing on one would flicker the screen away and
        // back on the next call.
        val served = mutableListOf<HttpStatusCode>()
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.InternalServerError, HttpStatusCode.Unauthorized)) {
            client(status, onServed = { served += status }).get("https://example.invalid/api/v2/events")
        }
        assertEquals(listOf(HttpStatusCode.OK), served)
    }
}
