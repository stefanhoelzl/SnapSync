package app.snapsync.http

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.minAppVersionFromRefusal
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.save
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import kotlin.time.TimeSource

private val httpLog = Logger.withTag("Http")

/**
 * Install the credential, version and logging interceptor on [this] client.
 *
 * **Platform-free, and separate from any engine, because it is where four cross-cutting rules live and
 * none of them is about iOS**: every request carries the device token, every request declares this
 * build's version, a `401` from a gated route starts the credential-recovery loop, and a `426` records that the backend
 * refuses this build. Attaching them here rather than at each call site is the point — create, event
 * fetch, join, manifest, union, device config, leave, and the extension's reconcile listing all flow
 * through one factory, so none can be forgotten and a seam added later inherits all four.
 *
 * It lives in the technology-neutral adapter rather than beside the Darwin engine so that the SAME
 * function is exercised by tests and by the world harness over a `MockEngine`. That is not tidiness: the
 * behaviour under test is a response-handling rule, no engine can be made to produce the responses it
 * branches on without a server, and a harness that re-implemented it would assert a copy. `darwinHttpClient`
 * keeps the engine choice and applies this.
 */
fun HttpClient.withCredentialInterceptor(
    token: () -> String?,
    /**
     * The backend rejected [the token this request carried][sentToken]. Reported only for a request that carried a
     * token to a gated route ([isGatedRequest]); naming the token lets the store clear it only if it still holds it.
     */
    onRejected: suspend (sentToken: String) -> Unit,
    appVersion: () -> String = { "" },
    onVersionRefused: (minimumVersion: String?) -> Unit = {},
    onServed: () -> Unit = {},
): HttpClient = also { client ->
    client.plugin(HttpSend).intercept { request ->
        // Remembered, not re-read: by the time the answer arrives a renewal may have replaced it, and a rejection
        // must name the credential that was actually refused.
        val sent = token()?.also { request.headers.append("Authorization", "Bearer $it") }
        // Declared on EVERY request through this client, including the ungated `/attest/*` bootstrap:
        // an obsolete build that can still mint a token would otherwise discover it is obsolete only on
        // its next call, which is a worse first contact (capability `min-app-version`). Attaching it
        // here rather than per call site is the point — a seam added later inherits it for free.
        request.headers.append(APP_VERSION_HEADER, appVersion())
        val start = TimeSource.Monotonic.markNow()
        try {
            val call = execute(request)
            // A 401 from a GATED route means the backend REJECTED this token — which is NOT the same as it
            // having expired, and is the one case the expiry-based staleness check cannot see. It happens
            // whenever the signing key is rotated, or the leave cascade collects this device's attestation
            // record. Without acting on it, the app would keep re-sending a perfectly fresh-LOOKING but dead
            // credential forever, and no wake would ever heal it.
            //
            // Only from a gated route, and only when a token was sent: an ungated `/attest/*` 401 is that
            // route's own answer (a stale challenge, a refused attestation), never a verdict on the token (B2).
            if (sent != null && call.response.status == HttpStatusCode.Unauthorized &&
                isGatedRequest(request.method.value, request.url.encodedPath)
            ) {
                onRejected(sent)
            }
            // A 426 means the BACKEND refuses this build as too old (capability `min-app-version`), and
            // it is noticed here for the same reason the 401 above is: every metadata seam passes
            // through this one interceptor, so no seam can forget to report it, and a seam added later
            // is covered for free. A served response clears it, which is what heals the screen after an
            // update — nothing else has to remember to.
            //
            // The body is read through `save()`, which BUFFERS the response so the caller can still read
            // it. Consuming it here would leave a seam reading an empty channel on the one status whose
            // payload is the whole point. Only on 426, so the ordinary path buffers nothing.
            val settled = when (call.response.status) {
                HttpStatusCode.UpgradeRequired ->
                    call.save().also { onVersionRefused(minAppVersionFromRefusal(it.response.bodyAsText())) }
                else -> call.also { if (it.response.status.isSuccess()) onServed() }
            }
            val ms = start.elapsedNow().inWholeMilliseconds
            val req = settled.request.content.contentLength ?: -1L
            val resp = settled.response.contentLength() ?: -1L
            httpLog.i {
                "${settled.request.method.value} ${settled.request.url} → ${settled.response.status.value} " +
                    "(${ms}ms, req=$req, resp=$resp)"
            }
            settled
        } catch (t: Throwable) {
            val ms = start.elapsedNow().inWholeMilliseconds
            httpLog.w(t) { "${request.method.value} ${request.url.buildString()} → FAILED (${ms}ms)" }
            throw t
        }
    }
}
