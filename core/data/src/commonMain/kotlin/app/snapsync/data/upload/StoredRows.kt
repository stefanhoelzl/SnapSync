package app.snapsync.data.upload

import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.MintsUploadRow
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.TimestampText
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

/**
 * A stored `ledgerRow` read as an [UploadRow] — the one mapper every read of the upload's rows uses, so a column is
 * read in one place. A row whose key spells none is not a row of this device's uploads: it maps to none, logged, never
 * thrown on, and the next join's purge removes it. The key holds the asset; the `assetId` column is its index.
 */
internal class StoredRows {
    private val log = Log("OwnUploadRows")

    /** A stored row as the mapper read it: the row, or none when its [key] spells none. */
    class Mapped(val key: String, val row: UploadRow?)

    /** The rows [mapped] holds — each one whose key spells none left out, and logged. */
    fun rows(mapped: List<Mapped>): List<UploadRow> {
        mapped.filter { it.row == null }.forEach { unreadable(it.key) }
        return mapped.mapNotNull(Mapped::row)
    }

    @Suppress("LongParameterList", "UnusedParameter") // the statement's columns, in order; the key holds the asset
    fun stored(
        key: String,
        assetId: Stored<AssetId>,
        state: LedgerState,
        creationDate: String,
        role: String,
        contentType: String,
        originalFilename: String,
        destinationPath: String?,
    ): Mapped {
        val detail = RowDetail(TimestampText.captureDate(creationDate), contentType, originalFilename)
        return Mapped(key, UploadKeyText.parse(key)?.let { row(it, state, detail, destinationPath) })
    }

    @OptIn(MintsUploadRow::class)
    private fun row(key: UploadKey, state: LedgerState, detail: RowDetail, destinationPath: String?): UploadRow =
        when (state) {
            LedgerState.DISCOVERED -> UploadRow.Discovered(key, detail)
            LedgerState.REQUESTED -> UploadRow.Requested(key, detail, destinationPath?.let(::DestinationPath))
            LedgerState.COMPLETED -> UploadRow.Completed(key, detail)
        }

    private fun unreadable(key: String) =
        log.unlabelled.w("a ledger row's key spells no upload key — left out", "key" of key)
}
