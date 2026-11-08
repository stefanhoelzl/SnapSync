package app.snapsync.query.receiving

import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.DownloadCounts
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.store.StoreChanges
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.receiving.AwaitedPhotos
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlin.time.TimeSource

private val log = Log("ReceivingProgress")

/**
 * The joined screen's download progress: `downloaded` — the joined event's received photos, `total` — every foreign
 * photo known for it that can still arrive, `inFlight` — those with a transfer under way, and `awaited` — what the
 * others declared beyond them ([AwaitedPhotos]). Scoped to the joined event: the store keeps every past event's
 * received rows as suppression handles, and unscoped "received" was every photo ever received.
 *
 * Re-read in ONE statement, so it is never a torn composite, each time the download store changes or the membership
 * does — only while the app is in front ([AppForeground.whileInFront]) and a screen follows it. Seeded
 * [DownloadProgress.UNREAD]: before the first read nothing has been read, and saying so stops the download arrow from
 * hiding — and the whole screen from settling — over counts nobody took. A failed read keeps the last good value: it is
 * still the most recent thing read, and never regresses to a placeholder or to a counted zero.
 */
class ReceivingProgress(
    private val counts: ReceivingCounts,
    private val membership: MembershipRepository,
    foreground: AppForeground,
    private val changes: StoreChanges,
    scope: CoroutineScope,
) {
    val progress: StateFlow<DownloadProgress> = foreground.whileInFront(looks())
        .scan(DownloadProgress.UNREAD) { last, read -> read ?: last }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(), DownloadProgress.UNREAD)

    /** One read per change; deferred, since the membership's first read opens the shared database. */
    private fun looks(): Flow<DownloadProgress?> = flow {
        emitAll(combine(membership.state, changes.looks) { held, _ -> held }.map(::read))
    }

    /** The counts now, or `null` when there are none to read or the store refused: the last read stands. */
    private suspend fun read(held: MembershipRead): DownloadProgress? = when (held) {
        is MembershipRead.Joined -> counted(held.membership)
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
    }

    /** The counts read, their time logged: how long a commit's re-read takes of the lane. */
    private suspend fun counted(joined: Membership): DownloadProgress? {
        val started = TimeSource.Monotonic.markNow()
        val progress = when (val read = counts.counts(joined.event.id)) {
            is DownloadRead.Ok -> progressOf(read.value, receives(joined))
            is DownloadRead.Unavailable -> null
        }
        val millis = started.elapsedNow().inWholeMilliseconds
        log.i("download counts read", "millis" of millis, "answered" of (progress != null))
        return progress
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
