package app.snapsync.presentation

import app.snapsync.domain.event.EventId
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.ScreenMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/**
 * How a part of the screen runs its work on the container — as an intent, or as a guarded one ([Guarded]) — and reads
 * what the container holds now.
 */
internal interface IntentRunner {
    fun intent(block: suspend () -> Unit): Job

    fun guarded(command: Guarded, block: suspend () -> Unit)

    /** The screen's model as the container holds it now. */
    fun current(): ScreenModel
}

/**
 * The screen's own cells, shared by its parts: what it holds beside what it observes ([local]), the join in progress
 * ([pending]), the ids of its requests and how its work runs ([runner]).
 */
internal class ScreenCells(
    val local: MutableStateFlow<Local>,
    val pending: MutableStateFlow<PendingJoin?>,
    val ids: RequestIds,
    val runner: IntentRunner,
    private val scope: CoroutineScope,
) {
    private var invalidLinkClear: Job? = null

    /**
     * Flash the transient invalid-link error: a link arrived that the decoder rejected, so whichever layer is showing —
     * create, join or joined — shows a self-clearing message without touching persisted state.
     *
     * The value is an INPUT to the reduction — it reaches the screen inside `Layer.CreateEvent.error` (coalesced with a
     * sticky create failure), `Layer.JoiningEvent.notice` or `Layer.Joined.notice` — but the set-then-clear
     * choreography lives HERE, in presentation (`docs/architecture.md`, "Commands cross one door": multi-step
     * interactions are presentation-owned, and interaction state dies with the UI).
     *
     * A rejected link while the message is already showing re-arms the full window (the timer restarts) — the
     * deliberate reading of "self-clearing a few seconds after it LAST appeared".
     */
    fun flashInvalidLink() {
        local.update { it.copy(transientError = ScreenMessage.INVALID_LINK) }
        invalidLinkClear?.cancel()
        invalidLinkClear = scope.launch {
            delay(TRANSIENT_ERROR_MILLIS)
            local.update { it.copy(transientError = null) }
        }
    }
}

/** How long the transient invalid-link error stays on screen after it last appeared. */
private const val TRANSIENT_ERROR_MILLIS = 4_000L
