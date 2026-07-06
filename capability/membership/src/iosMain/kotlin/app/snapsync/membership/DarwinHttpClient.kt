package app.snapsync.membership

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.contentLength
import kotlin.time.TimeSource

private val httpLog = Logger.withTag("Http")

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
 */
fun darwinHttpClient(): HttpClient = HttpClient(Darwin).also { client ->
    client.plugin(HttpSend).intercept { request ->
        val start = TimeSource.Monotonic.markNow()
        try {
            val call = execute(request)
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
