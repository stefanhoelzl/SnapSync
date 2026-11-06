package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetId

/**
 * How far the member's own photos are shared, as the joined screen counts them: [shared] of [total], and [handedOver]
 * — those whose upload is under way, never more than what is left. The total is the membership's admitted photos and
 * nothing else, so a photo the upload would never send is never counted, and a row of a photo no longer admitted (a
 * raised cutoff, a deleted photo) counts for nothing.
 */
data class SharingProgress(val shared: Int, val total: Int, val handedOver: Int) {
    companion object {
        /** The progress of [admitted], given which photos the upload rows hold [done] and which [underWay]. */
        fun of(admitted: Set<AssetId>, done: Set<AssetId>, underWay: Set<AssetId>): SharingProgress {
            val shared = admitted.count { it in done }
            val left = admitted.size - shared
            return SharingProgress(shared, admitted.size, minOf(admitted.count { it in underWay }, left))
        }
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
