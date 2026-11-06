package app.snapsync.presentation

import app.snapsync.domain.membership.Membership
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.SwitchTo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDateTime

/**
 * The member's edits to the participation form, grouped.
 *
 * At the join gate each only reduces: a tap touches no port and dispatches no command until Join. With the event's
 * settings open each one IS the change, applied through [SettingsChanges.change]. They are intents rather than screen
 * state because the screen SHOWS them — and a GROUP because they are one surface's questions.
 */
internal class FormEdits(
    private val cells: ScreenCells,
    private val membership: StateFlow<Membership?>,
    private val settings: SettingsChanges,
    private val ranges: RangeResolver,
) {
    private val local = cells.local

    /**
     * While joined, an edit can only be a settings change, so it takes its place in the settings' order
     * ([SettingsChanges.enqueue]) — behind an open still queued. At the join gate it is an ordinary intent, so the Join
     * that follows it reads the form it left.
     */
    private fun formIntent(act: suspend () -> Unit) {
        if (membership.value != null) settings.enqueue(act) else cells.runner.intent { act() }
    }

    // At the join gate each edit drafts the form; with the event's settings open it applies at once.
    fun onShareOn(to: SwitchTo) = formIntent {
        val on = to.isOn
        val open = settings.openMembership()
        if (open != null) {
            settings.change(open) { now, _ -> now.copy(direction = directionOf(on, now.direction.includesDownload)) }
        } else {
            local.editForm { it.copy(shareOn = on) }
        }
    }

    fun onReceiveOn(to: SwitchTo) = formIntent {
        val on = to.isOn
        val open = settings.openMembership()
        if (open != null) {
            settings.change(open) { now, _ -> now.copy(direction = directionOf(now.direction.includesUpload, on)) }
        } else {
            local.editForm { it.copy(receiveOn = on) }
        }
    }

    fun onSaveToAlbum(to: SwitchTo) = formIntent {
        val on = to.isOn
        val open = settings.openMembership()
        if (open != null) {
            settings.change(open) { now, _ -> now.copy(saveToAlbum = on) }
        } else {
            local.editForm { it.copy(saveToAlbum = on) }
        }
    }

    fun onRangePreset(preset: RangeChoice) {
        // A preset chip is a complete choice: the calendar it sits on closes.
        local.update { it.copy(picking = null) }
        formIntent { editRange { it.copy(preset = preset) } }
    }

    /** A custom range from the calendar; a `null` bound keeps the one already picked. */
    fun onRangeCustom(from: LocalDateTime?, until: LocalDateTime?) = formIntent {
        editRange { f ->
            f.copy(preset = RangeChoice.CUSTOM, customFrom = from ?: f.customFrom, customUntil = until ?: f.customUntil)
        }
    }

    /**
     * A committed range (a preset chip, or the calendar's OK). With the settings open it is the one place the bounds are
     * re-resolved from the form; every other change carries the bounds in effect untouched.
     */
    private suspend fun editRange(edit: (RangeForm) -> RangeForm) {
        val open = settings.openMembership()
        if (open == null) {
            local.editForm(edit)
            return
        }
        val form = edit(local.value.form)
        settings.change(open) { now, c ->
            val range = ranges.resolve(form, c.event.range.start, c.event.range.end, local.value.shareCount)
            now.copy(from = range.chosenFrom, until = range.chosenUntil)
        }
    }
}
