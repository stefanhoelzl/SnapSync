package app.snapsync.presentation

import app.snapsync.domain.membership.Membership
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.time.Timestamp
import app.snapsync.presentation.JoinGateRules.settled
import app.snapsync.presentation.JoinedLayer.shown
import app.snapsync.presentation.Reduction.reduceFrom
import app.snapsync.presentation.StatusHealth.arrowOf
import app.snapsync.presentation.StatusHealth.syncHealth
import app.snapsync.presentation.Switches.mobileData
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SyncHealth

/** What the ladder reads: the observed sources, the membership, the network's notice, "now" and the snapshot's word. */
internal class StatusFacts(
    val observed: Observed,
    val joined: Membership,
    val network: NetworkNotice?,
    val now: Timestamp,
    val keyLost: Boolean,
    /** What the snapshot says on its own: not read yet, settled, or work remaining — the ladder's last rung. */
    val settled: SyncHealth,
)

/** One rung of the health ladder: its line, or `null` when it does not apply and the next rung is asked. */
internal interface Rung {
    fun health(facts: StatusFacts): SyncHealth?
}

/** The rungs in order: the first that applies is the line, and [last] — which always answers — when none does. */
internal class StatusLadder(private val rungs: List<Rung>, private val last: LastRung) {
    fun of(facts: StatusFacts): SyncHealth = rungs.firstNotNullOfOrNull { it.health(facts) } ?: last.health(facts)
}

/** The ladder's bottom rung: it always answers. */
internal interface LastRung {
    fun health(facts: StatusFacts): SyncHealth
}

/**
 * Neither shares nor receives: once the snapshot is read and nothing is left in either direction, that is the one
 * thing worth saying — access, the network, the start and verification all concern photos that no longer move. Work
 * still draining in a switched-off direction is shown by the ladder below, never masked (the reason above
 * [syncHealth]).
 */
private object NothingMoves : Rung {
    override fun health(facts: StatusFacts): SyncHealth? =
        if (facts.joined.share.direction != Direction.Neither) null else nothingMoving(facts.settled)

    private fun nothingMoving(settled: SyncHealth): SyncHealth? = when (settled) {
        SyncHealth.InSync -> SyncHealth.Inactive
        SyncHealth.Loading -> SyncHealth.Loading
        else -> null
    }
}

/**
 * The device lost the event's key: nothing moves in either direction, and only the event's invite — from someone in
 * the group — brings it back, so no rung below says anything truer.
 */
private object KeyLost : Rung {
    override fun health(facts: StatusFacts): SyncHealth? = SyncHealth.KeyLost.takeIf { facts.keyLost }
}

/**
 * Missing permission is the sole attention state — the only reason contribution cannot run. It outranks NotStarted
 * because it is the only ACTIONABLE state, and the member must resolve it BEFORE the event begins or they miss the
 * start; hiding it behind the clock line would ambush them with a permission prompt at the very moment the party
 * starts. LIMITED is NOT here: a partial grant is a working state — it falls through to the snapshot-derived health
 * exactly like a full one.
 */
private object NeedsAccess : Rung {
    override fun health(facts: StatusFacts): SyncHealth? {
        val permission = facts.observed.permission
        return if (permission.readsPhotos) null else SyncHealth.NeedsAccess(canAsk = permission == PhotoGrant.UNDECIDED)
    }
}

/**
 * No usable network. Below access, which decides what is shared and can be fixed offline; above everything else —
 * before the start, received photos cannot arrive either, and a missing network is the true cause of most failed
 * verifications, so it outranks the vaguer cannot-verify line by rank alone.
 */
private object NoNetwork : Rung {
    override fun health(facts: StatusFacts): SyncHealth? = facts.network?.let { SyncHealth.NoNetwork(it) }
}

/**
 * The event has not begun. Outranks every snapshot-derived value because nothing of this member's CAN be syncing yet —
 * the cutoff floor guarantees it (`minPhotoDate >= startsAt > now`, and a photo cannot be captured in the future) — so
 * a snapshot line would say nothing true that this does not say better.
 */
private object NotStarted : Rung {
    override fun health(facts: StatusFacts): SyncHealth? =
        SyncHealth.NotStarted.takeIf { facts.joined.event.range.start.at > facts.now }
}

/**
 * Uploads are gated on an attestation token (change `withhold-unverified-uploads`), and we could not get one. Ranked
 * BELOW permission and BELOW NotStarted: with no library access — or before the event begins — nothing of this
 * member's can upload anyway, so an unusable token is not yet their problem, and two attention lines would only
 * compete. Ranked ABOVE the sync progress, because "Syncing" would be a lie: nothing can upload at all.
 *
 * No proof, because SnapSync failed when asked for one: its condition is told, never that this device cannot be
 * verified — a refusal, or a proof this phone could not give, keeps the [Unattested] line.
 */
private object VerificationFailing : Rung {
    override fun health(facts: StatusFacts): SyncHealth? {
        val observed = facts.observed
        if (observed.attested || observed.refusal != null) return null
        return observed.attestFault?.let { SyncHealth.ServiceFailing(it.notice) }
    }
}

/** Not verified: a definite refusal names its cause on the same rung; no verdict names none. */
private object Unattested : Rung {
    override fun health(facts: StatusFacts): SyncHealth? =
        if (facts.observed.attested) null else SyncHealth.Unattested(facts.observed.refusal?.cause)
}

/**
 * Photos wait and SnapSync is failing: the line says which way and that they are safe. Never over "Up to date": with
 * nothing waiting, an outage is no one's concern.
 */
private object ServiceFailing : Rung {
    override fun health(facts: StatusFacts): SyncHealth? {
        val fault = facts.observed.serviceFault?.takeIf { facts.settled is SyncHealth.Syncing }
        return fault?.let { SyncHealth.ServiceFailing(it.notice) }
    }
}

/** What the snapshot says on its own: the bottom of the ladder, which always answers. */
private object Settled : LastRung {
    override fun health(facts: StatusFacts): SyncHealth = facts.settled
}

/** The ladder, in the order the status line ranks its lines. */
private val HEALTH_LADDER = StatusLadder(
    listOf(NothingMoves, KeyLost, NeedsAccess, NoNetwork, NotStarted, VerificationFailing, Unattested, ServiceFailing),
    last = Settled,
)

// Shown tracks completeness (never lies about "everything up/received"); pulse tracks live activity
// (never fakes motion). Each arrow derives from ITS OWN COUNTS ALONE — this function does not read the
// membership's direction, and never force-hides: an opted-out direction contributes no work and so has a
// zero total — the upload total is 0 for a non-contributing membership, and the download total is 0 for a
// membership that never reconciles — so the arrows agree with the direction because the counts already do.
//
// A mask would be harmful, not merely redundant. A force-hidden arrow can only ever conceal a MISMATCH between the
// direction contract and what the system is actually doing. Concealing that mismatch is exactly how a download-only
// membership uploaded its member's camera roll for a full release cycle while this screen read "In sync": the one
// surface that would have shown them an upload they never asked for was the surface that hid it. If the counts are
// right, the arrow is already right; if they are wrong, an arrow the member never asked for is the only signal anyone
// gets. The display must not assert a contract the system is not keeping.

object StatusHealth {
    /**
     * The joined layer's one status line, in a fixed priority: the rungs in order ([HEALTH_LADDER]), over what the
     * snapshot says on its own. Its own function because the ladder is one question, apart from which layer
     * [reduceFrom] builds.
     */
    internal fun joinedHealth(
        observed: Observed,
        joined: Membership,
        network: NetworkNotice?,
        nowCutoff: Timestamp,
        keyLost: Boolean,
    ): SyncHealth = HEALTH_LADDER.of(
        StatusFacts(observed, joined, network, nowCutoff, keyLost, settledHealth(observed)),
    )

    /**
     * What the snapshot says on its own: not read yet, settled, or work remaining.
     *
     * The download arm has its OWN read-ness, and it must gate the health too. [syncHealth] hides an arrow when its
     * counts
     * are complete, and shows "Up to date" only when BOTH arrows are hidden — so an un-read [DownloadProgress], whose
     * `downloaded` and `total` are both a placeholder zero, hides the download arrow and can carry the whole screen to
     * a
     * settled check mark on its own. Gating the upload side alone would relocate that defect rather than remove it: the
     * next member to join an event with foreign photos outstanding would meet it through this arm on their first launch
     * (`SNAPSYNC-14`, `SNAPSYNC-16`).
     */
    private fun settledHealth(observed: Observed): SyncHealth = when (val snapshot = observed.sync) {
        // Joined but persisted state not read yet — a neutral first frame (the joined chrome still shows).
        SharingProgressRead.NotYetRead -> SyncHealth.Loading
        is SharingProgressRead.Read -> readHealth(snapshot.progress, observed)
    }

    /**
     * The read snapshot's health, once the downloads are read too. Photos kept off mobile data wait while the phone is
     * on
     * a network the choice avoids: from the device's CURRENT choice, so after turning mobile data back on the few
     * transfers
     * still holding the old rule read as pending, not waiting (decision record
     * `changes/archive/2026-10-04-mobile-data-for-photos`, D7).
     */
    private fun readHealth(progress: SharingProgress, observed: Observed): SyncHealth {
        if (!observed.download.read) return SyncHealth.Loading
        val heldForWifi = !observed.mobileData && observed.network == NetworkAccess.Online(restricted = true)
        return syncHealth(progress, observed.download, heldForWifi)
    }

    internal fun syncHealth(progress: SharingProgress, download: DownloadProgress, heldForWifi: Boolean): SyncHealth {
        val upload = uploadArrow(progress, heldForWifi)
        val downloadArrow = downloadArrow(download, heldForWifi)
        if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) return SyncHealth.InSync
        // Only the others left outranks waiting for Wi-Fi: nothing of this device's own is held, so Wi-Fi would move
        // nothing.
        val others = onlyOthersLeft(upload, downloadArrow, download)
        return SyncHealth.Syncing(
            upload,
            downloadArrow,
            waitingForWifi = heldForWifi && !others,
            waitingForOthers = others,
        )
    }

    /** A held transfer is handed to the platform and so counts as in flight, but it is not RUNNING: no arrow pulses. */
    private fun uploadArrow(progress: SharingProgress, heldForWifi: Boolean): Arrow =
        arrowOf(shown = progress.shared < progress.total, pulsing = progress.handedOver > 0 && !heldForWifi)

    /**
     * The others' declared photos still uploading on their phones count as left to receive (change
     * `count-declared-photos`): they raise the arrow, never its pulse — nothing of theirs runs here.
     */
    private fun downloadArrow(download: DownloadProgress, heldForWifi: Boolean): Arrow = arrowOf(
        shown = download.downloaded < download.total + download.awaited,
        pulsing = download.inFlight > 0 && !heldForWifi,
    )

    /** Only the others are left: nothing of this device's own runs, waits or is offered and not yet here. */
    private fun onlyOthersLeft(upload: Arrow, downloadArrow: Arrow, download: DownloadProgress) =
        upload == Arrow.HIDDEN && downloadArrow == Arrow.STATIC && download.downloaded >= download.total

    internal fun arrowOf(shown: Boolean, pulsing: Boolean): Arrow =
        if (!shown) Arrow.HIDDEN else if (pulsing) Arrow.PULSING else Arrow.STATIC
}
