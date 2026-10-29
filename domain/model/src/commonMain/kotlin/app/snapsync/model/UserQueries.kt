package app.snapsync.model

import app.snapsync.domain.sharing.CaptureRange

/**
 * The **user-query bundle** (`docs/architecture.md`, "Queries cross a lane-gated door"): every read the
 * status container invokes as a function rather than observes as a state flow.
 *
 * Seated beside [UserCommands] for the same reason — `model/` is the zone both `compose/` (which builds and
 * decorates the live implementation) and `:domain:presentation` (which receives it by constructor) may name. It exists
 * because these reads touch ports: the shareable count reads the download store and the photo library. Built outside
 * `compose/`, a query ran on whatever thread invoked it — for the count, a composable effect on the main thread — and
 * no gate saw it, because the lane gate covered only the command bundle. Built there, every query is lane-decorated in
 * the one file that builds both bundles. The join screen's read of an invite is the query layer's
 * (`JoinDetailsQuery`), awaited on the composition lane like this one.
 *
 * No query has a default body: a host that cannot answer a query must say what it answers instead.
 *
 * - [shareableCount] — how many of the member's own photos [range] would share, photos waiting for the event's start
 *   included, or `null` when the grant permits no count. Purely local.
 */
interface UserQueries {
    suspend fun shareableCount(range: CaptureRange): Int?
}
