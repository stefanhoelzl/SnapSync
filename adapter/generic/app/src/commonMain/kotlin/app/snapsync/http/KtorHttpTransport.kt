package app.snapsync.http

import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult
import app.snapsync.ports.HttpTransport
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private val httpLog = Logger.withTag("Http")

/**
 * The [HttpTransport] port over a Ktor [HttpClient] — one class for every platform; each composition supplies the
 * engine (Darwin on iOS, OkHttp on Android, CIO on the JVM).
 *
 * It sends what it is given and answers what came back, whatever the status, with one log line per request. Every
 * failure of the engine — no connection, a reset, a timeout, a body cut short — is caught HERE, once, and answered as
 * [HttpResult.Unreachable]: nothing above this edge ever wraps a call in a catch.
 */
class KtorHttpTransport(private val client: HttpClient) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpResult {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method)
                request.headers.filterKeys { !it.equals(HttpHeaders.ContentType, ignoreCase = true) }
                    .forEach { (name, value) -> header(name, value) }
                // Ktor refuses `Content-Type` as a plain header: it travels with the body it describes.
                request.body?.let { setBody(TextContent(it, contentTypeOf(request))) }
            }
            val text = response.bodyAsText()
            httpLog.i {
                "${request.method} ${request.url} → ${response.status.value} " +
                    "(${start.elapsedNow().inWholeMilliseconds}ms, req=${request.body?.length ?: 0}, resp=${text.length})"
            }
            HttpResult.Response(
                response.status.value,
                response.headers.entries().associate { (name, values) -> name to values.joinToString(",") },
                text,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            httpLog.w(t) { "${request.method} ${request.url} → FAILED (${start.elapsedNow().inWholeMilliseconds}ms)" }
            HttpResult.Unreachable(t.toString())
        }
    }

    private fun contentTypeOf(request: HttpRequest): ContentType =
        ContentType.parse(request.headers.mapKeys { it.key.lowercase() }["content-type"] ?: "text/plain")
}
