package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.upload.CountRead
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.ListingRead
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowTransition
import app.snapsync.data.upload.RowTransitions
import app.snapsync.domain.event.EventId
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.withTimeoutOrNull

private val log = Log("SettleStoredUploads")

/** Upper bound on the listing — the same as the join-time load's: it bounds a stuck call, not a slow one. */
private const val LISTING_TIMEOUT_MS = 15_000L

/**
 * **Settle the in-flight rows whose bytes the backend already stores** — at the app's return to the foreground. Bytes
 * can land long before the OS acknowledges their job, and sometimes the acknowledgement never reaches the ledger (an
 * extension withheld after a downgrade to a partial grant is presented nothing): the backend is the one party that
 * knows. Only a requested row is settled, by the compare-and-set the platform's callbacks make too — so it needs no
 * lock — and a failure leaves nothing behind: the next return asks again.
 */
class SettleStoredUploads(
    private val rows: OwnUploadRows,
    private val listing: DeviceListing,
    private val transitions: RowTransitions,
) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId?) = when (event) {
        null -> Unit
        is EventId -> joined(event)
    }

    private suspend fun joined(event: EventId) = when (val inFlight = rows.inFlight(event)) {
        is CountRead.Counted -> if (inFlight.count > 0) listed(event) else Unit
        is CountRead.Unavailable -> log.w(
            "the upload rows could not be read — nothing settled",
            "detail" of inFlight.detail,
        )
    }

    private suspend fun listed(event: EventId) = when (
        val read = withTimeoutOrNull(LISTING_TIMEOUT_MS) {
            listing.stored(event)
        }
    ) {
        is ListingRead.Listed -> settled(event, read)
        is ListingRead.Unavailable -> log.w(
            "the device listing could not be read — nothing settled",
            "detail" of read.detail,
        )
        is ListingRead.Unreadable -> log.e(
            "the device listing was not understood — nothing settled",
            "detail" of read.detail,
        )
        null -> log.w("the device listing timed out — nothing settled")
    }

    private suspend fun settled(event: EventId, read: ListingRead.Listed) {
        val settled = read.stored.count { transitions.listed(event, it) == RowTransition.Transitioned }
        log.i("in-flight rows the backend already stores settled", "rows" of settled)
    }
}
