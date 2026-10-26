package app.snapsync.services.upload

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import kotlin.test.Test
import kotlin.test.assertIs

/** Which transfer each uploader drives. */
class UploaderTest {

    private object Queue : OsUploadQueue {
        override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget) = CreateOutcome.CREATED
        override suspend fun retryOffered(): List<OsUploadJob> = emptyList()
        override suspend fun terminals(): List<OsUploadJob> = emptyList()
        override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome = ChangeOutcome.Applied
        override suspend fun acknowledge(job: OsUploadJob): ChangeOutcome = ChangeOutcome.Applied
    }

    private object Session : TransferSession {
        override val takesResources = false

        @CompositionOnly
        override fun inbound(handlers: TransferHandlers) = Unit
        override suspend fun create(source: TransferSource, target: UploadTarget, tag: String) = CreateOutcome.CREATED
        override suspend fun live(): List<Transfer> = emptyList()
        override suspend fun cancel(transfer: Transfer) = Unit
    }

    @Test
    fun `the OS's queue is driven by the queue transfer and the app's session by the session transfer`() {
        assertIs<OsQueueTransfer>(Uploader.Queue(Queue).transferFor(setup()))
        assertIs<SessionTransfer>(Uploader.Session(Session).transferFor(setup()))
    }
}
