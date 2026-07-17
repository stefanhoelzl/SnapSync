package app.snapsync.world

import app.snapsync.ports.Enrollment
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * A common [Enrollment] living in `:test:world` (design decision D6): PUTs the device
 * manifest JSON to `<host>/events/<eventId>/devices/<deviceId>` over the injected mini-edge [client] —
 * a mirror of `:adapter:generic`'s `HttpEnrollment`, which production composes since migration
 * step 7 (the root-side `IosEnrollment` copies died there).
 *
 * Kept world-local so the world's mini-edge client stays injectable without a production dep;
 * the deletion-ledger's "Enrollment (keep 1)" row dies with this copy at migration step 10.
 */
class HttpEnrollment(
    private val client: HttpClient,
    host: String,
) : Enrollment {

    private val base = host.trimEnd('/')

    override suspend fun put(eventId: String, deviceId: String, json: String): Boolean = runCatching {
        client.put("$base/events/$eventId/devices/$deviceId") {
            contentType(ContentType.Application.Json)
            setBody(json)
        }.status.isSuccess()
    }.getOrDefault(false)
}
