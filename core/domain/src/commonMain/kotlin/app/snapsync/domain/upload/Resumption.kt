package app.snapsync.domain.upload

/**
 * Where a failed upload's file goes on from, by the backend's word (`docs/architecture.md`, "Resumable uploads"):
 * asked before the same file is sent again, so a retry continues where the last attempt stopped rather than at byte 0.
 */
sealed interface ResumePoint {
    /** The backend already holds these bytes whole — the last attempt's answer was lost on the way back. */
    data object Landed : ResumePoint

    /** Send from byte [offset]: `0` sends the file whole. */
    data class From(val offset: Long) : ResumePoint

    /** The backend could not be asked: create nothing now. */
    data object Deferred : ResumePoint
}

/** Which byte a staged file's transfer starts at, or that it starts not now. */
sealed interface TransferStart {
    data class At(val offset: Long) : TransferStart

    /** Where the bytes stand could not be learned: no transfer now, the next pass asks again. */
    data object NotNow : TransferStart

    companion object {
        /**
         * The transfer of a staged file of [size] bytes the backend says stands at [point]: from where its bytes
         * stopped; from its last byte when it all landed — one byte resent lands the transfer like any other, and the
         * platform reports the landing the ledger waits for; or none now.
         */
        fun of(point: ResumePoint, size: Long): TransferStart = when (point) {
            ResumePoint.Landed -> At(size - 1)
            is ResumePoint.From -> At(point.offset)
            ResumePoint.Deferred -> NotNow
        }
    }
}

/** How a transfer of the app's own session ended, as its delegate reports it. */
enum class TransferEnd { LANDED, FAILED }

/**
 * Whether a staged upload file — the exported (and, for an encrypted event, sealed) bytes a session transfer sends —
 * stays on disk, with the digest that lets a failed attempt resume beside it.
 */
enum class StagedFile {
    KEPT,
    RELEASED,
    ;

    companion object {
        /** After its transfer [ended]: a failure's file stays, so the next attempt sends the same bytes on. */
        fun after(ended: TransferEnd): StagedFile = when (ended) {
            TransferEnd.LANDED -> RELEASED
            TransferEnd.FAILED -> KEPT
        }

        /**
         * In a sweep of the files no live transfer sends — what an export left when no transfer outlived it: one whose
         * [row] still needs its upload is a failed attempt's, kept for the next to resume; every other is released.
         * Releasing loses nothing: a transfer's bytes come from the photo library, and a started one exports again.
         */
        fun unclaimed(row: UploadRow?): StagedFile = when (row) {
            is UploadRow.Discovered, is UploadRow.Requested -> KEPT
            is UploadRow.Completed, null -> RELEASED
        }
    }
}
