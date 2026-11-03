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
    /** Make [row]'s upload into [event] a job sending [handle]'s bytes, and record its row requested once it exists. */
    override suspend fun create(event: EventId, row: UploadRow.Discovered, from: SendFrom): CreateAnswer =
        when (val built = requests.first(event, row.key, row.detail)) {
            is RequestBuilt.Built -> when (val target = target(event, row.key, built.request)) {
                is Target.To -> made(event, row.key, from.handle, built.request, target.target)
                is Target.Withheld -> CreateAnswer.Withheld(target.because)
            }
            RequestBuilt.NoIdentity -> CreateAnswer.Unaddressed("no device id is held here")
            is RequestBuilt.IdentityUnreadable -> CreateAnswer.Unaddressed(built.detail)
        }

    /**
     * Re-point the retry the queue [offered] at a freshly built request — the same destination, the token from its
     * store of record. The row stays requested whatever the answer: the queue still holds the job, and one it refuses
     * comes back as a terminal failure through the drain.
     */
    suspend fun retry(event: EventId, offered: OfferedRetry): RetryAnswer =
        when (val built = requests.retry(event, offered.row.key, offered.row.detail)) {
            is RequestBuilt.Built -> when (val target = target(event, offered.row.key, built.request)) {
                is Target.To -> repointed(queue.retry(offered.job, target.target))
                is Target.Withheld -> RetryAnswer.Withheld(target.because)
            }
            RequestBuilt.NoIdentity -> RetryAnswer.Unaddressed("no device id is held here")
            is RequestBuilt.IdentityUnreadable -> RetryAnswer.Unaddressed(built.detail)
        }

    /** Where [key]'s upload into [event] goes, with the headers its event's seal adds — or that it may not go now. */
    private fun target(event: EventId, key: UploadKey, request: BuiltRequest): Target =
        when (val seal = sealing.sealFor(event, key.asset, key.role)) {
            UploadSeal.Plain -> Target.To(UploadTarget(request.url, request.headers, network.transferNetwork()))
            is UploadSeal.Sealed -> Target.To(
                UploadTarget(
                    request.url,
                    request.headers + sealing.edgeSeal(seal.order).headers(),
                    network.transferNetwork(),
                ),
            )
            is UploadSeal.Withheld -> Target.Withheld(seal.because)
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

    /** Where an upload goes, or that its event's seal withholds it. */
    private sealed interface Target {
        class To(val target: UploadTarget) : Target

        class Withheld(val because: SealVerdict.Withheld.Because) : Target
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
}
