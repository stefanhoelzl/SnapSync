package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.TransferEnd
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.UploadJobState

/**
 * **The jobs the OS's upload queue presents** — a terminal job recorded on its row where the queue presents it, and
 * acknowledged; an offered retry handed on with its row ([JobOwners]).
 *
 * **Every presented job is acknowledged**, whatever its record came to — one no row owns included — or PhotoKit
 * reports `appex failed to acknowledge jobs for processing state` (error 50008), discards the outstanding jobs and
 * defers the extension ~300 s (measured, iOS 26.6). Only a job whose row could not be READ is left unanswered:
 * acknowledging it would discard an outcome nothing has recorded, and the queue presents it again.
 */
class OsQueueDrain(
    private val queue: OsUploadQueue,
    private val owners: JobOwners,
    private val answers: JobAcknowledgement,
    private val record: JobRecord,
) {
    /** The retries the queue offers for [event]'s rows; one no row owns is acknowledged here, and not handed on. */
    suspend fun offered(event: EventId): List<OfferedRetry> = queue.retryOffered().mapNotNull { offered(event, it) }

    /**
     * Record every terminal job of [event]'s rows and acknowledge it: a landed one settles its row; a failed one
     * returns it to needing a job, and is handed on with the resource the queue still holds (none once retry-spent).
     */
    suspend fun drain(event: EventId): List<ReturnedJob> = queue.terminals().mapNotNull { drained(event, it) }

    /** Acknowledge [offered] without re-pointing it: the queue lets the job go. */
    suspend fun answer(offered: OfferedRetry) = answers.acknowledge(offered.job)

    private suspend fun offered(event: EventId, job: OsUploadJob): OfferedRetry? =
        when (val owner = owners.of(event, job)) {
            is JobOwner.Row -> OfferedRetry(job, owner.row)
            is JobOwner.None -> null.also { answers.ownerless(job, owner.why) }
            JobOwner.Unreadable -> null
        }

    private suspend fun drained(event: EventId, job: OsUploadJob): ReturnedJob? =
        when (val owner = owners.of(event, job)) {
            is JobOwner.Row -> acknowledged(event, job, owner.row.key)
            is JobOwner.None -> null.also { answers.ownerless(job, owner.why) }
            JobOwner.Unreadable -> null
        }

    private suspend fun acknowledged(event: EventId, job: OsUploadJob, key: UploadKey): ReturnedJob? {
        val returned = ended(event, job, key, endOf(job.state))
        answers.acknowledge(job)
        return returned
    }

    private fun ended(event: EventId, job: OsUploadJob, key: UploadKey, end: TransferEnd): ReturnedJob? = when (end) {
        TransferEnd.LANDED -> null.also { record.ended(event, key, end) }
        TransferEnd.FAILED -> ReturnedJob(key, record.ended(event, key, end), job.resource)
    }

    /** A terminal job's state as the transfer's end: only a success landed its bytes. */
    private fun endOf(state: UploadJobState): TransferEnd = when (state) {
        UploadJobState.SUCCEEDED -> TransferEnd.LANDED
        UploadJobState.FAILED, UploadJobState.CANCELLED, UploadJobState.PENDING, UploadJobState.REGISTERED,
        UploadJobState.UNKNOWN,
        -> TransferEnd.FAILED
    }
}

/** A retry the queue offers for a job of [row] — re-pointed by the tier, or answered by the drain. */
class OfferedRetry internal constructor(internal val job: OsUploadJob, val row: UploadRow)

/**
 * A terminal job whose upload failed, recorded and acknowledged: [recorded] is what the ledger made of it, [handle]
 * the resource the queue still holds — none for a retry-spent job, whose photo is looked up again to re-create it.
 */
class ReturnedJob internal constructor(
    val key: UploadKey,
    val recorded: RowTransition,
    val handle: GalleryResourceHandle?,
)
