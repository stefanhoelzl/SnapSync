package app.snapsync.presentation

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.query.ScreenLookup
import app.snapsync.query.membership.JoinDetails
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Overlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** What the join gate reads of the world: the membership, the grant, the key's presence and the phone's standing. */
internal class GateReads(
    val membership: StateFlow<Membership?>,
    val permission: StateFlow<PhotoGrant>,
    val keyPresence: StateFlow<KeyPresence>,
    val refusal: StateFlow<DeviceRefusal?>,
    val attestFault: StateFlow<ServiceFault?>,
)

/**
 * **The join gate**: an opened invite, the event it names loaded, a first join confirmed or
 * a switch weighed, and what each answered. Nothing a link says joins without a tap.
 *
 * The gate's async work runs INLINE within the container's intents (not on a side scope) so each pending transition
 * reduces through the container's own pipeline deterministically. A modal join is fine to serialize; a real fetch
 * suspends here, yielding a Loading frame before the result.
 */
internal class JoinGate(
    private val cells: ScreenCells,
    private val reads: GateReads,
    private val ranges: RangeResolver,
    private val commands: UserCommands,
    private val requests: RequestStatusFlows,
    private val joinDetails: ScreenLookup<InviteLink, JoinDetails>,
    private val scope: CoroutineScope,
) {
    private val local = cells.local
    private val pending = cells.pending
    private val runner = cells.runner
    private val log = cells.log
    private var maintenanceReload: Job? = null

    /**
     * An invite the app was opened with, read at the boundary (`LinkReading`): what is no invite flashes the transient
     * error without touching state; an invite whose key cannot be read — cut short in sharing — opens the
     * incomplete-invite screen, as a key that does not open its event does; an invite opens the **join gate** — a first
     * join the full-screen confirmation, a different event while joined a switch confirmation. Re-scanning the
     * already-joined event is a no-op (never re-enrolls).
     */
    fun onInvite(read: InviteLinkVerdict): Job = runner.intent {
        when (read) {
            InviteLinkVerdict.NotAnInvite -> cells.flashInvalidLink()
            is InviteLinkVerdict.KeyUnreadable -> keyUnreadable(read.event)
            is InviteLinkVerdict.Invite -> invited(read.link)
        }
    }

    private fun keyUnreadable(event: EventId) = when (event) {
        pending.value?.eventId -> ignoreRepeat(event, "a pending join is open")
        joinedId() -> ignoreRepeat(event, "already joined")
        else -> startUnreadable(event)
    }

    private suspend fun invited(link: InviteLink) {
        val joined = joinedId()
        when (link.event) {
            // The two DUPLICATE rungs, first because they outrank every other reading of the same link.
            pending.value?.eventId -> ignoreRepeat(link.event, "a pending join is open")
            joined -> reopened(link)
            // First join → JoiningEvent; a different event while joined → Joined.pendingSwitch (the same-event case is
            // the duplicate rung above).
            else -> startPending(link.event, link.key, leavesQuietly = joined != null)
        }
    }

    /** The joined event's own invite gives a LOST key back; any other reopening of it changes nothing. */
    private fun reopened(link: InviteLink) {
        val key = link.key
        if (key != null && reads.keyPresence.value == KeyPresence.Lost) {
            restoreKey(link.event, key)
        } else {
            ignoreRepeat(link.event, "already joined")
        }
    }

    private fun joinedId(): EventId? = reads.membership.value?.let { it.event.id }

    /**
     * A delivery of a link this gate is already acting on, or has already acted on — **recorded, then ignored**.
     *
     * The platform delivers the same link more than once, and that is measured rather than defensive: build 687
     * received one URL twice on an iOS 18.7.9 cold launch (~130 ms apart, the scene delegate's connection and then
     * SwiftUI's `.onOpenURL`), and twice again on iOS 26.6 both while running (8 ms) and cold (105 ms). So "exactly
     * once" is enforced here, in tested code, and NOT assumed of any arrangement of platform hooks — which is what lets
     * more than one delivery hook stay live, and what keeps a hook a future iOS adds or removes from reintroducing a
     * double join.
     *
     * Both rungs read state that already exists and already self-clears, so there is no duplicate-tracking field to
     * leak or to forget to reset: `pending` is cleared when the join is committed or dismissed, and the membership names
     * the event only while it stands. A repeat is therefore ignored exactly while the member is still deciding about
     * it, and a genuinely re-opened invite afterwards is acted on again.
     *
     * It logs because "nothing happened, this was a duplicate" and "nothing happened, the link never arrived" are
     * different answers with different causes (law "Absence is never silent"). A device log that could not tell them
     * apart is what made `SNAPSYNC-25` take a day to characterise.
     */
    private fun ignoreRepeat(eventId: EventId, because: String) {
        log("join gate: ignoring a repeated delivery of $eventId — $because")
    }

    /**
     * The joined event's reopened invite, offered to give its lost key back — kept only if it is the event's own. The
     * screen follows the key's presence, not the request's answer.
     */
    private fun restoreKey(eventId: EventId, key: EventKey) {
        val request = cells.ids.next()
        commands.restoreKey.fire(KeyRestoreRequest(request, key))
        log("join gate: the reopened invite of $eventId offers its key back ($request)")
    }

    /** Retry the details fetch after a transient load failure. */
    fun onRetryLoad(): Job = runner.intent {
        val p = pending.value ?: return@intent
        maintenanceReload?.cancel()
        pending.value = p.copy(phase = JoinProgress.Loading, service = null)
        showLoaded(p.eventId, load(p.eventId, p.key))
    }

    /**
     * Confirm a first join with the chosen range, direction and album: enroll → provision (no leave).
     *
     * Where the surface said so ([asksAccessOnJoin]), the same tap first raises iOS's photo-access dialog — the one
     * deliberate action the join gate has that may. The join does not wait for the answer: `request()` returns nothing
     * and cannot suspend, the grant arrives only through the permission source, and the join goes ahead whatever it is
     * — so the dialog lands over Committing or the joined screen, and a later grant starts sharing through the existing
     * subscription (decision record `simplify-join-screen`, D3). A retry never re-requests: by then iOS has an answer.
     */
    fun onConfirmJoin(): Job = runner.intent {
        if (asksAccessOnJoin(reads.membership.value, reads.permission.value)) {
            commands.platform.requestAccess.fire(Unit)
        }
        commit()
    }

    /**
     * Confirm a switch: run the **leave and nothing else**, and choose nothing on the member's behalf. The pending join
     * survives; once the leave has cleared the membership, the reduction's no-membership rung renders the **regular
     * full-screen join surface** for the new event, where the member picks direction, range and album exactly as on a
     * first join. This is why the confirmation carries no pickers and this intent takes no arguments — the surface that
     * follows owns every choice.
     *
     * The leave rides the same [UserCommands.leave] the joined layer's Leave action uses, so in-flight downloads are
     * cancelled and non-terminal rows pruned before `LeaveEvent` stops the producer and clears the membership.
     *
     * Nothing is re-derived after the leave: the loaded phase is always the confirm surface, and whether its confirm
     * also asks for photo access is read live once the membership is gone ([asksAccessOnJoin]).
     */
    fun onConfirmSwitch() = runner.guarded(Guarded.SwitchLeave) {
        val p = pending.value?.takeIf { (it.phase as? JoinProgress.Detailed)?.step == JoinPhase.Detailed.Step.Ready }
        val left = joinedId()
        if (p != null && left != null) leaveFor(p, left)
    }

    /**
     * The switch's leave of [left] for [p]'s event. It answers nothing but the membership: the guard is held until it
     * is gone — or the switch was given up, since a leave that cannot be written leaves the member joined — so a second
     * tap asks for no second leave.
     */
    private suspend fun leaveFor(p: PendingJoin, left: EventId) {
        local.update { it.copy(overlays = Overlays()) } // every overlay belongs to the layer being left
        commands.leave.fire(Unit)
        combine(reads.membership, pending) { joined, open ->
            joined?.let { it.event.id } != left || open?.eventId != p.eventId
        }.first { it }
    }

    /** Retry a failed commit — the leave (if any) already succeeded, so this re-runs only the join. */
    fun onRetryJoin(): Job = runner.intent { commit() }

    /**
     * Discard the pending join, returning to the base screen — the create layer when no event is joined. Reached
     * through a **switch**, the leave has already run, so this lands the device in **no event**; the confirmation
     * named the event being left, and rescanning an invite rejoins.
     */
    fun onCancelJoin(): Job = runner.intent { pending.value = null }

    /**
     * Dismiss the switch confirmation *before* its leave, staying in the current event untouched. The same one-line
     * body as [onCancelJoin], deliberately kept distinct: the two are genuinely different acts — this one keeps the
     * membership, that one ends with none.
     */
    fun onCancelSwitch(): Job = runner.intent { pending.value = null }

    /**
     * A create just minted [eventId]: route it into the **same** pending-join gate a scanned QR uses —
     * non-auto-confirmed — so the creator loads the event, picks a range, and confirms like any joiner. The
     * `POST /events` already minted the event, so the gate holds a real `eventId` and performs a real details load;
     * provision happens on confirm.
     */
    suspend fun eventCreated(eventId: EventId, key: EventKey?) = startPending(eventId, key)

    /**
     * An invite to [eventId] whose key cannot be read: the incomplete-invite screen, with nothing loaded and nothing
     * left — what the member needs is the whole invite, from whoever shared it.
     */
    private fun startUnreadable(eventId: EventId) {
        log("join gate: the invite to $eventId carries a key that cannot be read")
        local.update { it.copy(form = ranges.freshForm, picking = null) }
        pending.value = PendingJoin(eventId, JoinProgress.WrongLink, key = null)
    }

    private suspend fun startPending(eventId: EventId, key: EventKey?, leavesQuietly: Boolean = false) {
        // A fresh surface starts from the defaults — all on, the full event window. Seeding HERE rather than in the
        // reduction is what keeps the member's edits from being overwritten by every subsequent reduction, and what
        // stops a previous surface's choices leaking into this one.
        local.update { it.copy(form = ranges.freshForm, picking = null) }
        // Pending BEFORE any read: a repeated delivery of the same link is then the duplicate rung's, and a loading
        // switch shows no dialog yet.
        pending.value = PendingJoin(eventId, JoinProgress.Loading, key)
        val loaded = load(eventId, key)
        if (leavesQuietly) leaveQuietlyFor(eventId, loaded)
        showLoaded(eventId, loaded)
    }

    /** [loaded] shown on [eventId]'s pending join, and reloaded while the service is being updated. */
    private fun showLoaded(eventId: EventId, loaded: Loaded) {
        pending.update { it.showing(eventId, loaded) }
        scope.reloadWhileUpdating(loaded, pending) { onRetryLoad() }?.let { maintenanceReload = it }
    }

    /**
     * Another event's invite while joined: when leaving the joined event loses nothing for anyone (decision record
     * `changes/back-dated-events`, D8), it is left at once, without the switch question — the join that follows is then
     * a first join, whose Cancel leaves the device in no event, exactly as after a confirmed switch. Asked only once the
     * invite has loaded, and only while it is still the gate's: an invite that cannot be joined never leaves the joined
     * event.
     */
    private suspend fun leaveQuietlyFor(eventId: EventId, loaded: Loaded) {
        if (pending.value?.eventId != eventId) return
        val id = cells.ids.next()
        commands.switch.fire(SwitchRequest(id, loaded.invite))
        val verdict = requests.switch.answerTo(id)
        if (verdict == SwitchVerdict.Quiet && pending.value?.eventId == eventId) {
            log("join gate: leaving the joined event for $eventId without asking — it loses nothing")
            local.update { it.copy(overlays = Overlays()) } // every overlay belongs to the layer being left
            commands.leave.fire(Unit)
        }
    }

    /**
     * Load the event's join details. The range's floor is the event's start, canonical by construction: a membership
     * always carries a range, so a details read that cannot supply one lands on a retryable failure, never on an empty
     * range row and an enabled confirm, which would join at whole-library scope and upload the whole camera roll.
     */
    private suspend fun load(eventId: EventId, key: EventKey?): Loaded {
        val load = joinDetails.ask(InviteLink(eventId, key))
        // The headless negative oracle: a gate parked on a failed details load shows a dialog nobody headless watches —
        // without this line, `debug.log` shows only the HTTP `404`.
        if (load !is JoinDetails.Found) log("join gate: details load did not succeed for $eventId ($load)")
        return loadedOf(load)
    }

    private suspend fun commit() {
        val p = pending.value ?: return
        val detailed = confirmable(p) ?: return
        val choice = choiceFor(p, detailed) ?: return
        pending.value = p.copy(phase = JoinProgress.Detailed(detailed.event, JoinPhase.Detailed.Step.Committing), service = null)
        beginMembership()
        val id = cells.ids.next()
        commands.join.fire(JoinRequest(id, choice))
        val commit = requests.join.answerTo(id)
        val joined = reads.membership.value
        pending.update { it.settled(p, detailed, commit, joined, reads.refusal.value, reads.attestFault.value) }
    }

    /**
     * Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a still-loading, blocked or
     * committing phase ignores the action. Both carry a non-null name, start, end AND deletion date — so a commit can
     * never reach the join without the floor, the ceiling, and the retention deadline.
     */
    private fun confirmable(p: PendingJoin): JoinProgress.Detailed? =
        (p.phase as? JoinProgress.Detailed)?.takeIf { it.step in RETRYABLE_STEPS }

    /**
     * What is committed: what the reduction RESOLVED — the same value the surface rendered. A join always carries a
     * direction: with both switches off the confirm is disabled on screen, and a confirm that arrives anyway joins
     * nothing rather than a membership doing nothing.
     */
    private fun choiceFor(p: PendingJoin, detailed: JoinProgress.Detailed): JoinChoice? {
        val form = local.value.form
        val range = ranges.resolve(form, detailed.event.startsAt, detailed.event.endsAt, local.value.shareCount)
        val served = p.loaded?.takeIf { range.commitEnabled } ?: return null
        return JoinChoice(
            served,
            InviteLink(p.eventId, p.key),
            range.chosenFrom,
            range.chosenUntil,
            range.direction,
            AlbumSwitch.choice(form.saveToAlbum),
        )
    }

    /**
     * A membership BEGINS here, so its surface state starts here — in one place every new membership passes through, a
     * rejoin of the same event included. A collector watching the membership could miss that: it is a StateFlow, and a
     * leave and a rejoin of one event can conflate into A → A.
     */
    private fun beginMembership() {
        local.update { it.copy(settings = Owned(null, SettingsSurface.Closed), renameRequest = null) }
    }
}
