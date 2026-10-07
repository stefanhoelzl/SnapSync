package app.snapsync.http

/**
 * Whether the backend's token gate guards [method] [path] — the client's copy of the gate's CLOSED ungated list in
 * `api/src/app.ts` (capability `privacy-security`, "Only a rejected credential is invalidated, and only that one";
 * decision record `harden-seam-bug-classes`, D10).
 *
 * A `401` means "your credential is rejected" only where the gate ran. From an ungated route it is that route's own
 * answer: the `/attest/…` issuers refuse a stale challenge, a rejected attestation or a device with no record with
 * `401`, and reading any of those as a rejected token dropped a perfectly good one (B2). So a token is passed — and a
 * `401` read as a verdict on it — only on a gated route: `HttpBackendTest` pins that the `Backend` methods taking a
 * token are exactly the routes this predicate calls gated, and the backend mock's credential lever refuses only these.
 *
 * `path` is the request's path, `/api/vN` prefix included or not — the prefix is stripped exactly as the backend's
 * `splitVersion` strips it. The list is pinned to the backend's by `:test:architecture`'s `GatedPathPinTest`, which
 * reads `app.ts`, so a route the backend opens cannot stay gated here unnoticed.
 */
fun isGatedRequest(method: String, path: String): Boolean {
    val bare = bare(path)
    val read = method == "GET" || method == "HEAD"
    val ungated = method == "OPTIONS" ||
        bare.startsWith("/attest/") ||
        (read && (bare in PUBLIC_GETS || bare.startsWith("/_astro/") || EVENT_PAGE.matches(bare) || WEB_PHOTOS.matches(bare))) ||
        (read && (EVENT_READ.matches(bare) || EVENT_UNION_READ.matches(bare) || DOWNLOAD_REDIRECT.matches(bare)))
    return !ungated
}

/**
 * Whether a token sent with [method] [path] is VERIFIED, so that a `401` from it is a verdict on that token: every
 * gated route ([isGatedRequest]), and the two public event reads, which check a token when one is sent — the union so
 * its log can name the reader (decision record `changes/incremental-union`, D5), the event's details so the app's
 * read is credentialed (`changes/separate-event-page-from-device-api`, D7). A token is passed only where this
 * holds; `HttpBackendTest` pins the `Backend` methods that take one to it.
 */
fun verifiesToken(method: String, path: String): Boolean =
    isGatedRequest(method, path) ||
        ((method == "GET" || method == "HEAD") && (EVENT_UNION_READ.matches(bare(path)) || EVENT_READ.matches(bare(path))))

private fun bare(path: String) = VERSION_PREFIX.replaceFirst(path.substringBefore('?'), "").ifEmpty { "/" }

private val VERSION_PREFIX = Regex("""^/api/v\d+(?=/|$)""")

/** The exact-path public GETs: the marketing page, the join page, the health probe, the AASA, the asset links. */
internal val PUBLIC_GETS = setOf(
    "/",
    "/join",
    "/health",
    "/.well-known/apple-app-site-association",
    "/.well-known/assetlinks.json",
)

/** The event's own page, `/join/<eventId>` — exactly one segment (capability `event-site`). */
private val EVENT_PAGE = Regex("""^/join/[^/]+$""")

/**
 * The event page's read, `/web/events/<eventId>/photos` (decision record `changes/separate-event-page-from-device-api`):
 * a browser's, never the app's — known here so the copy of the backend's list stays whole.
 */
private val WEB_PHOTOS = Regex("""^/web/events/[^/]+/photos$""")

/** The two event reads authorized by eventId possession alone. */
private val EVENT_READ = Regex("""^/events/[^/]+$""")
private val EVENT_UNION_READ = Regex("""^/events/[^/]+/files$""")

/**
 * The download redirect (decision record `changes/incremental-union`, D1): the OS's download transports fetch it,
 * never the `Backend` port, so no token ever rides it — known here so the copy of the backend's list stays whole.
 */
private val DOWNLOAD_REDIRECT = Regex("""^/events/[^/]+/files/devices/[^/]+/[^/]+/[^/]+$""")
