package app.snapsync.rejoin

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The [EventFilesSource] over an injected Ktor [HttpClient] (the engine — Darwin on iOS — is supplied
 * by the composition root, so this stays platform-neutral and testable with `MockEngine`). It GETs
 * `<host>/event/<eventId>/files` (HTTPS, default ATS), maps any non-2xx / transport / parse error to
 * a failed [Result], and parses the complete-asset array into [RemoteAsset]s — reading only each
 * asset's `assetId` and its resources' `filename` (the other listed fields are ignored unknown keys).
 * The `eventId` is a UUID, so no path encoding is required.
 */
class HttpEventFilesSource(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventFilesSource {

    private val base = host.trimEnd('/')

    override suspend fun list(eventId: String): Result<List<RemoteAsset>> = runCatching {
        val response = client.get("$base/event/$eventId/files")
        check(response.status.isSuccess()) { "list $eventId: HTTP ${response.status.value}" }
        json.decodeFromString(ListSerializer(AssetDto.serializer()), response.bodyAsText())
            .map { dto -> RemoteAsset(dto.assetId, dto.resources.map { RemoteResource(it.filename) }) }
    }

    @Serializable
    private class AssetDto(
        val assetId: String,
        val resources: List<ResourceDto>,
    )

    @Serializable
    private class ResourceDto(
        val filename: String,
    )
}
