package app.snapsync.model

/**
 * The **user-query bundle** (`docs/architecture.md`, "Queries cross a lane-gated door"): every read the
 * status container invokes as a function rather than observes as a state flow.
 *
 * Seated beside [UserCommands] for the same reason — `model/` is the zone both `compose/` (which builds and
 * decorates the live implementation) and `:domain:presentation` (which receives it by constructor) may name. It exists
 * because these reads touch ports: the shareable count reads the download store and the photo library, the
 * join details cross the network. Built outside `compose/`, a query ran on whatever thread invoked it — for the
 * count, a composable effect on the main thread — and no gate saw it, because the lane gate covered only the
 * command bundle. Built there, every query is lane-decorated in the one file that builds both bundles.
 *
 * No query has a default body: a host that cannot answer a query must say what it answers instead.
 *
 * - [loadJoinDetails] — the join gate's details read (capability `join-event`): `GET /events/:id` mapped to
 *   a block / retry / ready outcome, and [JoinLoad.WrongLink] when the link's key (`linkKey`) does not open the event.
 * - [shareableCount] — how many of the member's own photos the range `[cutoff, until]` would share
 *   (capability `join-event`), or `null` when the grant permits no count. Purely local.
 */
interface UserQueries {
    suspend fun loadJoinDetails(eventId: String, linkKey: String?): JoinLoad

    suspend fun shareableCount(cutoff: CaptureCutoff, until: CaptureCeiling?): Int?
}
