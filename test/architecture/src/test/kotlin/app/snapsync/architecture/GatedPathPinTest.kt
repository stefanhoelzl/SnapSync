package app.snapsync.architecture

import app.snapsync.http.isGatedRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The client's ungated-path predicate is the backend's closed list** (capability `device-attestation`, "Only a
 * rejected credential is invalidated, and only that one"; decision record `harden-seam-bug-classes`, D10).
 *
 * The credential interceptor reads a `401` as a rejected token only from a route the token gate guards
 * ([isGatedRequest]). That predicate is a copy of the gate's closed list in `api/src/app.ts`, and a copy drifts: a
 * route the backend opens but the client still thinks gated would have its own `401`s read as a revoked credential
 * again (B2). So this reads the backend's list out of `app.ts` and drives the REAL predicate against every entry —
 * and pins the list's size, so an entry added there fails here until the client learns it.
 *
 * **Text, and it says so:** it reads the literals of the gate's `publicGet` / `publicRead` / `if (…)` block. A gate
 * rewritten into a different shape fails the shape assertions below rather than passing on nothing.
 */
class GatedPathPinTest {

    private val app = File(SourceScan.repoRoot, "api/src/app.ts")

    /** The gate's source, from the `publicGet` declaration to the `return await next()` that admits ungated calls. */
    private fun gate(): String {
        val text = app.readText()
        val start = text.indexOf("const publicGet =")
        val end = text.indexOf("return await next();", start)
        assertTrue(start >= 0 && end > start, "the gate's ungated list moved in api/src/app.ts — re-point this pin")
        return text.substring(start, end)
    }

    @Test
    fun `every path the backend serves ungated is ungated to the client`() {
        val gate = gate()
        val exact = Regex("""path === "([^"]+)"""").findAll(gate).map { it.groupValues[1] }.toList()
        val prefixes = Regex("""path\.startsWith\("([^"]+)"\)""").findAll(gate).map { it.groupValues[1] }.toList()
        // Pinned sizes: an entry added to the backend's list fails here until the client's copy learns it.
        assertEquals(
            listOf("/", "/join", "/health", "/.well-known/apple-app-site-association"),
            exact,
            "the backend's exact-path public GETs changed — update `isGatedRequest` (adapter/generic/app) and this pin",
        )
        assertEquals(
            listOf("/_astro/", "/attest/"),
            prefixes,
            "the backend's ungated prefixes changed — update `isGatedRequest` and this pin",
        )
        exact.forEach { path ->
            assertFalse(isGatedRequest("GET", path), "GET $path is ungated on the backend")
            assertTrue(isGatedRequest("POST", path) || path == "/", "only GET/HEAD open $path")
        }
        assertFalse(isGatedRequest("GET", "/_astro/index.abc123.js"))
        // `/attest/` is a device route: it arrives version-prefixed, under every method.
        assertFalse(isGatedRequest("POST", "/api/v1/attest/renew"))
        assertFalse(isGatedRequest("POST", "/api/v2/attest/token"))
        assertFalse(isGatedRequest("GET", "/api/v2/attest/challenge"))
        assertFalse(isGatedRequest("OPTIONS", "/api/v2/events"))
    }

    @Test
    fun `the two public event reads are ungated only as reads`() {
        val gate = gate()
        val reads = Regex("""/\^(\\/events\\/\[\^/]\+(?:\\/files)?)\$/""").findAll(gate).map { it.groupValues[1] }.toList()
        assertEquals(2, reads.size, "the backend's public event reads changed shape — update `isGatedRequest` and this pin: $reads")
        assertTrue(Regex("""\(method === "GET" \|\| method === "HEAD"\) &&\s*\(/\^""").containsMatchIn(gate), "the event reads are no longer GET/HEAD-only")
        assertFalse(isGatedRequest("GET", "/api/v2/events/E1"))
        assertFalse(isGatedRequest("HEAD", "/api/v2/events/E1/files"))
        // The same path shape, mutated, stays gated — the rename, the manifest, the leave.
        assertTrue(isGatedRequest("PATCH", "/api/v2/events/E1"))
        assertTrue(isGatedRequest("PUT", "/api/v2/events/E1/devices/D1/manifest"))
        assertTrue(isGatedRequest("DELETE", "/api/v2/events/E1/devices/D1"))
        assertTrue(isGatedRequest("POST", "/api/v2/events"))
        assertTrue(isGatedRequest("PUT", "/api/v2/devices/D1"))
    }
}
