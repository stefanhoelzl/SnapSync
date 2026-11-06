package app.snapsync.presentation

import app.snapsync.domain.event.EventId
import app.snapsync.ui.state.Overlays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The non-idempotent commands whose taps claim an in-flight slot (see `StatusContainerHost.guardedIntent`). */
internal enum class Guarded { Create, Rename, SwitchLeave }

/**
 * Surface state that belongs to one membership (decision record `harden-seam-bug-classes`, D13): written with the
 * event it was opened for, and read back only while that event is the joined one. A change of membership therefore
 * closes it by construction — no exit path (leave, switch, self-leave, config clear) has to remember to (B7).
 */
internal data class Owned<T>(val eventId: EventId?, val value: T) {
    /** [value] while [eventId] is the [joined] event — or names none (a latch set from outside, e.g. forged). */
    fun forMembership(joined: EventId?, otherwise: T): T = when (eventId) {
        null, joined -> value
        else -> otherwise
    }
}

internal fun MutableStateFlow<Local>.editOverlays(edit: (Overlays) -> Overlays) = update {
    it.copy(overlays = edit(it.overlays))
}

internal fun MutableStateFlow<Local>.editForm(edit: (RangeForm) -> RangeForm) = update { it.copy(form = edit(it.form)) }
