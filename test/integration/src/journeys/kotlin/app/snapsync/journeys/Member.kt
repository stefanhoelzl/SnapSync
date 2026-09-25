package app.snapsync.journeys

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.model.normalizeAssetId
import app.snapsync.model.uploadKey
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.UUID

/**
 * The journeys' second member (capability `testing-architecture`, "All-real journeys are the contracts' safety net"):
 * a device the backend cannot tell from a real one, played over the backend's public HTTP surface ONLY.
 *
 * It joins, uploads and publishes exactly as a device's uploader addresses those routes, with real JPEG bytes, so
 * the app's download of its photos ends in a real PhotoKit import. The payloads are built from `model/`'s wire
 * types, so a change to the wire breaks this compile rather than drifting from what the app sends.
 *
 * It sends no `authorization` header: the dev backend's fallback bearer supplies the credential and the enrolment a
 * header-less caller lacks (`api/src/dev/serve.ts`, "FALLBACK BEARER"), exactly as it does for the simulator app,
 * which cannot attest.
 */
class Member(private val http: HttpClient, backend: String) {
    private val backend = backend.trimEnd('/')
    val deviceId: String = UUID.randomUUID().toString()

    suspend fun join(eventId: String) {
        checked("join $deviceId", http.put("$backend/events/$eventId/devices/$deviceId") { served() })
    }

    /** Uploads [count] real photos captured at [creationDate] and publishes them; answers their asset ids. */
    suspend fun share(eventId: String, count: Int, creationDate: String): Set<String> {
        val assets = List(count) {
            // An iOS local identifier, normalized as the app's uploader normalizes one (the path takes no `/`).
            val assetId = normalizeAssetId("${UUID.randomUUID().toString().uppercase()}/L0/001")
            DeviceManifestAsset(assetId, creationDate, listOf(primary(assetId)))
        }
        assets.forEach { upload(it.assetId) }
        val manifest = DeviceManifest(deviceId, assets)
        checked(
            "publish manifest",
            http.put("$backend/events/$eventId/devices/$deviceId/manifest") {
                served()
                contentType(ContentType.Application.Json)
                setBody(manifest.encodeToJson())
            },
        )
        return assets.mapTo(mutableSetOf()) { it.assetId }
    }

    private suspend fun upload(assetId: String) {
        checked(
            "upload $assetId",
            http.put("$backend/files/devices/$deviceId/$assetId/${ResourceRole.PRIMARY.wire}?filename=$FILENAME") {
                served()
                contentType(ContentType.Image.JPEG)
                setBody(JPEG)
            },
        )
    }

    private fun primary(assetId: String) = ManifestResource(
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        key = uploadKey(assetId, ResourceRole.PRIMARY, FILENAME),
        filename = FILENAME,
    )

    private fun HttpRequestBuilder.served() = header(APP_VERSION_HEADER, SERVED_VERSION)

    private suspend fun checked(step: String, response: HttpResponse): HttpResponse {
        check(response.status.isSuccess()) {
            "the member's '$step' was refused by the backend: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
        return response
    }

    private companion object {
        const val FILENAME = "IMG_0001.JPG"
        const val SERVED_VERSION = "99.0"

        /** A real 16×16 JPEG — the smallest ordinary photo a library accepts, so the app's import is a real one. */
        val JPEG: ByteArray = checkNotNull(Member::class.java.getResourceAsStream("/member.jpg")) {
            "member.jpg is missing from the journeys' resources"
        }.use { it.readBytes() }
    }
}
