package app.snapsync.ports

import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult

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
