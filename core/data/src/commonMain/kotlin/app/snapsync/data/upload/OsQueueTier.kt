package app.snapsync.data.upload

import app.snapsync.data.crypto.UploadSeal
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.SealVerdict
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.UploadTarget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * **The OS's own upload queue as an upload tier** ([OsUploadQueue] — PhotoKit's, in the iOS ≥ 26.1 upload extension):
 * a row's upload made a job sending the library's own bytes, and an offered retry re-pointed at a freshly built
 * request. The queue takes no file, so an encrypted event's upload is sealed by the edge from the one file's key its
 * headers carry. How presented jobs are read and recorded is [OsQueueDrain]'s.
 */
class OsQueueTier(
    private val queue: OsUploadQueue,
    private val requests: UploadRequests,
    private val sealing: UploadSealing,
    private val network: MobileDataStore,
    private val record: JobRecord,
) : UploadTier {
    /**
     * Make [row]'s upload into [event] a job sending [handle]'s bytes, and record its row requested once it exists. How
     * its event seals it is asked first: an encrypted event's request carries the photo's name sealed with it.
     */
    override suspend fun create(event: EventId, row: UploadRow.Discovered, from: SendFrom): CreateAnswer =
        when (val seal = sealing.sealFor(event, row.key.asset, row.key.role)) {
            is UploadSeal.Goes -> created(event, row, from, seal)
            is UploadSeal.Withheld -> CreateAnswer.Withheld(seal.because)
        }

    private suspend fun created(
        event: EventId,
        row: UploadRow.Discovered,
        from: SendFrom,
        seal: UploadSeal.Goes,
    ): CreateAnswer =
        when (val built = requests.first(event, row.key, row.detail, seal)) {
            is RequestBuilt.Built -> made(event, row.key, from.handle, built.request, target(built.request, seal))
            RequestBuilt.NoIdentity -> CreateAnswer.Unaddressed("no device id is held here")
            is RequestBuilt.IdentityUnreadable -> CreateAnswer.Unaddressed(built.detail)
            is RequestBuilt.Unverified -> CreateAnswer.Unverified(built.detail)
        }

    /**
     * Re-point the retry the queue [offered] at a freshly built request — the same destination, the token from its
     * store of record. The row stays requested whatever the answer: the queue still holds the job, and one it refuses
     * comes back as a terminal failure through the drain.
     */
    suspend fun retry(event: EventId, offered: OfferedRetry): RetryAnswer =
        when (val seal = sealing.sealFor(event, offered.row.key.asset, offered.row.key.role)) {
            is UploadSeal.Goes -> repointed(event, offered, seal)
            is UploadSeal.Withheld -> RetryAnswer.Withheld(seal.because)
        }

    private suspend fun repointed(event: EventId, offered: OfferedRetry, seal: UploadSeal.Goes): RetryAnswer =
        when (val built = requests.retry(event, offered.row.key, offered.row.detail, seal)) {
            is RequestBuilt.Built -> repointed(queue.retry(offered.job, target(built.request, seal)))
            RequestBuilt.NoIdentity -> RetryAnswer.Unaddressed("no device id is held here")
            is RequestBuilt.IdentityUnreadable -> RetryAnswer.Unaddressed(built.detail)
            is RequestBuilt.Unverified -> RetryAnswer.Unverified(built.detail)
        }

    /** Where [request] goes, with the headers the event's seal adds: the edge seals the library's own bytes. */
    private fun target(request: BuiltRequest, seal: UploadSeal.Goes): UploadTarget =
        UploadTarget(request.url, request.headers + sealHeaders(seal), network.transferNetwork())

    private fun sealHeaders(seal: UploadSeal.Goes): Map<String, String> = when (seal) {
        UploadSeal.Plain -> emptyMap()
        is UploadSeal.Sealed -> sealing.edgeSeal(seal.order).headers()
    }

    /** The job made, and its row recorded requested in the same step — a cancellation cannot split the two. */
    private suspend fun made(
        event: EventId,
        key: UploadKey,
        handle: GalleryResourceHandle,
        request: BuiltRequest,
        target: UploadTarget,
    ): CreateAnswer = withContext(NonCancellable) {
        record.created(event, key, queue.create(handle, target), request.destination)
    }

    private fun repointed(answer: ChangeOutcome): RetryAnswer = when (answer) {
        ChangeOutcome.Applied -> RetryAnswer.Repointed
        is ChangeOutcome.Refused -> RetryAnswer.Refused("${answer.code} ${answer.detail}")
    }
}

/** What re-pointing an offered retry came to. The row stays requested in every case: the queue still holds the job. */
sealed interface RetryAnswer {
    data object Repointed : RetryAnswer

    /** The queue refused the re-point; the job comes back as a terminal failure. [detail] is diagnostics only. */
    data class Refused(val detail: String) : RetryAnswer

    /** The event's key cannot seal it now: the offered retry is left as it is, and offered again. */
    class Withheld(val because: SealVerdict.Withheld.Because) : RetryAnswer

    /** Nothing can be addressed to this device now. */
    data class Unaddressed(val detail: String) : RetryAnswer

    /**
     * This phone holds no proof it is genuine: the offered retry is not re-pointed — the queue keeps it, and offers it
     * again once the phone is verified. [detail] is diagnostics only.
     */
    data class Unverified(val detail: String) : RetryAnswer
}
