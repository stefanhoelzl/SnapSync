package app.snapsync.join

import app.snapsync.ports.EventJoin
import app.snapsync.ports.JoinResult
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/**
 * Production [EventJoin] over an injected Ktor [HttpClient] and host (Darwin on iOS):
 * `PUT <host>/events/<eventId>/devices/<deviceId>` carrying **no body**.
 *
 * The refusals are mapped rather than flattened, because the caller can act on the difference: a `409`
 * is the event at capacity — a sentence the join surface can show — while a `404` means the event is
 * gone and anything else is a transport failure a retry may heal. The previous seam answered all three
 * as `false`, which is how "this event is full" and "the network blipped" became the same message.
 */
class HttpEventJoin(
    private val client: HttpClient,
    host: String,
) : EventJoin {

    private val base = host.trimEnd('/')

    override suspend fun join(eventId: String, deviceId: String): JoinResult = runCatching {
        when (val status = client.put("$base/events/$eventId/devices/$deviceId").status) {
            HttpStatusCode.Conflict -> JoinResult.EVENT_FULL
            HttpStatusCode.NotFound -> JoinResult.EVENT_NOT_FOUND
            else -> if (status.isSuccess()) JoinResult.JOINED else JoinResult.FAILED
        }
    }.getOrDefault(JoinResult.FAILED)
}
