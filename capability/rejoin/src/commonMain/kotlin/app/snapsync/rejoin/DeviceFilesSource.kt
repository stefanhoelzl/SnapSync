package app.snapsync.rejoin

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The seam that fetches a **device's** already-stored object filenames (`GET /devices/<deviceId>/files`,
 * `bunny-list-endpoint`). Bytes are device-partitioned and event-independent, so this is the dedup
 * source the extension reconciler seeds `COMPLETED` from — a reinstall (empty ledger) is restored by
 * it, and it preserves dedup across an event switch. Failures are a failed [Result] (never thrown), so
 * the reconciler can defer the cycle rather than crash.
 */
interface DeviceFilesSource {
    suspend fun list(deviceId: String): Result<List<String>>
}

/**
 * The [DeviceFilesSource] over an injected Ktor [HttpClient] (Darwin on iOS, supplied by the
 * composition root; `MockEngine` in tests). GETs `<host>/devices/<deviceId>/files` (HTTPS, default
 * ATS), maps any non-2xx / transport / parse error to a failed [Result], and reads only each entry's
 * `filename` (the `size`/`url` fields are ignored unknown keys). The `deviceId` is a UUID, so no path
 * encoding is required.
 */
class HttpDeviceFilesSource(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DeviceFilesSource {

    private val base = host.trimEnd('/')

    override suspend fun list(deviceId: String): Result<List<String>> = runCatching {
        val response = client.get("$base/devices/$deviceId/files")
        check(response.status.isSuccess()) { "list device $deviceId: HTTP ${response.status.value}" }
        json.decodeFromString(ListSerializer(FileDto.serializer()), response.bodyAsText())
            .map { it.filename }
    }

    @Serializable
    private class FileDto(val filename: String)
}
