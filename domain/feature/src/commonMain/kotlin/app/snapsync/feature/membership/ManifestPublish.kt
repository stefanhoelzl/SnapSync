package app.snapsync.feature.membership

import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowsRead
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.TimestampText
import app.snapsync.format.UploadKeyText
import app.snapsync.model.LedgerEntry
import app.snapsync.services.membership.MembershipProjection
import co.touchlab.kermit.Logger

/**
 * **Publish this device's manifest** from its upload rows — the one step a pass's walk ends with, and the step phase
 * 4.4c replaces with its own (MIGRATING: the manifest still speaks the old zones' `LedgerEntry`). The manifest
 * DECLARES what this device will provide: every row the membership's policy admits, whatever its upload state, with
 * the [version] the pass read first. Settled once the membership's own range has ended — one fresh read, like the
 * gate's. A publish that throws is contained at the pass's `publish` step; the next pass republishes.
 */
class ManifestPublish(
    private val rows: OwnUploadRows,
    private val producer: Lazy<DeviceManifestProducer>,
    private val config: MembershipProjection,
    private val log: Logger = Logger.withTag("ManifestPublish"),
) {
    /** Publish what [event]'s rows declare under [policy], carrying [version]. */
    suspend fun publish(event: EventId, policy: SelectionPolicy, version: Long) {
        when (val read = rows.rows(event)) {
            is RowsRead.Read -> published(event, read.rows, policy, version)
            is RowsRead.Unavailable -> log.w { "the upload rows could not be read — no manifest this pass (${read.detail})" }
        }
    }

    private suspend fun published(event: EventId, rows: List<UploadRow>, policy: SelectionPolicy, version: Long) {
        val admitted = policy.admitRecorded(rows.map { it.recorded }).mapTo(mutableSetOf()) { it.assetId }
        producer.value.produce(event, rows.map(::entry), admitted, version, config.freshReadShareHasEnded())
    }

    /** [row] as the manifest still reads a row. */
    private fun entry(row: UploadRow): LedgerEntry = LedgerEntry(
        key = UploadKeyText.of(row.key),
        assetId = row.key.asset,
        state = stateOf(row),
        creationDate = TimestampText.captureText(row.detail.captured),
        role = row.key.role,
        contentType = row.detail.contentType,
        originalFilename = row.detail.originalFilename,
    )

    private fun stateOf(row: UploadRow) = when (row) {
        is UploadRow.Discovered -> LedgerState.DISCOVERED
        is UploadRow.Requested -> LedgerState.REQUESTED
        is UploadRow.Completed -> LedgerState.COMPLETED
    }
}
