package app.snapsync.architecture

import app.snapsync.http.isGatedRequest
import app.snapsync.http.verifiesToken
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.Routes
import java.io.File
import kotlin.reflect.full.declaredFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The client's ungated-path predicate is the backend's closed list** (capability `privacy-security`, "Only a
 * rejected credential is invalidated, and only that one"; decision record `harden-seam-bug-classes`, D10).
 *
 * A token is passed, and a `401` read as a rejected token, only on a route the token gate guards ([isGatedRequest];
 * `HttpBackendTest` pins the `Backend` port's token-taking routes to it). That predicate is a copy of the gate's closed list in `api/src/app.ts`, and a copy drifts: a
 * route the backend opens but the client still thinks gated would have its own `401`s read as a revoked credential
 * again (B2). So this reads the backend's ROOT list out of `app.ts`, and the device API's from the generated wire
 * (`api/openapi.yaml`'s `security`), and drives the REAL predicate against every entry.
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
            listOf("/", "/join", "/health", "/.well-known/apple-app-site-association", "/.well-known/assetlinks.json"),
            exact,
            "the backend's exact-path public GETs changed — update `isGatedRequest` (adapter/generic/app) and this pin",
        )
        assertEquals(
            listOf("/_astro/"),
            prefixes,
            "the backend's ungated prefixes changed — update `isGatedRequest` and this pin",
        )
        exact.forEach { path ->
            assertFalse(isGatedRequest("GET", path), "GET $path is ungated on the backend")
            assertTrue(isGatedRequest("POST", path) || path == "/", "only GET/HEAD open $path")
        }
        assertFalse(isGatedRequest("GET", "/_astro/index.abc123.js"))
        // The event's own page: one segment under `/join/`, read-only — the gate's one regex-shaped exact path.
        assertTrue(
            """/^\/join\/[^/]+$/.test(path)""" in gate,
            "the backend's event-page match (`/join/<eventId>`) changed — update `isGatedRequest` and this pin",
        )
        assertEquals(
            2,
            Regex("""\.test\(path\)""").findAll(gate).count(),
            "a new regex-shaped public GET — pin it here",
        )
        assertFalse(isGatedRequest("GET", "/join/3f2c0000-0000-4000-8000-00000000e91a"))
        assertTrue(isGatedRequest("POST", "/join/3f2c0000-0000-4000-8000-00000000e91a"))
        assertTrue(isGatedRequest("GET", "/join/a/b"))
        // The event page's read: exactly `/web/events/<id>/photos`, read-only (`routes/web.ts`).
        assertTrue(
            "WEB_PHOTOS_PATH.test(path)" in gate,
            "the backend no longer admits the event page's read — update `isGatedRequest` and this pin",
        )
        val web = File(SourceScan.repoRoot, "api/src/routes/web.ts").readText()
        assertTrue(
            """WEB_PHOTOS_PATH = /^\/web\/events\/[^/]+\/photos$/""" in web,
            "the backend's event-page read changed shape — update `isGatedRequest` and this pin",
        )
        assertFalse(isGatedRequest("GET", "/web/events/3f2c0000-0000-4000-8000-00000000e91a/photos"))
        assertTrue(isGatedRequest("POST", "/web/events/3f2c0000-0000-4000-8000-00000000e91a/photos"))
        assertTrue(isGatedRequest("GET", "/web/events/E1/photos/x"))
        // `/attest/` is a device route: it arrives version-prefixed, under every method.
        assertFalse(isGatedRequest("POST", "/api/v2/attest/renew"))
        assertFalse(isGatedRequest("POST", "/api/v2/attest/token"))
        assertFalse(isGatedRequest("GET", "/api/v2/attest/challenge"))
        assertFalse(isGatedRequest("OPTIONS", "/api/v2/events"))
    }

    /**
     * The DEVICE API's half: the backend reads it off `api/openapi.yaml` (`api/src/route-security.ts`), and the
     * generated wire states the same `security` — a `GatedRequest` for a route that verifies a token, an
     * `OpenRequest` for one that takes none. The client's copy must agree with every route the description declares.
     */
    @Test
    fun `the client's predicate agrees with every route the description declares`() {
        val routes = Routes::class.nestedClasses.filter { it.objectInstance != null }
        assertTrue(routes.size >= 10, "the generated routes moved — re-point this pin")
        routes.forEach { route ->
            val method = route.java.getField("METHOD").get(null) as String
            val path = "/api/v2" + (route.java.getField("TEMPLATE").get(null) as String).replace(Regex("""\{\w+}"""), "E1")
            val request = route.declaredFunctions.firstOrNull { it.name == "request" } ?: return@forEach
            when (request.returnType.classifier) {
                GatedRequest::class -> assertTrue(
                    verifiesToken(method, path),
                    "$method $path verifies a token it is sent",
                )
                OpenRequest::class -> {
                    assertFalse(isGatedRequest(method, path), "$method $path is open")
                    assertFalse(verifiesToken(method, path), "$method $path is sent no token")
                }
                else -> error("${route.simpleName} builds a ${request.returnType} — not a request of either kind")
            }
        }
    }

    @Test
    fun `the legacy anonymous event reads are public reads, every other event route gated`() {
        // `PENDING_CLEANUPS.md`, "Anonymous event reads": the description marks them optional; the client sends a token.
        assertFalse(isGatedRequest("GET", "/api/v2/events/E1"))
        assertFalse(isGatedRequest("HEAD", "/api/v2/events/E1/files"))
        assertTrue(verifiesToken("GET", "/api/v2/events/E1"))
        assertTrue(verifiesToken("GET", "/api/v2/events/E1/files"))
        // The same path shape, mutated, stays gated — the change, the manifest, the leave.
        assertTrue(isGatedRequest("PATCH", "/api/v2/events/E1"))
        assertTrue(isGatedRequest("PUT", "/api/v2/events/E1/devices/D1/manifest"))
        assertTrue(isGatedRequest("DELETE", "/api/v2/events/E1/devices/D1"))
        assertTrue(isGatedRequest("POST", "/api/v2/events"))
        assertTrue(isGatedRequest("PUT", "/api/v2/devices/D1"))
    }

    @Test
    fun `the download redirect is ungated, and only as a read`() {
        assertFalse(isGatedRequest("GET", "/api/v2/events/E1/files/devices/D1/A1/primary"))
        assertFalse(isGatedRequest("HEAD", "/api/v2/events/E1/files/devices/D1/A1/primary"))
        assertTrue(isGatedRequest("PUT", "/api/v2/events/E1/files/devices/D1/A1/primary"))
    }
}
