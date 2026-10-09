package app.snapsync.model

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
