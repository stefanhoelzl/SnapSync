package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.presentation.RangeResolution.reconfigureForm
import app.snapsync.presentation.RenameRequests.answerTo
import app.snapsync.query.membership.MembershipShown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The event's settings, applied as they change: the queue every settings act runs through, what the next change builds
 * on, and the answers to the withdrawal question.
 */
internal class SettingsChanges(
    private val cells: ScreenCells,
    private val membership: StateFlow<MembershipShown>,
    private val commands: UserCommands,
    private val requests: RequestStatusFlows,
    private val cutoffFormatter: CutoffFormatter,
    private val albumContents: AlbumContents,
    private val scope: CoroutineScope,
) {
    private val local = cells.local

    /**
     * The settings' changes, in the order they were made: changes made one after another apply in order, the last one
     * standing. Orbit runs intents concurrently, so a confirm, the next switch or a close could overtake a change still
     * saving; each settings act is enqueued HERE, synchronously in the tapping thread, and one consumer runs them one
     * at a time. Started lazily on the container's scope.
     */
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val worker by lazy {
        // Never closed: the worker lives as long as the container's scope, so it ends by cancellation only.
        scope.launch { while (true) queue.receive()() }
    }

    /** Run [act] after every settings act enqueued before it — see [queue]. */
    internal fun enqueue(act: suspend () -> Unit) {
        worker.start()
        queue.trySend(act)
    }

    /**
     * Open the settings surface, pre-filled from the membership held. Seeding HERE rather than in the reduction is what
     * makes the pre-fill a SNAPSHOT: a foreground refresh landing mid-edit updates the heading, not the controls in the
     * member's hand.
     */
    internal fun open() = enqueue {
        val joined = membership.value.held ?: return@enqueue
        val form = reconfigureForm(joined.event.range, SettingChange.of(joined), cutoffFormatter::toLocal)
            .copy(albumContents = albumContents)
        local.update {
            it.copy(
                form = form,
                picking = null,
                settings = Owned(joined.event.id, SettingsSurface.Open),
                pendingWithdrawal = Owned(null, null),
                lastApplied = Owned(null, null),
            )
        }
    }

    /**
     * Close the settings: swiped down, back, or a tap on the joined screen above them. Every change already applied as
     * it was made, so closing writes nothing — and a question still open about withdrawing photos is answered "keep
     * sharing".
     */
    internal fun close() = enqueue {
        local.update {
            it.copy(
                settings = Owned(null, SettingsSurface.Closed),
                pendingWithdrawal = Owned(null, null),
                picking = null,
            )
        }
    }

    /**
     * The settings apply each change as it is made: one reconfigure per change, built from the membership IN EFFECT
     * with that one change, and run in the container's intent order — so quick changes apply one after another and the
     * last one stands. A change that would withdraw photos from the event (sharing off, or a narrower range) is held
     * instead, and asked about ([pendingWithdrawal][Local.pendingWithdrawal]). [joined] is the membership whose
     * settings are open ([openMembership]).
     */
    internal suspend fun change(joined: Membership, change: (SettingChange, Membership) -> SettingChange) {
        val inEffect = settingsInEffect(joined)
        val next = change(inEffect, joined)
        // A switch set to what it shows changes nothing, so there is nothing to save.
        if (next == inEffect) return
        if (next.withdrawsFrom(inEffect)) {
            local.update { it.copy(pendingWithdrawal = Owned(joined.event.id, next)) }
            return
        }
        applySetting(joined, inEffect, next)
    }

    /**
     * The settings in effect: the last change these settings applied, else the membership as read. The read can lag a
     * save the use-case just made, and a change built on it would quietly undo the one before — so quick changes build
     * on each other, not on a stale read.
     */
    private fun settingsInEffect(joined: Membership): SettingChange =
        local.value.lastApplied.forMembership(joined.event.id, null) ?: SettingChange.of(joined)

    /**
     * Run one change through [UserCommands.reconfigure]. The event id rides with the values so a switch that landed
     * while the settings were open makes the use-case a no-op rather than overwriting a different membership; the
     * share-range rule is the use-case's. Afterwards the controls are re-seeded from the settings in effect — the
     * change
     * if it landed, the ones before it if not — so a change that did not land shows the setting still in effect, and
     * says so: the controls never hold a value that is not saved.
     */
    private suspend fun applySetting(joined: Membership, before: SettingChange, next: SettingChange) {
        local.update { it.copy(pendingWithdrawal = Owned(null, null)) }
        val id = cells.ids.next()
        val choice = ShareChoice(
            joined.event.id,
            next.from,
            next.until,
            next.direction,
            AlbumSwitch.choice(next.saveToAlbum),
        )
        commands.reconfigure.fire(SettingsRequest(id, choice))
        when (requests.settings.answerTo(id)) {
            SettingsResult.NotCurrent -> closeSettings()
            SettingsResult.Saved -> reseed(joined, next, SettingsSurface.Open)
            SettingsResult.SaveFailed, SettingsResult.Refused -> reseed(joined, before, SettingsSurface.SaveFailed)
        }
    }

    /** A switch landed while the settings were open: they close, the membership they were for being gone. */
    private fun closeSettings() = local.update {
        it.copy(settings = Owned(null, SettingsSurface.Closed), lastApplied = Owned(null, null))
    }

    /** The controls re-seeded from the settings [inEffect], the surface showing [surface]. */
    private fun reseed(joined: Membership, inEffect: SettingChange, surface: SettingsSurface) {
        val reseeded = reconfigureForm(joined.event.range, inEffect, cutoffFormatter::toLocal)
            .copy(albumContents = albumContents)
        local.update {
            it.copy(
                form = reseeded,
                settings = Owned(joined.event.id, surface),
                lastApplied = Owned(joined.event.id, inEffect),
            )
        }
    }

    /** "Stop sharing" on the withdrawal question: the held change applies. */
    fun onConfirmStopSharing() = enqueue {
        val joined = membership.value.held ?: return@enqueue
        val held = local.value.pendingWithdrawal.forMembership(joined.event.id, null) ?: return@enqueue
        applySetting(joined, settingsInEffect(joined), held)
    }

    /** "Keep sharing": the held change is dropped, and the controls — which never took it — stay as they were. */
    fun onKeepSharing() = enqueue { local.update { it.copy(pendingWithdrawal = Owned(null, null)) } }

    /**
     * The membership whose settings are open over the joined screen — where a form edit applies rather than drafts — or
     * `null` when none are.
     */
    internal fun openMembership(): Membership? = membership.value.held?.takeIf {
        local.value.settings.forMembership(it.event.id, SettingsSurface.Closed) != SettingsSurface.Closed
    }
}
