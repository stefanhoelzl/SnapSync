package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.upload.AssetProgress

/**
 * How far the member's own photos are shared, as the joined screen counts them: [shared] of [total], and [handedOver]
 * — those a job carries, so their upload is under way; a photo waiting for a job is left, but nothing of it runs. The
 * total is the membership's admitted photos and nothing else, so a photo the upload would never send is never counted,
 * and a row of a photo no longer admitted (a raised cutoff, a deleted photo) counts for nothing.
 */
data class SharingProgress(val shared: Int, val total: Int, val handedOver: Int) {
    companion object {
        /** The progress of [admitted], given how far each photo the upload rows hold has got. */
        fun of(admitted: Set<AssetId>, photos: Map<AssetId, AssetProgress>): SharingProgress = SharingProgress(
            shared = admitted.count { photos[it] == AssetProgress.DONE },
            total = admitted.size,
            handedOver = admitted.count { photos[it] == AssetProgress.HANDED_OVER },
        )
    }
}

/**
 * The sharing progress as the screen reads it: [NotYetRead] until both the upload rows and the total have been read
 * since the app started — never a placeholder that would settle the screen on "Up to date" over nothing read — then
 * [Read], which never goes back to [NotYetRead].
 */
sealed interface SharingProgressRead {
    data object NotYetRead : SharingProgressRead

    data class Read(val progress: SharingProgress) : SharingProgressRead
}
