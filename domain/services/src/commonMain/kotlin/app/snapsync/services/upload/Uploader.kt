package app.snapsync.services.upload

import app.snapsync.model.EntryScope
import app.snapsync.model.Resource
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadFailure
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSession
import app.snapsync.services.crypto.UploadSeal
import app.snapsync.services.crypto.UploadSealing
import app.snapsync.services.gallery.UploadDiscovery
import co.touchlab.kermit.Logger

/**
 * Which of the device's uploaders a cycle sends through — the OS's job queue in the upload extension, the app's own
 * session in the app — and the [BackgroundTransfer] that drives it ([transferFor]).
 */
sealed interface Uploader {
    /** The cycle's transfer over this uploader, with [setup]'s collaborators. */
    fun transferFor(setup: TransferSetup): BackgroundTransfer

    /** The OS's upload-job queue: PhotoKit's, in the upload extension. */
    class Queue(private val queue: OsUploadQueue) : Uploader {
        override fun transferFor(setup: TransferSetup): BackgroundTransfer = OsQueueTransfer(queue, setup)
    }

    /** The app's background transfer session; [resuming] makes its file uploads resume a failed attempt. */
    class Session(private val session: TransferSession, private val resuming: Resuming? = null) : Uploader {
        override fun transferFor(setup: TransferSetup): BackgroundTransfer = SessionTransfer(session, setup, resuming)
    }
}

/** What a cycle's transfer works with, whichever uploader it drives. */
class TransferSetup(
    val record: TransferRecord,
    /** Where a failed job's still-live resource is found for its re-creation: the cycle's own by-key resolve. */
    val resources: UploadDiscovery,
    /** The photo library, which exports a resource to a file for an uploader that sends files. */
    val gallery: GalleryReader,
    /** The shared area a file uploader's exported bytes wait in ([UPLOAD_STAGING_DIR]). */
    val files: Files,
    /**
     * The networks an upload made or re-pointed NOW may use — read at that moment, so a change of the member's choice
     * governs the uploads that start after it, never the ones already handed to the platform.
     */
    val network: () -> TransferNetwork,
    /**
     * How each upload is sealed (the encrypted file format, `docs/architecture.md`): not at all in a plain event; in an
     * encrypted one, sealed into the staged file where the uploader sends files, else by the edge from the one file's
     * key the headers carry. `null` where nothing is ever sealed.
     */
    val sealing: UploadSealing? = null,
    val log: Logger = Logger.withTag("UploadTransfer"),
    val entryContext: EntryScope = EntryScope.None,
) {
    /** How [resource] goes up: plainly where nothing is ever sealed, else as [sealing] decides for the joined event. */
    internal suspend fun sealOf(resource: Resource): UploadSeal =
        if (sealing == null) UploadSeal.Plain else sealing.sealFor(resource)
}

/** What the platform's create answer means to the cycle: the same outcome. */
internal fun CreateOutcome.uploadCreateOutcome(): UploadCreateOutcome = when (this) {
    CreateOutcome.CREATED -> UploadCreateOutcome.CREATED
    CreateOutcome.LIMIT_EXCEEDED -> UploadCreateOutcome.LIMIT_EXCEEDED
    CreateOutcome.FAILED -> UploadCreateOutcome.FAILED
}

/** What the platform said of a failed transfer, as the cycle carries it: the same words. */
internal fun TransferFailure.uploadFailure(): UploadFailure = UploadFailure(detail)
