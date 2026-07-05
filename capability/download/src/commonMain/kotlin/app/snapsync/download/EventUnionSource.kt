package app.snapsync.download

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One resource of a foreign asset in the event-wide union (capability `bunny-list-endpoint`). */
class UnionResource(
    val key: String,
    val url: String,
    val role: String,
    val contentType: String,
    val originalFilename: String,
)

/** One **complete** asset in the event-wide union, tagged with its owning device and capture date. */
class UnionAsset(
    val deviceId: String,
    val assetId: String,
    val creationDate: String,
    val resources: List<UnionResource>,
)

/**
 * The seam that fetches the event-wide union (`GET /events/<eventId>/files`): every contributing
 * device's **complete** assets, each tagged with its `deviceId` and carrying per-resource download
 * `url`s. Failures surface as a failed [Result] (never thrown) so the download controller can keep its
 * last good state rather than crash. Own-vs-foreign selection is the caller's concern (by `deviceId`).
 */
interface EventUnionSource {
    suspend fun union(eventId: String): Result<List<UnionAsset>>
}

/**
 * [EventUnionSource] over an injected Ktor [HttpClient] (Darwin on iOS). GETs `<host>/events/<id>/files`
 * (HTTPS, default ATS), maps any non-2xx / transport / parse error to a failed [Result], and parses the
 * union array. `eventId` is a UUID, so no path encoding is required.
 */
class HttpEventUnionSource(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventUnionSource {

    private val base = host.trimEnd('/')

    override suspend fun union(eventId: String): Result<List<UnionAsset>> = runCatching {
        val response = client.get("$base/events/$eventId/files")
        check(response.status.isSuccess()) { "union $eventId: HTTP ${response.status.value}" }
        json.decodeFromString(ListSerializer(AssetDto.serializer()), response.bodyAsText())
            .map { dto ->
                UnionAsset(
                    deviceId = dto.deviceId,
                    assetId = dto.assetId,
                    creationDate = dto.creationDate,
                    resources = dto.resources.map {
                        UnionResource(it.key, it.url, it.role, it.contentType, it.filename)
                    },
                )
            }
    }

    @Serializable
    private class AssetDto(
        val deviceId: String,
        val assetId: String,
        val creationDate: String,
        val resources: List<ResourceDto>,
    )

    @Serializable
    private class ResourceDto(
        val key: String,
        val url: String,
        val role: String,
        val contentType: String,
        val filename: String,
    )
}
