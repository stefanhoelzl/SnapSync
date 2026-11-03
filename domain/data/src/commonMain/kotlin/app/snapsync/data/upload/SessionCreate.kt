package app.snapsync.data.upload

import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.UploadKeyText
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * **A session transfer made, and its row recorded requested, in one step** a cancellation cannot split: a transfer the
 * session made is recorded before the pass can end, or the next top-up would make it again. It goes over the network
 * the member's mobile-data choice allows, read at each create.
 */
class SessionCreate(
    private val session: TransferSession,
    private val network: MobileDataStore,
    private val record: JobRecord,
) {
    /** [upload] sent from [source] with [headers]. */
    suspend fun made(upload: SessionUpload, source: TransferSource, headers: Map<String, String>): Made =
        withContext(NonCancellable) {
            val target = UploadTarget(upload.request.url, headers, network.transferNetwork())
            val outcome = session.create(source, target, upload.tag)
            Made(outcome, record.created(upload.event, upload.key, outcome, upload.request.destination))
        }
}

/** What the session answered a create, and what recording that answer came to. */
class Made(val outcome: CreateOutcome, val answer: CreateAnswer)

/** One row's upload as the session tier makes it: into [event], [key]'s bytes from [handle], as [request] says. */
class SessionUpload(
    val event: EventId,
    val key: UploadKey,
    val handle: GalleryResourceHandle,
    val request: BuiltRequest,
) {
    /** The tag its transfer carries — its key, so the session's report names the row. */
    val tag: String = UploadKeyText.of(key)
}
