package app.snapsync.rejoin

/**
 * One object already stored for the event, as returned by the backend per-event listing
 * (`bunny-list-endpoint`). The join needs only the reinstall-stable [filename] (the upload key it
 * matches local resources against) and the [lastModified] upload time (used as the seeded row's
 * `updatedAt` so "last backed up N ago" stays honest).
 */
class RemoteFile(val filename: String, val lastModified: String?)

/**
 * The seam that fetches the event's already-stored files (`GET /event/<id>/files`). Failures are
 * surfaced as a failed [Result] — never thrown — so the join can reduce them into `JoinFailed`
 * rather than crashing the app.
 */
interface EventFilesSource {
    suspend fun list(eventId: String): Result<List<RemoteFile>>
}
