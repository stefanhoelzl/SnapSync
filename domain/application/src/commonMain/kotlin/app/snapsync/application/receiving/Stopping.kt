package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ReceivingCleanup
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.Membership
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

/** Why receiving may be torn down — each case carries what makes the irreversible cleanup safe. */
sealed interface StopReason {
    /** The member left: the leave's transaction landed. */
    class Left(val evidence: LeftMembership) : StopReason

    /** The event's photos are deleted: the membership stays, shown as over, and receives nothing more. */
    class Deleted(val membership: Membership.Deleted) : StopReason

    /** The store holds no membership — a leave that failed half-way left these behind. */
    class NotJoined(val evidence: ConfirmedNotJoined) : StopReason

    /** The member's settings no longer receive. */
    class SharesOnly(val membership: Membership.Active) : StopReason
}

/**
 * Stop receiving: cancel every transfer — this process's and any a relaunched process inherited — and drop every photo
 * not yet received with its staged bytes. Received photos persist: each is a suppression handle. An import already
 * claimed is NOT cancelled: its transaction may still commit, so its row is spared, settles when the library reports,
 * and stays a handle — a stop does not fully clean, and that is correct.
 */
class StopReceiving(private val cleanup: ReceivingCleanup) {
    private val log = Log("StopReceiving")

    @EntryPointCall
    suspend operator fun invoke(
        @Suppress(
            "UNUSED_PARAMETER",
        ) because: StopReason,
    ) = when (val stopped = cleanup.stopReceiving()) {
        is DownloadRead.Ok -> log.i("receiving stopped", "freed" of stopped.value)
        is DownloadRead.Unavailable -> log.w("receiving could not be stopped", "why" of stopped.detail)
    }
}
