package app.snapsync.ios.upload

import app.snapsync.gallery.DeviceManifestUploader
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The synchronous, in-cycle device-manifest uploader (capability `device-manifest`): PUTs the device
 * manifest JSON to `<host>/events/<eventId>/devices/<deviceId>` over the injected Ktor [client] (Darwin /
 * NSURLSession on iOS, default ATS). Returns `true` only on a confirmed `2xx` (so the producer records
 * the snapshot as last-uploaded only on success); any transport error or non-2xx is `false`, so the
 * producer simply retries the unchanged snapshot next cycle. No background session — the call completes
 * within the cycle (a kill mid-PUT is recomputed next cycle).
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
