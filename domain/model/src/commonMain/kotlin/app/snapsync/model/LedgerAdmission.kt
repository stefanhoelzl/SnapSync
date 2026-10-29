package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.RecordedAsset
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.format.TimestampText

/**
 * The assets a membership's [policy] **admits** among a set of ledger [rows] — the one translation every consumer of
 * the ledger takes to the policy's row admission ([SelectionPolicy.admitRecorded]): the device-manifest projection,
 * the upload cycle's enqueue and its departed window, and the album gather. MIGRATING: the ledger moves into the layers
 * with the uploads, and this translation with it.
 *
 * **A ledger row is not the admitted set.** It records that the policy admitted its asset *when the row was written*,
 * and a membership's policy changes under it — so it is asked again. Rows are grouped per asset first: several resources
 * of one photo share an `assetId` and stand or fall together. A row with no readable `creationDate` is undated, which
 * no range holds; the next walk that reaches it fills its date.
 */
fun admittedAssetIds(rows: Collection<LedgerEntry>, policy: SelectionPolicy): Set<AssetId> =
    policy.admitRecorded(
        rows.groupBy { it.assetId }.map { (assetId, group) ->
            RecordedAsset(assetId, TimestampText.captureDate(group.first().creationDate))
        },
    ).mapTo(mutableSetOf()) { it.assetId }
