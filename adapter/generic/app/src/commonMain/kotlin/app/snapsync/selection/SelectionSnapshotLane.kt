package app.snapsync.selection

import app.snapsync.model.ConfinedTo
import app.snapsync.model.GalleryAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * What a partial-grant selection source needs from its platform: observe the library, read the selection once,
 * follow a change pushed against a held read, and turn a read into a snapshot. [F] is the platform's held read
 * (`PHFetchResult` on iOS), [C] its change notification (`PHChange`) and [S] the snapshot it delivers.
 */
interface SelectionPlatform<F : Any, C : Any, S> {
    /** Start delivering changes to [onChange], on any thread. */
    fun startObserving(onChange: (C) -> Unit)

    fun stopObserving()

    /** The baseline: one read of the whole current selection. */
    suspend fun baseline(): F

    /** [held] after [change], or `null` when the change does not touch it (nothing to emit). */
    fun after(held: F, change: C): F?

    /** The selection [of] a read, as a snapshot. */
    suspend fun snapshot(of: F): S
}

/**
 * The ordering core of a gallery's partial-grant selection observer (capability `photo-access`; law
 * "State reached from OS callbacks is confined", `docs/architecture.md`; decision record
 * `harden-seam-bug-classes`, D12).
 *
 * Observes only while the grant is [GalleryAccess.LIMITED] **and** observation is switched on ([observe] — the
 * gallery's `observeChanges`, called only from host assembly): a baseline snapshot when observation begins, and one
 * per change after it. Every piece of work — beginning, ending, the baseline, each change — goes through ONE channel
 * consumed on ONE serial [lane], so:
 *
 *  - snapshots are emitted in the order their reads happened. They used to be launched one coroutine each on a
 *    parallel dispatcher, so two quick changes could emit newest-first and leave the consumer holding a selection
 *    the user had already changed (B9);
 *  - a change that arrives while the baseline is being read queues behind it and is applied to it, instead of
 *    finding no held read and being dropped;
 *  - no snapshot read under an observation that has since ended (a grant upgraded to full mid-read) is emitted,
 *    on the baseline path or the change path: each begin carries a generation, the permission collector advances
 *    it the moment the grant moves, and every emission first checks that the generation its read was built under
 *    is still the current one — whether or not the [LaneWork.End] the move queued has reached the lane yet;
 *  - changes that queue up behind a running enumeration are folded into ONE more enumeration for the latest of
 *    them, instead of one full resource read each (see [change]).
 *
 * The held read, the observing flag and [observed] are touched only by the consumer, on [lane]; [generation] is
 * written only by the permission collector and read by the consumer.
 */
class SelectionSnapshotLane<F : Any, C : Any, S>(
    permission: StateFlow<GalleryAccess>,
    scope: CoroutineScope,
    /** Serial: production passes `Dispatchers.Default.limitedParallelism(1)`. */
    lane: CoroutineDispatcher,
    private val platform: SelectionPlatform<F, C, S>,
) {

    /** Whether observation is switched on — off until the gallery's owner asks. */
    private val enabled = MutableStateFlow(false)

    /** Switch observation on or off; while on, it still runs only under a partial grant. */
    fun observe(enabled: Boolean) {
        this.enabled.value = enabled
    }

    private sealed interface LaneWork<out C> {
        class Begin(val generation: Int) : LaneWork<Nothing>
        data object End : LaneWork<Nothing>
        class Change<C>(val change: C) : LaneWork<C>
    }

    private val work = Channel<LaneWork<C>>(Channel.UNLIMITED)

    @Volatile
    private var generation = 0

    // Lane-confined: read and written only by the consumer below.
    @ConfinedTo("selection")
    private var observing = false

    @ConfinedTo("selection")
    private var held: F? = null

    /** The generation of the observation [held] belongs to — what a change's snapshot is built under. */
    @ConfinedTo("selection")
    private var observed = 0

    // Snapshots conflate: each is the whole selection, so an unconsumed older one is superseded by construction,
    // and emission never suspends the lane. The latest is REPLAYED, so a consumer that subscribes after the baseline
    // was read still receives it.
    private val emitted = MutableSharedFlow<S>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val snapshots: Flow<S> = emitted

    init {
        scope.launch(lane) {
            // A change handler may drain the Changes queued behind it and stop at the first item that is not one;
            // that item is handed back here and handled next, so nothing is reordered or lost.
            var carried: LaneWork<C>? = null
            while (true) {
                val item = carried ?: work.receiveCatching().getOrNull() ?: break
                carried = handle(item)
            }
        }
        scope.launch {
            var limited = false
            combine(permission, enabled) { status, on -> on && status == GalleryAccess.LIMITED }.collect { nowLimited ->
                if (nowLimited == limited) return@collect
                limited = nowLimited
                generation++
                work.trySend(if (nowLimited) LaneWork.Begin(generation) else LaneWork.End)
            }
        }
    }

    /** Handle [item]; returns work a change handler dequeued but did not handle, which runs next. */
    private suspend fun handle(item: LaneWork<C>): LaneWork<C>? {
        when (item) {
            is LaneWork.Begin -> begin(item.generation)
            LaneWork.End -> end()
            is LaneWork.Change -> return change(item.change)
        }
        return null
    }

    private suspend fun begin(started: Int) {
        if (started != generation) return
        platform.startObserving { change -> work.trySend(LaneWork.Change(change)) }
        observing = true
        observed = started
        val baseline = platform.baseline()
        if (started != generation) return
        held = baseline
        val snapshot = platform.snapshot(baseline)
        if (started == generation) emitted.emit(snapshot)
    }

    private fun end() {
        if (observing) platform.stopObserving()
        observing = false
        held = null
    }

    /**
     * Apply [first] and every change already queued behind it, then enumerate ONCE, for the latest.
     *
     * Each snapshot is a full, eager resource read of the selection, and each is the WHOLE selection — so a
     * snapshot computed for a change that another has already superseded is work whose result the conflating
     * flow would drop anyway. Changes that arrived while the previous enumeration ran are therefore folded into
     * one: at most one enumeration runs, the changes behind it only move [held] forward, and when it finishes
     * one more runs for the latest. The last snapshot emitted is the one per-change emission would have ended on.
     *
     * Folding [held] through [SelectionPlatform.after] one change at a time is what keeps that true: each change
     * is relative to the read before it, and `after` reads only the change it was pushed (the pushed
     * `fetchResultAfterChanges`), never the library. Draining stops at the first queued item that is not a
     * change — an observation ending or restarting — and hands it back to run next, so no change is ever applied
     * across one.
     *
     * A change is built under the generation of the observation that holds [held]. If the grant has moved since
     * (the collector advanced [generation] and queued the End or Begin that follows), nothing is enumerated or
     * emitted: neither the change in hand, nor — checked again after the enumeration, which may span the move —
     * the fold's one snapshot. The End that the move queued is then handled next, as it would be anyway.
     */
    private suspend fun change(first: C): LaneWork<C>? {
        val current = held ?: return null
        val builtUnder = observed
        if (builtUnder != generation) return null
        var latest: F? = platform.after(current, first)
        var carried: LaneWork<C>? = null
        while (true) {
            val queued = work.tryReceive().getOrNull() ?: break
            if (queued !is LaneWork.Change) {
                carried = queued
                break
            }
            platform.after(latest ?: current, queued.change)?.let { latest = it }
        }
        val after = latest ?: return carried
        if (builtUnder != generation) return carried
        held = after
        val snapshot = platform.snapshot(after)
        if (builtUnder == generation) emitted.emit(snapshot)
        return carried
    }
}
