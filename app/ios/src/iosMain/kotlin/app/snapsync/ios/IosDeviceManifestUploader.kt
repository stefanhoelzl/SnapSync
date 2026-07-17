package app.snapsync.ios

import app.snapsync.ports.DeviceManifestUploader
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The app-process device-manifest uploader (capability `device-manifest`), the url-session tier's
 * (iOS 18–26.0) analogue of the extension's uploader: PUTs the device manifest JSON to
 * `<host>/events/<eventId>/devices/<deviceId>` over the injected Ktor [client] (shared Darwin client).
 * On this tier the **app** is the manifest's sole writer (no extension process exists). Returns `true`
 * only on a confirmed `2xx`; any transport error or non-2xx is `false`, so the producer retries the
 * unchanged snapshot next cycle. Thin wiring — untestable app-shell code (root `CLAUDE.md`).
 */
class IosDeviceManifestUploader(
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
