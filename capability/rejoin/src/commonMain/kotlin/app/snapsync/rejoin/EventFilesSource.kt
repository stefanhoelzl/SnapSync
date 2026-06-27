package app.snapsync.rejoin

/**
 * One object already stored for the event, as returned by the backend per-event listing
 * (`bunny-list-endpoint`). The join needs only the reinstall-stable [filename] — the upload key it
 * matches local resources against. (Seeded rows take their `updatedAt` from the join time; an
 * uploaded resource is immutable, so no stored timestamp or version is consulted.)
 */
class RemoteFile(val filename: String)

/**
 * The seam that fetches the event's already-stored files (`GET /event/<id>/files`). Failures are
 * surfaced as a failed [Result] — never thrown — so the join can reduce them into `JoinFailed`
 * rather than crashing the app.
 */
interface EventFilesSource {
    suspend fun list(eventId: String): Result<List<RemoteFile>>
}
