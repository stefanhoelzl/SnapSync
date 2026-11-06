package app.snapsync.presentation

import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.time.Timestamp
import app.snapsync.ui.state.PendingSwitch
import app.snapsync.ui.state.SyncHealth

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
    val (joined, permission, snapshot, download, attested, access) = observed
    val network = access.notice
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
        return unjoinedLayer(
            pending,
            create,
            transient,
            Choices(form, local.picking),
            permission,
            network,
            observed.refusal,
            observed.attestFault,
            display,
        )
    }
    val health = joinedHealth(observed, joined, network, nowCutoff, local.keyPresence == KeyPresence.Lost)
    // A pending join for a DIFFERENT event while joined is a switch confirmation over the joined screen.
    val pendingSwitch = pending?.let { PendingSwitch(it.eventId.value, it.phase.shows) }
    // Where the event is in its life, for the dates line. Informational only —
    // the health above is unchanged by it, and sync continues after the end in the backend grace window.
    val timing = eventTiming(joined.event.range.start, joined.event.range.end, nowCutoff)
    // The counts line rides ONLY on the two health rungs that read the numbers; every rung above them
    // means the numbers are unknown (not read yet), zero (no access collapses the gallery total) or beside
    // the point (not started, unverified) — and the one status line is what the member should read.
    val counts = (snapshot as? SharingProgressRead.Read)
        ?.takeIf { health is SyncHealth.InSync || health is SyncHealth.Syncing }
        ?.let { syncCounts(it.progress, download, joined.share.direction) }
    return joinedLayer(
        joined, health, pendingSwitch, permission,
        JoinedFacts(timing, counts, display.today(), display.joinableUntil(joined, nowCutoff)),
        rename, reconfiguring, transient, Choices(form, local.picking), display, observed.invite,
    )
}
