package app.snapsync.ports

/**
 * **HTTP, and nothing above it** — the one external system every backend call crosses (`docs/architecture.md`, "The
 * backend boundary"). The backend's protocol — its routes, bodies, token and version header, and what an answer
 * means — is the core's own (`BackendGateway`, over the generated wire), so this port speaks platform vocabulary only:
 * a request goes out, a [HttpResult.Response] of any status comes back, or [HttpResult.Unreachable].
 *
 * **It never throws** (cancellation aside): the adapter catches its engine's failures ONCE, at its edge, and answers
 * them as [HttpResult.Unreachable] — so no caller above wraps a call in a catch.
 *
 * Its promises are the port contract `HttpTransportContract`, bound for every engine the app ships: CIO and OkHttp on
 * the JVM, OkHttp on the Android emulator, Darwin on the iOS simulator.
 */
fun interface HttpTransport : Port {
    suspend fun send(request: HttpRequest): HttpResult
}

/**
 * One HTTP exchange's request, as the `HttpTransport` port carries it: the [method], the absolute [url], the [headers]
 * to send — a `Content-Type` among them names the [body]'s media type — and a text [body] or none. Plain HTTP and
 * nothing else: what a route means, which token it carries and how its answer reads is the backend gateway's
 * (`docs/architecture.md`, "The backend boundary").
 */
class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

/** What one exchange came to: an answer — whatever its status — or none at all. */
sealed interface HttpResult {
    /** The server answered: its [status], its [headers] (one value per name) and its body as text. */
    class Response(val status: Int, val headers: Map<String, String>, val body: String) : HttpResult

    /** No answer reached the app — no connection, a reset, a timeout, a body cut short — and why, for the log. */
    class Unreachable(val reason: String) : HttpResult
}
