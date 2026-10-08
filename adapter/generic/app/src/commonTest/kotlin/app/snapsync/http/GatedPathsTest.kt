package app.snapsync.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The client's copy of the backend's token gate, route by route: which requests the gate guards ([isGatedRequest]) and
 * which verify a token sent with them ([verifiesToken]). `GatedPathPinTest` holds the list to `api/src/app.ts`; this
 * holds the predicate to the list, one row per way a request can be read.
 */
class GatedPathsTest {

    private class Row(val method: String, val path: String, val gated: Boolean, val verifies: Boolean)

    private val rows = listOf(
        // The device API: gated, so a token is verified.
        Row("PUT", "/api/v2/events/E/devices/D", gated = true, verifies = true),
        Row("GET", "/events/E/files/devices/D", gated = true, verifies = true),
        Row("POST", "/events/E", gated = true, verifies = true),
        Row("POST", "/join", gated = true, verifies = true),
        Row("GET", "/join/E/more", gated = true, verifies = true),
        // A preflight and the attestation issuers: never gated.
        Row("OPTIONS", "/events/E/devices/D", gated = false, verifies = false),
        Row("POST", "/api/v2/attest/challenge", gated = false, verifies = false),
        // The public reads, by GET or HEAD.
        Row("GET", "/api/v2", gated = false, verifies = false),
        Row("GET", "/api/v2/", gated = false, verifies = false),
        Row("HEAD", "/health", gated = false, verifies = false),
        Row("GET", "/_astro/page.js", gated = false, verifies = false),
        Row("GET", "/join/E", gated = false, verifies = false),
        Row("GET", "/web/events/E/photos", gated = false, verifies = false),
        Row("GET", "/events/E/files/devices/D/A/primary", gated = false, verifies = false),
        // The two event reads: open to eventId possession, but a token sent with them is checked.
        Row("GET", "/api/v1/events/E?with=query", gated = false, verifies = true),
        Row("HEAD", "/events/E/files", gated = false, verifies = true),
    )

    @Test
    fun each_request_is_gated_and_verified_exactly_as_the_backend_reads_it() {
        rows.forEach {
            assertEquals(it.gated, isGatedRequest(it.method, it.path), "${it.method} ${it.path} gated")
            assertEquals(it.verifies, verifiesToken(it.method, it.path), "${it.method} ${it.path} verifies")
        }
    }
}
