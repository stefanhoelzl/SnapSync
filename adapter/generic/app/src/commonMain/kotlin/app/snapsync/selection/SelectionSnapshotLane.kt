package app.snapsync.selection

import app.snapsync.model.ConfinedTo
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.ports.PhotoSelectionChangeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * What a partial-grant selection source needs from its platform: observe the library, read the selection once,
 * follow a change pushed against a held read, and turn a read into resources. [F] is the platform's held read
 * (`PHFetchResult` on iOS) and [C] its change notification (`PHChange`).
 */
interface SelectionPlatform<F : Any, C : Any> {
    /** Start delivering changes to [onChange], on any thread. */
    fun startObserving(onChange: (C) -> Unit)

    fun stopObserving()

    /** The baseline: one read of the whole current selection. */
    suspend fun baseline(): F

    /** [held] after [change], or `null` when the change does not touch it (nothing to emit). */
    fun after(held: F, change: C): F?

    /** The selection [of] a read, as resources. */
    suspend fun snapshot(of: F): List<Resource>
}

/**
 * The ordering core of the partial-grant [PhotoSelectionChangeSource] (capability `limited-photo-access`; law
 * "State reached from OS callbacks is confined", capability `module-architecture`; decision record
 * `harden-seam-bug-classes`, D12).
 *
 * Observes only while the grant is [PermissionStatus.LIMITED]: a baseline snapshot when observation begins, and one
 * per change after it. Every piece of work — beginning, ending, the baseline, each change — goes through ONE channel
 * consumed on ONE serial [lane], so:
 *
 *  - snapshots are emitted in the order their reads happened. They used to be launched one coroutine each on a
 *    parallel dispatcher, so two quick changes could emit newest-first and leave the consumer holding a selection
 *    the user had already changed (B9);
 *  - a change that arrives while the baseline is being read queues behind it and is applied to it, instead of
 *    finding no held read and being dropped;
 *  - a baseline whose observation ended while it was being read (a grant upgraded to full mid-read) emits nothing:
 *    each begin carries a generation, and the permission collector advances it the moment the grant moves.
 *
 * The held read and the observing flag are touched only by the consumer, on [lane]; [generation] is written only by
 * the permission collector and read by the consumer.
 */
class SelectionSnapshotLane<F : Any, C : Any>(
    permission: StateFlow<PermissionStatus>,
    scope: CoroutineScope,
    /** Serial: production passes `Dispatchers.Default.limitedParallelism(1)`. */
    lane: CoroutineDispatcher,
    private val platform: SelectionPlatform<F, C>,
) : PhotoSelectionChangeSource {

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

    // Snapshots conflate: each is the whole selection, so an unconsumed older one is superseded by construction,
    // and emission never suspends the lane.
    private val emitted = MutableSharedFlow<List<Resource>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val snapshots: Flow<List<Resource>> = emitted

    init {
        scope.launch(lane) { for (item in work) handle(item) }
        scope.launch {
            var limited = false
            permission.collect { status ->
                val nowLimited = status == PermissionStatus.LIMITED
                if (nowLimited == limited) return@collect
                limited = nowLimited
                generation++
                work.trySend(if (nowLimited) LaneWork.Begin(generation) else LaneWork.End)
            }
        }
    }

    private suspend fun handle(item: LaneWork<C>) {
        when (item) {
            is LaneWork.Begin -> begin(item.generation)
            LaneWork.End -> end()
            is LaneWork.Change -> change(item.change)
        }
    }

    private suspend fun begin(started: Int) {
        if (started != generation) return
        platform.startObserving { change -> work.trySend(LaneWork.Change(change)) }
        observing = true
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

    private suspend fun change(change: C) {
        val current = held ?: return
        val after = platform.after(current, change) ?: return
        held = after
        emitted.emit(platform.snapshot(after))
    }
}
