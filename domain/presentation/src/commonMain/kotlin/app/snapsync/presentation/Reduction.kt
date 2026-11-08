package app.snapsync.presentation

import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.time.Timestamp
import app.snapsync.presentation.JoinedLayer.shown
import app.snapsync.presentation.JoinedLayer.syncCounts
import app.snapsync.presentation.RenameRequests.renameOf
import app.snapsync.presentation.ShownFacts.eventTiming
import app.snapsync.presentation.StatusHealth.joinedHealth
import app.snapsync.presentation.UnjoinedLayer.creatingOf
import app.snapsync.query.membership.MembershipShown
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.PendingSwitch
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth

/** What the joined layer is reduced from beside the observed sources: the gate, the surfaces and the screen's cells. */
internal class JoinedInputs(
    val pending: PendingJoin?,
    val rename: RenameStatus,
    val reconfiguring: SettingsShown,
    val local: Local,
)

object Reduction {
    /**
     * Which of the worlds the app is in — unjoined (the join gate or the create surface) or joined — and, for the
     * joined layer, which health rung. Config presence is the top rung: without a connected event there is nothing to
     * share. Once config is present the screen is ALWAYS the joined layer — permission and sync activity are moods of
     * the one-line status, never a hero-replacing gate.
     */
    internal fun reduceFrom(
        observed: Observed,
        interaction: Interaction,
        local: Local,
        nowCutoff: Timestamp,
        display: Display,
    ): Reduced {
        // Whether this device is in an event is not known: no screen drawn from either answer would be true.
        if (observed.membership == MembershipShown.Unreadable) {
            return Reduced(Layer.MembershipUnreadable(local.retryingRead))
        }
        val joined = observed.joined
        val network = observed.network.notice
        val (pending, creation, renameStatus) = interaction
        val transient = local.transientError
        val form = local.form
        // Surface state belongs to the membership it was opened in; another membership reads it as closed / idle.
        val joinedId = joined?.let { it.event.id }
        val rename = Owned(local.renameOwner, renameOf(renameStatus, local.renameRequest))
            .forMembership(joinedId, RenameStatus.Idle)
        val reconfiguring = SettingsShown(
            surface = local.settings.forMembership(joinedId, SettingsSurface.Closed),
            askingToStopSharing = local.pendingWithdrawal.forMembership(joinedId, null) != null,
        )
        if (joined == null) {
            val create = Creation(creatingOf(creation, local.createRequest), display.create(local.create))
            return UnjoinedLayer.of(
                pending,
                create,
                transient,
                Choices(form, local.picking),
                observed.permission,
                network,
                observed.refusal,
                observed.attestFault,
                display,
            )
        }
        return joinedFrom(observed, joined, JoinedInputs(pending, rename, reconfiguring, local), nowCutoff, display)
    }

    /** The joined layer for [joined]: its health rung, and what it carries beside it. */
    private fun joinedFrom(
        observed: Observed,
        joined: Membership,
        shown: JoinedInputs,
        nowCutoff: Timestamp,
        display: Display,
    ): Reduced {
        val local = shown.local
        val network = observed.network.notice
        val health = joinedHealth(observed, joined, network, nowCutoff, local.keyPresence == KeyPresence.Lost)
        // A pending join for a DIFFERENT event while joined is a switch confirmation over the joined screen.
        val pendingSwitch = shown.pending?.let { PendingSwitch(it.eventId.value, it.phase.shows, it.service?.notice) }
        // Where the event is in its life, for the dates line. Informational only —
        // the health above is unchanged by it, and sync continues after the end in the backend grace window.
        val timing = eventTiming(joined.event.range.start, joined.event.range.end, nowCutoff)
        val counts = countsLine(observed, health, joined)
        return JoinedLayer.of(
            joined, health, pendingSwitch, observed.permission,
            JoinedFacts(timing, counts, display.today(), display.joinableUntil(joined, nowCutoff)),
            shown.rename, shown.reconfiguring, local.transientError, Choices(local.form, local.picking), display,
            observed.invite,
        )
    }

    /**
     * The counts line, once the counts are read. On a live event it rides ONLY on the two health rungs that read the
     * numbers; every rung above them means the numbers are unknown (not read yet), zero (no access collapses the
     * gallery
     * total) or beside the point (not started, unverified) — and the one status line is what the member should read. A
     * deleted event's counts are what it came to, so they follow the snapshot whatever the line says.
     */
    private fun countsLine(observed: Observed, health: SyncHealth, joined: Membership): SyncCounts? =
        (observed.sync as? SharingProgressRead.Read)
            ?.takeIf { observed.download.read && countsShown(health, joined) }
            ?.let { syncCounts(it.progress, observed.download, joined.share.direction) }

    /**
     * Whether the counts line shows beside [health]: always for a deleted event, else only beside the counted rungs.
     */
    private fun countsShown(health: SyncHealth, joined: Membership): Boolean =
        joined is Membership.Deleted || health is SyncHealth.InSync || health is SyncHealth.Syncing
}
