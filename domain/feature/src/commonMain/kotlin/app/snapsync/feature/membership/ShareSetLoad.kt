package app.snapsync.feature.membership

import app.snapsync.data.store.StoreWrite
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.ListingRead
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.domain.event.EventId
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Upper bound on the join-time listing. The join surface is waiting on it, and uploads are bounded by an
 * event window of at most 30 days, so the answer is small: phase 1 measured 1.67 s for 1,433 rows on an SE2
 * (`changes/archive/2026-09-21-always-full-enumerate`). The bound exists for a stuck call, not a slow one.
 */
private const val LISTING_TIMEOUT_MS = 15_000L

/**
 * The join-time load — a join loads the ledger from the per-device listing: at a provision into a
 * **new** membership — a first join or a switch, never a re-provision of
 * the joined event — make the joined event's ledger rows this membership's share set.
 *
 * Every ledger row belongs to one event and every read is scoped to the joined one (change
 * `event-scoped-local-state`), so rows of an earlier event are inert whatever happened to them; this load purges
 * them as housekeeping. On a confirmed listing the joined event's rows become exactly the device's stored
 * resources IN THAT EVENT, one bare `COMPLETED` row each, in one atomic seed — so nothing the backend already
 * holds for this event is uploaded again. Each event holds its own bytes (change `per-event-storage-layout`): a
 * photo stored for another event is uploaded again for this one. On a failed or timed-out listing the other events'
 * rows are purged and the joined event's are left as they are: empty for a first join, and for a rejoin what this
 * device last knew of the event, whose bytes the backend still holds while the event lives.
 *
 * **Why a failure blocks nothing.** There is no flag, no gate and no "load owed" bit. The only cost of a
 * failed load is that the walk records the window's photos as new work and uploads them again — idempotent
 * overwrites of the same `(eventId, deviceId, assetId, role)` objects, bounded by the event window. Retry state
 * could strand a device behind a gate; this cost cannot.
 *
 * It never throws: the join it belongs to completes whatever the listing does. A transport failure or a
 * timeout is a warning; a listing this build cannot **read** is an `Error`, because unlike the network it
 * will not heal, and every future join would pay the full re-upload.
 */
class ShareSetLoad(
    private val listing: DeviceListing,
    private val reset: UploadRowsReset,
    private val log: Logger = Logger.withTag("ShareSetLoad"),
) {
    suspend fun load(eventId: EventId) {
        when (val read = withTimeoutOrNull(LISTING_TIMEOUT_MS) { listing.stored(eventId) }) {
            is ListingRead.Listed -> seeded(eventId, read)
            is ListingRead.Unavailable -> kept(eventId).also {
                log.w { "device listing fetch failed — this membership keeps only the rows it already had (${read.detail})" }
            }
            is ListingRead.Unreadable -> kept(eventId).also {
                log.e {
                    "device listing was not understood — keeping only the rows this event already had; this device " +
                        "re-uploads what the backend already holds, and every join will until this is fixed (${read.detail})"
                }
            }
            null -> kept(eventId).also {
                log.w { "device listing timed out — this membership keeps only the rows it already had" }
            }
        }
    }

    private fun seeded(eventId: EventId, read: ListingRead.Listed) {
        when (val written = reset.seed(eventId, read.stored)) {
            StoreWrite.Written -> log.i { "loaded the share set — ${read.stored.size} stored resource(s) seeded COMPLETED" }
            is StoreWrite.Failed -> log.e { "could not reset the upload ledger at the join (${written.detail})" }
        }
    }

    private fun kept(eventId: EventId) = when (val written = reset.purgeExcept(eventId)) {
        StoreWrite.Written -> Unit
        is StoreWrite.Failed -> log.e { "could not purge the other events' upload rows at the join (${written.detail})" }
    }
}
