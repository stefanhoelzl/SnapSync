package app.snapsync.membership

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import kotlin.time.TimeSource

private val httpLog = Logger.withTag("Http")

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
 * Every request is logged as a single line — method, URL, status, duration, request + response sizes
 * (capability `diagnostic-logging`, D4) — via an [HttpSend] interceptor installed here, so all call
 * sites through this factory are covered without per-call edits. The stock Ktor `Logging` plugin
 * can't emit timing or sizes; this bespoke interceptor can. It logs through Kermit, so each line
 * inherits the ambient `[entryPoint]` prefix. `-1` size means the Content-Length was absent.
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
): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
}.withCredentialInterceptor(token, onRejected)

/**
 * Install the credential + logging interceptor on [this] client.
 *
 * Split out of [darwinHttpClient] so it is REACHABLE BY A TEST. The 401 branch below is the entry point of
 * the whole credential-recovery loop — a rejected token is dropped and a fresh one obtained — and it can
 * only be exercised against a response, which the Darwin engine cannot be made to produce without a
 * server. A test builds its own client over a mock engine and applies this same function, so what it
 * asserts is this interceptor rather than a copy of it.
 *
 * Behaviour is identical to having it inline; the engine choice stays in [darwinHttpClient].
 */
internal fun HttpClient.withCredentialInterceptor(
    token: () -> String?,
    onRejected: () -> Unit,
): HttpClient = also { client ->
    client.plugin(HttpSend).intercept { request ->
        token()?.let { request.headers.append("Authorization", "Bearer $it") }
        val start = TimeSource.Monotonic.markNow()
        try {
            val call = execute(request)
            // A 401 means the backend REJECTED this token — which is NOT the same as it having expired, and
            // is the one case the expiry-based staleness check cannot see. It happens whenever the signing
            // key is rotated, or the leave cascade collects this device's attestation record. Without acting
            // on it, the app would keep re-sending a perfectly fresh-LOOKING but dead credential forever, and
            // no wake would ever heal it.
            if (call.response.status == HttpStatusCode.Unauthorized) onRejected()
            val ms = start.elapsedNow().inWholeMilliseconds
            val req = call.request.content.contentLength ?: -1L
            val resp = call.response.contentLength() ?: -1L
            httpLog.i {
                "${call.request.method.value} ${call.request.url} → ${call.response.status.value} " +
                    "(${ms}ms, req=$req, resp=$resp)"
            }
            call
        } catch (t: Throwable) {
            val ms = start.elapsedNow().inWholeMilliseconds
            httpLog.w(t) { "${request.method.value} ${request.url.buildString()} → FAILED (${ms}ms)" }
            throw t
        }
    }
}
