package app.snapsync.presentation

import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.query.receiving.DownloadProgress
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.PendingSwitch
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * The joined layer itself, once the precedence table above has decided the health.
 *
 * Its own function because it answers a different question: `reduceFrom` decides WHICH layer and, for
 * this one, which health rung; this assembles what that layer carries.
 */
@Suppress("LongParameterList")
internal fun joinedLayer(
    joined: Membership,
    health: SyncHealth,
    pendingSwitch: PendingSwitch?,
    permission: PhotoGrant,
    facts: JoinedFacts,
    rename: RenameStatus,
    reconfiguring: SettingsShown,
    transient: ScreenMessage?,
    choices: Choices,
    display: Display,
    invite: String?,
): Reduced {
    val range = settingsRange(joined, reconfiguring, choices.form, display)
    val layer = Layer.Joined(
        membership = display.event(joined),
        // Derived once, in the query (`EventKeyQuery.invite`), and read HERE only (decision record `event-invite-qr`, D3's
        // surviving half): one derivation feeds both the rendered QR and the share action, so they cannot drift.
        inviteUrl = invite,
        health = health,
        today = facts.today,
        pendingSwitch = pendingSwitch,
        // The resting affordance, not an attention state: a
        // partial grant's joined layer always offers the picker, whatever the health.
        canChoosePhotos = permission == PhotoGrant.LIMITED,
        timing = facts.timing,
        counts = facts.counts,
        deleted = joined is Membership.Deleted,
        members = (joined as? Membership.Active)?.let { it.event.members }?.active,
        sharingNote = sharingNote(joined, health, facts.timing),
        joinableUntil = facts.joinableUntil,
        renameState = rename.toRenameState(),
        // The same transient cell the create layer's banner reads. A rejected link is rejected wherever
        // it arrives, so the message reaches whichever layer is showing rather than only one of them.
        notice = transient,
        surface = if (range != null) surfaceOf(range, permission, choices, reconfiguring) else JoinedSurface.Status,
    )
    return Reduced(layer, range)
}

/**
 * The settings surface's range, resolved against the MEMBERSHIP's own window while it is open, else `null`. A deleted
 * event has no settings any more: a surface left open when the deletion is learned gives way to the status.
 */
private fun settingsRange(joined: Membership, shown: SettingsShown, form: RangeForm, display: Display): ResolvedRange? =
    joined.takeIf { shown.surface != SettingsSurface.Closed && it is Membership.Active }
        ?.let { display.resolve(form, it.event.range.start, it.event.range.end) }

/** The settings surface over the joined screen, pre-filled from the choices. */
private fun surfaceOf(range: ResolvedRange, permission: PhotoGrant, choices: Choices, shown: SettingsShown) =
    JoinedSurface.Reconfigure(
        range = choices.form.view(range, permission, choices.picking),
        saveFailed = shown.surface == SettingsSurface.SaveFailed,
        askingToStopSharing = shown.askingToStopSharing,
    )

/**
 * Only once the range has ended and this member is in sync is "who are we waiting for" the member's question. Not a
 * one-way latch: a late guest or new photos turn "everyone has shared" back into waiting.
 */
private fun sharingNote(joined: Membership, health: SyncHealth, timing: EventTiming): SharingNote? =
    (joined as? Membership.Active)?.let { it.event.members }
        ?.takeIf { timing == EventTiming.Ended && settledForOthers(health) }
        ?.let(::noteOf)

/** Who the event still waits for, or that everyone has shared. */
private fun noteOf(members: MemberCounts): SharingNote =
    if (members.waitingFor > 0) SharingNote.WaitingFor(members.waitingFor) else SharingNote.EveryoneShared

/** In sync, or only the others left: this member has nothing more to do. */
private fun settledForOthers(health: SyncHealth): Boolean =
    health == SyncHealth.InSync || (health as? SyncHealth.Syncing)?.waitingForOthers == true

/** What the joined layer says beside its health: the dates line's timing, the counts line, and the device's today. */
internal class JoinedFacts(
    val timing: EventTiming,
    val counts: SyncCounts?,
    val today: LocalDate,
    /** A back-dated event's open-until date while it has not passed — the joined screen's "guests can join until". */
    val joinableUntil: LocalDateTime? = null,
)

// The counts line, from the SAME pairs `syncHealth` derives the arrows from, so the numbers and the arrows
// cannot disagree. A direction reads `Off` only when the member switched it off AND it has no work: the
// reason is the one above `syncHealth` — a display that hides work in a switched-off direction conceals
// exactly the mismatch the member most needs to see.
internal fun syncCounts(progress: SharingProgress, download: DownloadProgress, direction: Direction): SyncCounts =
    SyncCounts(shared = sharedCount(progress, direction), received = receivedCount(download, direction))

private fun sharedCount(progress: SharingProgress, direction: Direction): DirectionCount =
    if (!direction.includesUpload && progress.total == 0) {
        DirectionCount.Off
    } else {
        DirectionCount.Progress(done = progress.shared, total = progress.total)
    }

/** What is left to receive includes what the others declared and have not uploaded yet. */
private fun receivedCount(download: DownloadProgress, direction: Direction): DirectionCount {
    val total = download.total + download.awaited
    return if (!direction.includesDownload && total == 0) {
        DirectionCount.Off
    } else {
        DirectionCount.Progress(done = minOf(download.downloaded, total), total = total)
    }
}

/**
 * The counts line as the screen showed it, for a bug report: `<done>/<total>` or `off`
 * per direction, taken from the reduced [UiState] itself rather than re-derived, because a report exists to catch the
 * screen disagreeing with the stores. Empty when no counts line was showing — not joined, or a status line in its place.
 */
internal fun shownCounts(state: UiState): Map<String, String> {
    val counts = (state.layer as? Layer.Joined)?.counts ?: return emptyMap()
    return mapOf(
        DiagnosticKeys.SHOWN_SHARED to counts.shared.shown(),
        DiagnosticKeys.SHOWN_RECEIVED to counts.received.shown(),
    )
}

internal fun DirectionCount.shown(): String = when (this) {
    DirectionCount.Off -> "off"
    is DirectionCount.Progress -> "$done/$total"
}

internal fun RenameStatus.toRenameState(): RenameState = when (this) {
    RenameStatus.Idle -> RenameState.Idle
    RenameStatus.InFlight -> RenameState.InFlight
    RenameStatus.Succeeded -> RenameState.Succeeded
    is RenameStatus.Failed -> RenameState.Failed(reason.message(fault))
}

/**
 * The rename dialog's failure. Two reasons, because the port reports two:
 * the backend rejected the name, or everything else.
 *
 * There is deliberately no "this event no longer exists" message for the `404` that also arrives as
 * [RenameFailureReason.SERVER]. A `404` here is a single witness that the event is gone, and the
 * self-leave needs two; giving it copy would give it a meaning, and a meaning
 * invites acting on it. The standing foreground refresh reaches that verdict on its own terms.
 */
internal fun RenameFailureReason.message(fault: ServiceFault?): ScreenMessage = when (this) {
    RenameFailureReason.INVALID_NAME -> ScreenMessage.RENAME_NAME_REFUSED
    // A failing service is told as what it is; an answer from a working one — a `404` among them — generically.
    RenameFailureReason.SERVER -> fault?.message ?: ScreenMessage.RENAME_FAILED
    RenameFailureReason.EVENT_OVER -> ScreenMessage.EVENT_OVER
}

/** Whether the joined layer shows its settings, and whether the last change to them failed to save. */
internal enum class SettingsSurface { Closed, Open, SaveFailed }

/** The settings as the reduction reads them: open or not (and failed), and whether a withdrawal is being asked about. */
internal data class SettingsShown(val surface: SettingsSurface, val askingToStopSharing: Boolean)
