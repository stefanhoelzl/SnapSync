package app.snapsync.membership

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

/**
 * The per-request ceiling every call through this client carries (capability `sync-status`).
 *
 * Without one, the request is bounded only by `NSURLSession`'s defaults — and on a background wake that
 * is not a bound at all. The session runs **in-process**, so a suspended app services no socket; its
 * wall-clock idle timer expires unobserved, and the task reports only when the app next runs. Measured
 * in SNAPSYNC-6 (2026-08-01): of 19 `GET …/files`, the 14 that answered took **150–1673 ms**, while the
 * 5 that failed reported 64 s, 169 s, 419 s, 1191 s and 1642 s — each equal to the distance to the next
 * OS wake, not to anything the network did. The distribution is bimodal with nothing between, because
 * the two modes are different events: one RTT while awake, or nothing at all while frozen.
 *
 * 5 s therefore sits ~3× above the slowest real answer and far below any suspension artifact, and it
 * bounds the network portion of a wake's own work — now that no handler deadline exists, it is what stops a
 * stalled request from holding an OS handler until the OS's own expiry. A fast failure costs a retry and never
 * correctness — `DownloadController.reconcile` keeps last-good state on a union failure by contract.
 *
 * Corollary kept deliberately: with this ceiling in place, a request still reported as minutes long
 * **is** the suspension signal, at no extra cost.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

/**
 * The iOS HTTP client the backend port runs over: NSURLSession via Ktor's Darwin engine, so every call honours
 * default ATS (HTTPS-only).
 *
 * What this file OWNS is the engine and the timeout above — the two genuinely iOS facts. Everything that happens to
 * a request or a response is someone else's: the routes, the declared build version and the bearer header are
 * `HttpBackend`'s (the technology-neutral adapter, identical on every platform), and what an answer means — a
 * rejected credential, a refused build — is the authenticated backend's, in `:domain:services`.
 */
fun darwinHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
}
