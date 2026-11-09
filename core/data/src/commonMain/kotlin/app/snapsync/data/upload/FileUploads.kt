package app.snapsync.data.upload

import app.snapsync.data.crypto.SealOrder
import app.snapsync.domain.upload.TransferStart
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.TransferSource
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **A session upload sent from its staged file** — exported (and sealed) afresh when no attempt left one, hashed, and
 * sent whole; or, when a failed attempt left it with its digest, sent on from where the backend says its bytes stopped
 * ([TransferStart]). A file that got no transfer goes again unless its digest lets the next attempt resume it.
 */
class FileUploads(
    private val staged: StagedFiles,
    private val digests: StagedDigests,
    private val export: UploadExport,
    private val standings: UploadStandings,
    private val create: SessionCreate,
) {
    private val log = Log("FileUploads")

    /**
     * Held from a file's export until its transfer exists (or the file is released), and by [locked] for the sweep — so
     * the sweep never takes an exported file whose transfer is about to be made for unclaimed. It also keeps a staged
     * file in place while the session is handed it: an iOS background session raises an exception no Kotlin catch
     * stops for a file it cannot read, and its adapter's check before is safe only because nothing deletes meanwhile.
     */
    private val staging = Mutex()

    /** [upload] sent from its staged file, sealed into it as [order] says, or plain for none. */
    suspend fun sent(upload: SessionUpload, order: SealOrder?): CreateAnswer = staging.withLock {
        when (val placed = staged.located(staged.path(upload.tag))) {
            Placement.Unplaced -> CreateAnswer.Failed("the shared area cannot hold the staged file")
            is Placement.At -> when (val digest = digests.kept(upload.tag)) {
                null -> fresh(upload, placed.path, order)
                is StagedDigest -> resumed(upload, placed.path, digest)
            }
        }
    }

    /** [work] while no staged file is being exported or handed to the session. */
    suspend fun <T> locked(work: suspend () -> T): T = staging.withLock { work() }

    private suspend fun fresh(upload: SessionUpload, path: String, order: SealOrder?): CreateAnswer =
        when (exported(upload, path, order)) {
            Exported.DONE -> sentFrom(upload, path, digests.digest(upload.tag), from = 0)
            Exported.FAILED -> CreateAnswer.Failed("the resource could not be staged")
        }

    private suspend fun exported(upload: SessionUpload, path: String, order: SealOrder?): Exported = when (order) {
        null -> export.plain(upload.tag, path, upload.handle)
        is SealOrder -> export.sealed(upload.tag, upload.handle, order)
    }

    private suspend fun resumed(upload: SessionUpload, path: String, digest: StagedDigest): CreateAnswer {
        val standing = standings.of(upload.event, upload.request.device, upload.key, digest.sha256)
        return when (val start = TransferStart.of(standing, digest.size)) {
            is TransferStart.At -> sentFrom(upload, path, digest, start.offset)
            TransferStart.NotNow -> CreateAnswer.NotNow
        }
    }

    private suspend fun sentFrom(upload: SessionUpload, path: String, digest: StagedDigest?, from: Long): CreateAnswer {
        resumes(upload, from)
        val headers = upload.request.headers + resumeHeaders(digest, from)
        val made = create.made(upload, TransferSource.File(path, from), headers)
        return when (made.outcome) {
            CreateOutcome.CREATED -> made.answer
            CreateOutcome.LIMIT_EXCEEDED, CreateOutcome.FAILED -> made.answer.also { unsent(upload, digest) }
        }
    }

    private suspend fun resumes(upload: SessionUpload, from: Long) = when (from > 0) {
        true -> log.i("a failed upload resumes", "key" of upload.tag, "from" of from)
        false -> Unit
    }

    /** A file with a digest stays for the next attempt, which resumes it; without one it would be exported again. */
    private fun unsent(upload: SessionUpload, digest: StagedDigest?) = when (digest) {
        null -> staged.release(upload.tag)
        is StagedDigest -> Unit
    }

    /** The headers a resumable upload carries: its file's SHA-256, and from a later byte the range it sends. */
    private fun resumeHeaders(digest: StagedDigest?, from: Long): Map<String, String> = when (digest) {
        null -> emptyMap()
        is StagedDigest -> mapOf(Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256 to digest.sha256) + range(digest, from)
    }

    private fun range(digest: StagedDigest, from: Long): Map<String, String> = when (from > 0) {
        true -> mapOf(Routes.UploadResource.HEADER_CONTENT_RANGE to "bytes $from-${digest.size - 1}/${digest.size}")
        false -> emptyMap()
    }
}
