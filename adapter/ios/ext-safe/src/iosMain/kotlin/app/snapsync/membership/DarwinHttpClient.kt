package app.snapsync.membership

import app.snapsync.http.withCredentialInterceptor
import app.snapsync.logging.appMarketingVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

/**
 * The per-request ceiling every call through this client carries (capability `ios-app-shell`).
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
 * bounds the network portion of a receipt-held span. A fast failure costs a retry and never
 * correctness — `DownloadController.reconcile` keeps last-good state on a union failure by contract.
 *
 * Corollary kept deliberately: with this ceiling in place, a request still reported as minutes long
 * **is** the suspension signal, at no extra cost.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

/**
 * The iOS HTTP client for the re-join list fetch: NSURLSession via Ktor's Darwin engine, so the
 * fetch honours default ATS (HTTPS-only). Lives here so the engine choice stays in the capability
 * and the composition roots only wire it into [HttpDeviceFilesSource].
 *
 * What this file OWNS is the engine and the timeout above — the two genuinely iOS facts. Everything
 * that happens to a request or a response is [withCredentialInterceptor]'s, in the technology-neutral
 * adapter: the device token, the declared build version, the `401` recovery loop, the `426` refusal, and
 * the one-line request log. It is applied here rather than defined here so the world harness and the
 * tests exercise the same function over a `MockEngine` instead of a copy of it.
 *
 * **[token] authenticates every request made through this client** (capability `device-attestation`).
 * Attaching it here rather than at each call site is the point: create, event fetch, join/manifest,
 * union, device config, leave, notify, and the extension's reconcile listing ALL flow through this one
 * factory, so none of them can be forgotten — and a future caller inherits the header for free.
 *
 * It is read **per request**, never captured once: the app renews the token in the background, and a
 * client holding a stale copy would keep sending a dead credential long after a fresh one was available.
 *
 * A null token still sends the request, deliberately — it will `401`, and a `401` is a retryable failure.
 * Refusing to send would strand the work instead. (The three `/attest/…` routes are ungated, so the
 * bootstrap request that has no token yet is served regardless.)
 */
fun darwinHttpClient(
    token: () -> String? = { null },
    onRejected: () -> Unit = {},
    appVersion: () -> String = ::appMarketingVersion,
    onVersionRefused: (minimumVersion: String?) -> Unit = {},
    onServed: () -> Unit = {},
): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
}.withCredentialInterceptor(token, onRejected, appVersion, onVersionRefused, onServed)
