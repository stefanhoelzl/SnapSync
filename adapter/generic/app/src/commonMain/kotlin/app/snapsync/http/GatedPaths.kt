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
 * token are exactly the routes this predicate calls gated, and the mini-edge's credential lever refuses only these.
 *
 * `path` is the request's path, `/api/vN` prefix included or not — the prefix is stripped exactly as the backend's
 * `splitVersion` strips it. The list is pinned to the backend's by `:test:architecture`'s `GatedPathPinTest`, which
 * reads `app.ts`, so a route the backend opens cannot stay gated here unnoticed.
 */
fun isGatedRequest(method: String, path: String): Boolean {
    val bare = VERSION_PREFIX.replaceFirst(path, "").ifEmpty { "/" }
    val read = method == "GET" || method == "HEAD"
    val ungated = method == "OPTIONS" ||
        bare.startsWith("/attest/") ||
        (read && (bare in PUBLIC_GETS || bare.startsWith("/_astro/"))) ||
        (read && (EVENT_READ.matches(bare) || EVENT_UNION_READ.matches(bare)))
    return !ungated
}

private val VERSION_PREFIX = Regex("""^/api/v\d+(?=/|$)""")

/** The exact-path public GETs: the marketing page, the join page, the health probe, the AASA. */
internal val PUBLIC_GETS = setOf("/", "/join", "/health", "/.well-known/apple-app-site-association")

/** The two event reads authorized by eventId possession alone. */
private val EVENT_READ = Regex("""^/events/[^/]+$""")
private val EVENT_UNION_READ = Regex("""^/events/[^/]+/files$""")
