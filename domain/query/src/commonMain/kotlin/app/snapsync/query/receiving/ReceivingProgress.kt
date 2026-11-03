package app.snapsync.query.receiving

import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.DownloadCounts
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.AwaitedPhotos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The joined screen's download progress: `downloaded` — the joined event's received photos,
 * `total` — every foreign photo known for it that can still arrive, `inFlight` — those with a transfer under way, and
 * `awaited` — what the others declared beyond them ([AwaitedPhotos]). Scoped to the joined event: the store keeps
 * every past event's received rows as suppression handles, and unscoped "received" was every photo ever received.
 *
 * Seeded [DownloadProgress.UNREAD]: before the first [refresh] nothing has been read, and saying so stops the download
 * arrow from hiding — and the whole screen from settling — over counts nobody took. A failed read keeps the last good
 * value: it is still the most recent thing read, and never regresses to a placeholder or to a counted zero.
 */
class ReceivingProgress(private val counts: ReceivingCounts, private val membership: MembershipRepository) {
    private val latest = MutableStateFlow(DownloadProgress.UNREAD)

    val progress: StateFlow<DownloadProgress> get() = latest.asStateFlow()

    /** Read the counts again, in ONE statement, so the published progress can never be a torn composite. */
    suspend fun refresh() = when (val read = membership.state.value) {
        is MembershipRead.Joined -> counted(read.membership)
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> Unit
    }

    private suspend fun counted(joined: Membership) = when (val read = counts.counts(joined.event.id)) {
        is DownloadRead.Ok -> latest.value = progressOf(read.value, receives(joined))
        is DownloadRead.Unavailable -> Unit
    }

    private fun receives(joined: Membership): Boolean = when (joined) {
        is Membership.Active -> joined.share.direction.includesDownload
        is Membership.Deleted -> false
    }

    private fun progressOf(counts: DownloadCounts, receives: Boolean) = DownloadProgress(
        downloaded = counts.imported,
        total = counts.stillArriving,
        inFlight = counts.inFlight,
        awaited = AwaitedPhotos.of(receives, counts.othersDeclared, counts.known),
    )
}

/**
 * The download side's counts as the joined screen reads them. [read] is `false` only for [UNREAD] — nothing has been
 * read yet, which holds the health at loading — never for a counted zero.
 */
data class DownloadProgress(
    val downloaded: Int,
    val total: Int,
    val inFlight: Int = 0,
    val awaited: Int = 0,
    val read: Boolean = true,
) {
    companion object {
        val UNREAD = DownloadProgress(downloaded = 0, total = 0, inFlight = 0, awaited = 0, read = false)
    }
}
