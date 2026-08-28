package app.snapsync.join

import app.snapsync.ports.ManifestPublisher
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Production [ManifestPublisher] over an injected Ktor [HttpClient] and host (Darwin on iOS):
 * `PUT <host>/events/<eventId>/devices/<deviceId>/manifest` with the manifest JSON body, mapping a 2xx
 * to `true` and any transport or non-2xx failure to `false`.
 *
 * A **sub-resource**, deliberately: the publish replaces the membership's asset set and does nothing
 * else. It enrols nobody — a publish from a device holding no membership is refused with a `409` rather
 * than silently creating one — and it records no upload.
 */
class HttpManifestPublisher(
    private val client: HttpClient,
    host: String,
) : ManifestPublisher {

    private val base = host.trimEnd('/')

    override suspend fun publish(eventId: String, deviceId: String, json: String): Boolean = runCatching {
        client.put("$base/events/$eventId/devices/$deviceId/manifest") {
            contentType(ContentType.Application.Json)
            setBody(json)
        }.status.isSuccess()
    }.getOrDefault(false)
}
