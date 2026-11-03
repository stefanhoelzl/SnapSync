package app.snapsync.data.upload

import app.snapsync.data.crypto.UploadSeal
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource

/**
 * **The app's own transfer session as an upload tier** ([TransferSession] — a background `URLSession`, Android's
 * uploads): a row's upload made a transfer, its row recorded requested once the session accepted it. A session that
 * takes resources streams a plain one directly; every other upload — always a sealed one — is sent from its staged
 * file ([FileUploads]). How each transfer ends is [SessionEnds]'.
 *
 * A session offers no retries: a failure returns the row to needing a job, and the next top-up creates it again.
 */
class SessionTier(
    private val session: TransferSession,
    private val requests: UploadRequests,
    private val sealing: UploadSealing,
    private val create: SessionCreate,
    private val fromFiles: FileUploads,
) : UploadTier {
    /** Make [row]'s upload into [event] a transfer of [handle]'s bytes, and record its row requested once it exists. */
    override suspend fun create(event: EventId, row: UploadRow.Discovered, from: SendFrom): CreateAnswer =
        when (val built = requests.first(event, row.key, row.detail)) {
            is RequestBuilt.Built -> sealed(SessionUpload(event, row.key, from.handle, built.request))
            RequestBuilt.NoIdentity -> CreateAnswer.Unaddressed("no device id is held here")
            is RequestBuilt.IdentityUnreadable -> CreateAnswer.Unaddressed(built.detail)
        }

    private suspend fun sealed(upload: SessionUpload): CreateAnswer =
        when (val seal = sealing.sealFor(upload.event, upload.key.asset, upload.key.role)) {
            UploadSeal.Plain -> plain(upload)
            is UploadSeal.Sealed -> fromFiles.sent(upload, seal.order)
            is UploadSeal.Withheld -> CreateAnswer.Withheld(seal.because)
        }

    private suspend fun plain(upload: SessionUpload): CreateAnswer = when (session.takesResources) {
        true -> create.made(upload, TransferSource.Resource(upload.handle), upload.request.headers).answer
        false -> fromFiles.sent(upload, order = null)
    }
}
