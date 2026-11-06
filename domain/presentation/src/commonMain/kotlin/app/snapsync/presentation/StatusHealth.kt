package app.snapsync.presentation

import app.snapsync.domain.membership.Membership
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.time.Timestamp
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SyncHealth

/**
 * The joined layer's one status line, in a fixed priority: the rungs in order, over what the snapshot says on its
 * own. Its own function because the ladder is one question, apart from which
 * layer [reduceFrom] builds.
 */
internal fun joinedHealth(
    observed: Observed,
    joined: Membership,
    network: NetworkNotice?,
    nowCutoff: Timestamp,
    keyLost: Boolean,
): SyncHealth {
    val (_, permission, snapshot, download, attested, access) = observed
    // What the snapshot says on its own: not read yet, settled, or work remaining. The bottom of the ladder below,
    // and what decides whether a membership that neither shares nor receives may say so.
    val settled = when (snapshot) {
        // Joined but persisted state not read yet — a neutral first frame (the joined chrome still shows).
        SharingProgressRead.NotYetRead -> SyncHealth.Loading
        // The download arm has its OWN read-ness, and it must gate the health too. `syncHealth` below
        // hides an arrow when its counts are complete, and shows "Up to date" only when BOTH arrows are
        // hidden — so an un-read DownloadProgress, whose `downloaded` and `total` are both a placeholder
        // zero, hides the download arrow and can carry the whole screen to a settled check mark on its
        // own. Gating the upload side alone would relocate that defect rather than remove it: the next
        // member to join an event with foreign photos outstanding would meet it through this arm on
        // their first launch (`SNAPSYNC-14`, `SNAPSYNC-16`).
        //
        // Photos kept off mobile data wait while the phone is on a network the choice avoids: from the device's
        // CURRENT choice, so after turning mobile data back on the few transfers still holding the old rule read as
        // pending, not waiting (decision record `changes/archive/2026-10-04-mobile-data-for-photos`, D7).
        is SharingProgressRead.Read -> if (!download.read) {
            SyncHealth.Loading
        } else {
            syncHealth(
                snapshot.progress,
                download,
                heldForWifi = !observed.mobileData && access == NetworkAccess.Online(restricted = true),
            )
        }
    }
    return when {
        // Neither shares nor receives: once the snapshot is read and nothing is left in
        // either direction, that is the one thing worth saying — access, the network, the start and verification
        // all concern photos that no longer move. Work still draining in a switched-off direction is shown by the
        // ladder below, never masked (the reason above `syncHealth`).
        joined.share.direction == Direction.Neither && settled == SyncHealth.InSync -> SyncHealth.Inactive
        joined.share.direction == Direction.Neither && settled == SyncHealth.Loading -> SyncHealth.Loading
        // The device lost the event's key: nothing moves in either direction, and only the
        // event's invite — from someone in the group — brings it back, so no rung below says anything truer.
        keyLost -> SyncHealth.KeyLost
        // Missing permission is the sole attention state — the only reason contribution cannot run. It
        // outranks NotStarted because it is the only ACTIONABLE state, and the member must resolve it
        // BEFORE the event begins or they miss the start; hiding it behind the clock line would ambush
        // them with a permission prompt at the very moment the party starts. LIMITED is NOT here: a
        // partial grant is a working state — it falls through to
        // the snapshot-derived health exactly like GRANTED.
        !permission.readsPhotos -> SyncHealth.NeedsAccess(canAsk = permission == PhotoGrant.UNDECIDED)
        // No usable network. Below access, which
        // decides what is shared and can be fixed offline; above everything else — before the start, received photos
        // cannot arrive either, and a missing network is the true cause of most failed verifications, so it outranks
        // the vaguer cannot-verify line by rank alone.
        network != null -> SyncHealth.NoNetwork(network)
        // The event has not begun. Outranks every snapshot-derived value because nothing of this member's
        // CAN be syncing yet — the cutoff floor guarantees it (`minPhotoDate >= startsAt > now`, and a
        // photo cannot be captured in the future) — so a snapshot line would say nothing true that this
        // does not say better. Canonical fixed-width UTC on both sides ⇒ lexicographic IS chronological.
        joined.event.range.start.at > nowCutoff -> SyncHealth.NotStarted
        // Uploads are gated on an attestation token (change `withhold-unverified-uploads`), and we could not get one.
        // Ranked BELOW permission and BELOW NotStarted for the same reason: with no library access — or before the
        // event begins — nothing of this member's can upload anyway, so an unusable token is not yet their problem,
        // and two attention lines would only compete. Ranked ABOVE the sync progress, because "Syncing" would be a
        // lie: nothing can upload at all.
        // A definite refusal names its cause on the same rung; no verdict names none.
        // No proof, because SnapSync failed when asked for one: its condition is told, never that this device
        // cannot be verified — a refusal, or a proof this phone could not give, keeps the line above.
        !attested && observed.refusal == null && observed.attestFault != null ->
            SyncHealth.ServiceFailing(observed.attestFault.notice)
        !attested -> SyncHealth.Unattested(observed.refusal?.cause)
        // Photos wait and SnapSync is failing: the line says which way and that they are safe. Never over
        // "Up to date": with nothing waiting, an outage is no one's concern.
        observed.serviceFault != null && settled is SyncHealth.Syncing ->
            SyncHealth.ServiceFailing(observed.serviceFault.notice)
        else -> settled
    }
}

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
internal fun syncHealth(progress: SharingProgress, download: DownloadProgress, heldForWifi: Boolean): SyncHealth {
    // A held transfer is handed to the platform and so counts as in flight, but it is not RUNNING: no arrow pulses.
    val upload = arrowOf(shown = progress.shared < progress.total, pulsing = progress.handedOver > 0 && !heldForWifi)
    // The others' declared photos still uploading on their phones count as left to receive (change
    // `count-declared-photos`): they raise the arrow, never its pulse — nothing of theirs runs here.
    val downloadArrow = arrowOf(
        shown = download.downloaded < download.total + download.awaited,
        pulsing = download.inFlight > 0 && !heldForWifi,
    )
    return if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) {
        SyncHealth.InSync
    } else {
        SyncHealth.Syncing(
            upload = upload,
            download = downloadArrow,
            waitingForWifi = heldForWifi,
            // Only the others are left: nothing of this device's own runs, waits or is offered and not yet here.
            waitingForOthers = !heldForWifi && upload == Arrow.HIDDEN && downloadArrow == Arrow.STATIC &&
                download.downloaded >= download.total,
        )
    }
}

internal fun arrowOf(shown: Boolean, pulsing: Boolean): Arrow =
    if (!shown) Arrow.HIDDEN else if (pulsing) Arrow.PULSING else Arrow.STATIC
