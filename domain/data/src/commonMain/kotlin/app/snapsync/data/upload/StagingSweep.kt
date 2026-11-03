package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.StagedFile
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.TransferSession

/**
 * **The sweep of staged files no transfer outlived** — what an export left when the process died between it and its
 * transfer's start, or when a transfer ended without this process being told. Nothing else ever reaches such a file. A
 * file whose row still needs its upload is a failed attempt's and stays to resume ([StagedFile.unclaimed]); every other
 * goes, and loses nothing: a transfer's bytes come from the photo library, and a transfer started again exports again.
 */
class StagingSweep(
    private val files: Files,
    private val staged: StagedFiles,
    private val rows: OwnUploadRows,
    private val session: TransferSession,
    private val fromFiles: FileUploads,
) {
    private val log = Log("StagingSweep")

    /**
     * Delete every staged file and digest no live transfer sends, as [event]'s rows allow — while no staged file is
     * being exported or handed to the session, so a file about to be sent is never taken for unclaimed.
     */
    suspend fun releaseUnclaimed(event: EventId) =
        fromFiles.locked { sweep(event, session.live().mapNotNullTo(HashSet()) { it.tag }) }

    private suspend fun sweep(event: EventId, sending: Set<String>) =
        when (val listed = files.list(FileArea.SHARED, UPLOAD_STAGING_DIR)) {
            is FileResult.Ok -> swept(event, listed.value - claimed(sending))
            is FileFailure ->
                log.w("the upload staging directory could not be read — nothing released", "read" of listed)
        }

    private suspend fun swept(event: EventId, unclaimed: List<String>) = when (val read = rows.rows(event)) {
        is RowsRead.Read -> {
            val byKey = read.rows.associateBy { UploadKeyText.of(it.key) }
            unclaimed.filter { StagedFile.unclaimed(byKey[staged.keyOf(it)]) == StagedFile.RELEASED }.forEach(
                staged::delete,
            )
        }
        is RowsRead.Unavailable ->
            log.w("the upload rows could not be read — no staged file released", "detail" of read.detail)
    }

    private fun claimed(sending: Set<String>): Set<String> =
        sending.flatMapTo(HashSet()) { listOf(staged.path(it), staged.digestPath(it)) }
}
