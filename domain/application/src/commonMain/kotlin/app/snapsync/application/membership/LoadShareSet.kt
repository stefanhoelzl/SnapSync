package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.ListingRead
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.domain.event.EventId
import app.snapsync.inbound.Field
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Upper bound on the join-time listing. The join surface is waiting on it, and uploads are bounded by an
 * event window of at most 30 days, so the answer is small: phase 1 measured 1.67 s for 1,433 rows on an SE2
 * (`changes/archive/2026-09-21-always-full-enumerate`). The bound exists for a stuck call, not a slow one.
 */
private const val LISTING_TIMEOUT_MS = 15_000L

private val log = Log("LoadShareSet")

/**
 * **The join-time load** — a join loads the ledger from the per-device listing: at a provision into a **new**
 * membership — a first join or a switch, never a re-provision of the joined event — make the joined event's ledger rows
 * this membership's share set.
 *
 * Every ledger row belongs to one event and every read is scoped to the joined one (change `event-scoped-local-state`),
 * so rows of an earlier event are inert whatever happened to them; this load purges them as housekeeping. On a
 * confirmed listing the joined event's rows become exactly the device's stored resources IN THAT EVENT, one bare
 * `COMPLETED` row each, in one atomic seed — so nothing the backend already holds for this event is uploaded again.
 * Each event holds its own bytes (change `per-event-storage-layout`): a photo stored for another event is uploaded
 * again for this one. On a failed or timed-out listing the other events' rows are purged and the joined event's are
 * kept: empty for a first join, and for a rejoin what this device last knew of the event, whose bytes the backend still
 * holds while the event lives — except that a row in flight returns to needing a job (`UploadRowsReset.purgeExcept`).
 * The leave or switch before this join stopped every upload of the event, so nothing else would ever send it; should
 * an OS job have survived, the photo is uploaded twice, never seen as a second copy.
 *
 * **Why a failure blocks nothing.** There is no flag, no gate and no "load owed" bit. The only cost of a failed load
 * is that the walk records the window's photos as new work and uploads them again — idempotent overwrites of the same
 * `(eventId, deviceId, assetId, role)` objects, bounded by the event window. Retry state could strand a device behind
 * a gate; this cost cannot.
 *
 * The join it belongs to completes whatever the listing does: the answer is what the load came to. A transport
 * failure or a timeout is a warning; a listing this build cannot **read** is an error, because unlike the network it
 * will not heal, and every future join would pay the full re-upload.
 */
class LoadShareSet(private val listing: DeviceListing, private val reset: UploadRowsReset) {
    @EntryPointCall
    suspend operator fun invoke(eventId: EventId): ShareSetLoaded {
        val read = withTimeoutOrNull(LISTING_TIMEOUT_MS) { listing.stored(eventId) }
        told(read)
        return when (read) {
            is ListingRead.Listed -> seeded(eventId, read)
            is ListingRead.Unavailable, is ListingRead.Unreadable, null -> kept(eventId)
        }
    }

    /** A listing that could not be had is said, by how much it costs. */
    private suspend fun told(read: ListingRead?) = when (read) {
        is ListingRead.Listed -> Unit
        is ListingRead.Unavailable ->
            log.w("device listing failed — this membership keeps only the rows it already had", "detail" of read.detail)
        is ListingRead.Unreadable -> log.e(
            "device listing not understood — this device re-uploads what the backend already holds, and every join " +
                "will until this is fixed",
            "detail" of read.detail,
        )
        null -> log.w("device listing timed out — this membership keeps only the rows it already had")
    }

    private suspend fun seeded(eventId: EventId, read: ListingRead.Listed): ShareSetLoaded {
        val written = reset.seed(eventId, read.stored)
        return when (written) {
            StoreWrite.Written -> ShareSetLoaded.SEEDED
            is StoreWrite.Failed -> ShareSetLoaded.NOT_WRITTEN
        }.also { logged(it, "seed", "stored" of read.stored.size, written) }
    }

    private suspend fun kept(eventId: EventId): ShareSetLoaded {
        val written = reset.purgeExcept(eventId)
        return when (written) {
            StoreWrite.Written -> ShareSetLoaded.KEPT
            is StoreWrite.Failed -> ShareSetLoaded.NOT_WRITTEN
        }.also { logged(it, "purge", "kept" of eventId.value, written) }
    }

    /** A [write] the store refused is an error; one it made is said only when it seeded the share set. */
    private suspend fun logged(
        loaded: ShareSetLoaded,
        write: String,
        fact: Field,
        written: StoreWrite,
    ) = when (written) {
        StoreWrite.Written -> if (loaded == ShareSetLoaded.SEEDED) log.i("loaded the share set", fact) else Unit
        is StoreWrite.Failed ->
            log.e("could not write the upload ledger at the join", "write" of write, "detail" of written.detail)
    }
}

/**
 * What the join-time load came to: the joined event's rows [SEEDED] from the listing; [KEPT] as they were, the other
 * events' purged, when no listing could be read; or [NOT_WRITTEN], the store refusing the write.
 */
enum class ShareSetLoaded { SEEDED, KEPT, NOT_WRITTEN }
