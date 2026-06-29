package app.snapsync.rejoin

/**
 * One resource of a complete asset, as returned by the backend per-event listing
 * (`bunny-list-endpoint`). The join needs only the reinstall-stable [filename] — the upload key it
 * seeds `COMPLETED` and the producer later recomputes — so the other listed fields (role, contentType,
 * url) are ignored here.
 */
class RemoteResource(val filename: String)

/**
 * One **complete** asset from the backend listing: its [assetId] and the [resources] it declares (the
 * listing returns only assets all of whose resources are present). The join seeds one `COMPLETED` row
 * per resource, carrying this [assetId].
 */
class RemoteAsset(val assetId: String, val resources: List<RemoteResource>)

/**
 * The seam that fetches the event's already-stored **complete assets** (`GET /event/<id>/files`).
 * Failures are surfaced as a failed [Result] — never thrown — so the join can reduce them into
 * `JoinFailed` rather than crashing the app.
 */
interface EventFilesSource {
    suspend fun list(eventId: String): Result<List<RemoteAsset>>
}
