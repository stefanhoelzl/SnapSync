package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.upload.OwnPhotoResources
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowsRead
import app.snapsync.data.upload.SendFrom
import app.snapsync.data.upload.WalkedResource
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.domain.upload.WalkDiff
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("WalkOwnPhotos")

/**
 * **Walk the member's own photos** and decide what the walk writes ([WalkDiff]) — a read, writing nothing: a walk the
 * operating system cuts short records nothing it saw and retracts nothing it missed. The photos are read the way the
 * grant allows — the library within the capture range, or the selection in hand — and admitted by the membership's one
 * policy; resources are read only for the admitted photos the ledger does not fully know. A read that could not be
 * made admits nothing and is evidence of nothing.
 */
class WalkOwnPhotos(
    private val photos: OwnPhotos,
    private val rows: OwnUploadRows,
    private val resources: OwnPhotoResources,
) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, contribution: Contribution): WalkOutcome =
        when (val read = rows.rows(event)) {
            is RowsRead.Read -> walked(read.rows, contribution, photos.discover(contribution))
            is RowsRead.Unavailable -> WalkOutcome.NOTHING.also {
                log.w("the upload rows could not be read — nothing walked", "detail" of read.detail)
            }
        }

    private suspend fun walked(rows: List<UploadRow>, contribution: Contribution, read: DiscoveryRead): WalkOutcome =
        when (read) {
            is DiscoveryRead.Discovered -> discovered(rows, contribution, read)
            DiscoveryRead.NotReadable -> WalkOutcome(
                WalkDiff.of(rows, emptyList(), emptySet(), emptySet()),
                emptyMap(),
                SettlingLook.CouldNotLook,
            )
        }

    private suspend fun discovered(
        rows: List<UploadRow>,
        contribution: Contribution,
        read: DiscoveryRead.Discovered,
    ): WalkOutcome {
        val admitted = read.discovery.admitted
        val known = WalkDiff.known(rows)
        val toRead = admitted.filter { it.facts.assetId !in known }
        val walked = resources.of(read, toRead)
        val window = contribution.policy.admitRecorded(rows.map { it.recorded }).mapTo(mutableSetOf()) { it.assetId }
        val ids: Set<AssetId> = admitted.mapTo(mutableSetOf()) { it.facts.assetId }
        log.i(
            "walked the member's photos",
            "considered" of read.discovery.considered,
            "admitted" of admitted.size,
            "read" of toRead.size,
            "resources" of walked.size,
        )
        return WalkOutcome(
            WalkDiff.of(rows, walked.map { it.found }, ids, window),
            walked.handles(),
            SettlingLook.Looked(read.discovery),
        )
    }

    private fun List<WalkedResource>.handles(): Map<UploadKey, SendFrom> =
        associate { it.found.key to it.from }
}

/**
 * What a walk came to: what it writes ([diff]), and the handles of the resources it read — a top-up right after sends
 * from them. A walk that could not read the rows walked nothing ([NOTHING]): it writes nothing and adds nothing.
 */
class WalkOutcome(
    val diff: WalkDiff,
    val handles: Map<UploadKey, SendFrom>,
    /** What the walk was as a look at the member's photos — what a publish after it may settle on. */
    val look: SettlingLook,
) {
    companion object {
        val NOTHING = WalkOutcome(
            WalkDiff.of(emptyList(), emptyList(), emptySet(), emptySet()),
            emptyMap(),
            SettlingLook.CouldNotLook,
        )
    }
}
