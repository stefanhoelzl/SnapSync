package app.snapsync.ports

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
