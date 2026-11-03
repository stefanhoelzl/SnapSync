package app.snapsync.domain.upload

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.RecordedAsset
import kotlin.jvm.JvmInline

/** Only the upload ledger's read mints an [UploadRow]: a row is what the ledger holds, never what a caller says. */
@RequiresOptIn("Only the upload ledger's read says what a row holds.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsUploadRow

/**
 * What the device manifest says of a resource beside its key: when its photo was captured, its content type and its
 * original file name — written when the walk first recorded it, never by a later transition. A row the join-time load
 * seeded from a stored-file listing starts [bare]: the listing names no capture date, and the next walk fills it.
 */
class RowDetail(val captured: CaptureDate, val contentType: String, val originalFilename: String) {
    /** Whether this detail still waits for the walk to fill it — a seeded row's. */
    val bare: Boolean get() = captured == CaptureDate.Undated
}

/**
 * One row of the upload ledger as it was read, by its state — the upload's lifecycle as types. A row needs a job
 * ([Discovered]), has one the platform accepted ([Requested]), or is settled ([Completed]); each transition between
 * them is the ledger's compare-and-set from the state its type names, and a [Requested] one is written only with the
 * platform's word that the job exists (`CreatedJob`) — never before, so no process can claim a row and then die
 * holding it (decision record `changes/archive/2026-09-22-both-uploaders-active`, D1–D2).
 *
 * The three states are the stored ones ([LedgerState]); a row of another event is never read.
 */
sealed class UploadRow(val key: UploadKey, val detail: RowDetail) {
    /** The row as the policy admits rows: its asset and when it was captured. */
    val recorded: RecordedAsset get() = RecordedAsset(key.asset, detail.captured)

    /** No job exists for it: never attempted, or a failure returned it here. */
    class Discovered @MintsUploadRow constructor(key: UploadKey, detail: RowDetail) : UploadRow(key, detail)

    /**
     * The platform accepted a job for it, addressed to [destination] — `null` for a row recorded before the ledger kept
     * the destination, which the transfer tiers resolve by key instead.
     */
    class Requested @MintsUploadRow constructor(key: UploadKey, detail: RowDetail, val destination: DestinationPath?) :
        UploadRow(key, detail)

    /** The platform, or the backend's listing, says the bytes landed: nothing is owed. */
    class Completed @MintsUploadRow constructor(key: UploadKey, detail: RowDetail) : UploadRow(key, detail)
}

/** The path a row's upload was addressed to, as the platform's job names it on its way back. */
@JvmInline
value class DestinationPath(val path: String)
