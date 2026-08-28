package app.snapsync.membership

import app.snapsync.model.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The [DeviceFilesSource] over an injected Ktor [HttpClient] (Darwin on iOS, supplied by the
 * composition root; `MockEngine` in tests). GETs `<host>/files/devices/<deviceId>` (HTTPS, default ATS)
 * and answers the **storage keys** the device has stored.
 *
 * The backend answers in **identity terms** — `assetId`, `role`, and the resource's **capture
 * filename** — and mints no `url`. The key is therefore **recomposed** here through the shared
 * [uploadKey] builder, so the one definition of the storage layout stays in `model/` and this seam
 * cannot invent a key the client would not have composed. The recomposition is exact even when the
 * capture name is unavailable or is itself a storage key, because only its extension is consumed.
 *
 * **Decoding is STRICT, and that is load-bearing.** The previous listing shape also carried a field
 * named `filename`, and it carried the storage KEY where this one carries the CAPTURE NAME. A lenient
 * decode accepts either and silently means the opposite: capture names get seeded as ledger keys, every
 * real key is left unseeded, and the device re-uploads its entire library on the next rejoin — with no
 * exception, no failed request and no log line. Requiring `assetId` and `role`, and decoding `role`
 * against the closed [ResourceRole] vocabulary rather than as an opaque string, is what makes that
 * mismatch fail loudly instead.
 *
 * Failures are a failed `Result` (never thrown to the caller), and a **shape** failure is
 * distinguishable from a transport one: see [DeviceListingShapeException].
 */
class HttpDeviceFilesSource(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DeviceFilesSource {

    private val base = host.trimEnd('/')

    override suspend fun list(deviceId: String): Result<List<String>> {
        val body = runCatching {
            val response = client.get("$base/files/devices/$deviceId")
            check(response.status.isSuccess()) { "list device $deviceId: HTTP ${response.status.value}" }
            response.bodyAsText()
        }.getOrElse { return Result.failure(it) } // transport: transient, retried by deferring
        return runCatching {
            json.decodeFromString(ListSerializer(ResourceDto.serializer()), body)
                .map { uploadKey(it.assetId, it.role, it.filename) }
        }.recoverCatching {
            throw DeviceListingShapeException(
                "the per-device listing did not decode into {assetId, role, filename}: ${it.message}",
            )
        }
    }

    /** One stored resource, in the terms the backend addresses resources by. */
    @Serializable
    private class ResourceDto(val assetId: String, val role: ResourceRole, val filename: String)
}
