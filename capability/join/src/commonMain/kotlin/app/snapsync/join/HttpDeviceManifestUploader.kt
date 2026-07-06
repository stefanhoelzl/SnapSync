package app.snapsync.join

import app.snapsync.gallery.DeviceManifestUploader
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Production [DeviceManifestUploader] over an injected Ktor [HttpClient] and host (Darwin on iOS):
 * `PUT <host>/events/<eventId>/devices/<deviceId>` with the manifest JSON body, mapping a 2xx to `true`
 * and any transport/non-2xx failure to `false`. The join capability's [ManifestDeviceEnroller] uses it
 * to write the register-only empty manifest at enrollment; it also fits the extension's manifest write.
 */
class HttpDeviceManifestUploader(
    private val client: HttpClient,
    host: String,
) : DeviceManifestUploader {

    private val base = host.trimEnd('/')

    override suspend fun put(eventId: String, deviceId: String, json: String): Boolean = runCatching {
        client.put("$base/events/$eventId/devices/$deviceId") {
            contentType(ContentType.Application.Json)
            setBody(json)
        }.status.isSuccess()
    }.getOrDefault(false)
}
