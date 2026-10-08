package app.snapsync.journeys

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.FileHead
import app.snapsync.model.Hmac
import app.snapsync.model.decodeEventKey
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.model.AssetId
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
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The journeys' second member (`docs/testing.md`, "All-real journeys are the contracts' safety net"):
 * a device the backend cannot tell from a real one, played over the backend's public HTTP surface ONLY.
 *
 * It joins, uploads and publishes exactly as a device's uploader addresses those routes, with real JPEG bytes — sealed
 * under the event's key when the event is encrypted, as every member's upload is — so the app's download of its photos
 * ends in a real PhotoKit import. The payloads are built from `model/`'s wire
 * types, so a change to the wire breaks this compile rather than drifting from what the app sends.
 *
 * It sends no `authorization` header: the dev backend's fallback bearer supplies the credential and the enrolment a
 * header-less caller lacks (`api/src/dev/serve.ts`, "FALLBACK BEARER"), exactly as it does for the simulator app,
 * which cannot attest.
 */
class Member(private val http: HttpClient, backend: String) {
    private val backend = backend.trimEnd('/')
    val deviceId: String = UUID.randomUUID().toString()

    /** The joined event's key, as its invite carried it — `null` for a plain event. */
    private var eventKey: ByteArray? = null

    /** Joins [eventId], keeping [linkKey] — the key its invite carries — to seal every upload with, as a device does. */
    suspend fun join(eventId: String, linkKey: String?) {
        eventKey = linkKey?.let { checkNotNull(decodeEventKey(it)) { "the invite's key is not a key" } }
        checked("join $deviceId", http.put("$backend/events/$eventId/devices/$deviceId") { served() })
    }

    /** Uploads [count] real photos captured at [creationDate] and publishes them; answers their asset ids. */
    suspend fun share(eventId: String, count: Int, creationDate: String): Set<AssetId> {
        val assets = List(count) {
            // The canonical id an iOS device mints for a PhotoKit local identifier.
            val assetId = AssetId("${UUID.randomUUID().toString().uppercase()}_L0_001")
            DeviceManifestAsset(assetId, creationDate, listOf(primary(assetId)))
        }
        assets.forEach { upload(eventId, it.assetId) }
        // The backend refuses a manifest without a version; 0 is always admitted (equal is, and a join clears it).
        val manifest = DeviceManifest(deviceId, assets, version = 0)
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

    private suspend fun upload(eventId: String, assetId: AssetId) {
        checked(
            "upload $assetId",
            http.put("$backend/events/$eventId/files/devices/$deviceId/$assetId/${ResourceRole.PRIMARY.wire}?filename=$FILENAME") {
                served()
                contentType(ContentType.Image.JPEG)
                setBody(eventKey?.let { sealed(it, eventId, assetId) } ?: JPEG)
            },
        )
    }

    /**
     * [JPEG] in the encrypted file format (`docs/architecture.md`), as a member's device seals it: the format's own
     * derivations from `model/`, over the JDK's HMAC-SHA256 and AES-256-GCM — the primitives the JVM's `Crypto` binds.
     */
    private fun sealed(key: ByteArray, eventId: String, assetId: AssetId): ByteArray {
        val hmac = Hmac { k, message ->
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(message)
        }
        val random = SecureRandom()
        val head = FileHead(
            keyId = EncryptedFileFormat.keyIdOf(key, hmac),
            salt = ByteArray(EncryptedFileFormat.SALT_LENGTH).also(random::nextBytes),
            noncePrefix = ByteArray(EncryptedFileFormat.NONCE_PREFIX_LENGTH).also(random::nextBytes),
        )
        val associated = EncryptedFileFormat.associatedData(eventId, deviceId, assetId.value, ResourceRole.PRIMARY.wire)
        val fileKey = SecretKeySpec(EncryptedFileFormat.fileKeyOf(key, head.salt, associated, hmac), "AES")
        var file = EncryptedFileFormat.encodeHead(head)
        var offset = 0
        var segment = 0
        do {
            val end = minOf(JPEG.size, offset + EncryptedFileFormat.plaintextSegmentLength(segment))
            val last = end == JPEG.size
            val nonce = EncryptedFileFormat.segmentNonce(head.noncePrefix, segment, last)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, fileKey, GCMParameterSpec(EncryptedFileFormat.TAG_LENGTH * Byte.SIZE_BITS, nonce))
            file += cipher.doFinal(JPEG, offset, end - offset)
            offset = end
            segment++
        } while (!last)
        return file
    }

    private fun primary(assetId: AssetId) = ManifestResource(
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
