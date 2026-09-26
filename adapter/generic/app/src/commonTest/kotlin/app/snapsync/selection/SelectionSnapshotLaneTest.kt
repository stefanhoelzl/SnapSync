package app.snapsync.selection

import app.snapsync.model.GalleryAccess
import app.snapsync.model.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The partial-grant selection source's ORDERING (capability `photo-access`; decision record
 * `harden-seam-bug-classes`, D12; `own-work-per-wake`, D14): snapshots leave in the order their reads happened, a
 * change during the baseline is applied to it rather than dropped, and no read — baseline or change — whose
 * observation ended while it was read emits anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionSnapshotLaneTest {

    /** A selection is a list of ids; a change appends one. The baseline can be held open by a test. */
    private class FakePlatform(private val initial: List<String>) : SelectionPlatform<List<String>, String> {
        var onChange: ((String) -> Unit)? = null
        var stops = 0
        var baselineGate: CompletableDeferred<Unit>? = null

        override fun startObserving(onChange: (String) -> Unit) {
            this.onChange = onChange
        }

        override fun stopObserving() {
            stops++
            onChange = null
        }

        override suspend fun baseline(): List<String> {
            baselineGate?.await()
            return initial
        }

        override fun after(held: List<String>, change: String): List<String> = held + change

        /** Holds the NEXT enumeration open until completed; consumed by it. */
        var snapshotGate: CompletableDeferred<Unit>? = null

        /** Every enumeration, by the selection it read — the expensive call the lane folds. */
        val enumerations = mutableListOf<List<String>>()

        override suspend fun snapshot(of: List<String>): List<Resource> {
            enumerations += of
            snapshotGate?.let { gate ->
                snapshotGate = null
                gate.await()
            }
            return render(of)
        }

        private fun render(of: List<String>): List<Resource> =
            of.map { Resource(filename = "$it.heic", assetId = it, contentType = "image/heic", metadata = emptyMap(), data = it) }

        fun change(id: String) = checkNotNull(onChange) { "not observing" }(id)
    }

    private fun List<Resource>.ids() = map { it.data as String }

    @Test
    fun rapid_changes_from_many_threads_emit_in_the_order_they_were_applied() = runTest {
        // B9: each snapshot used to be read and emitted on its own coroutine over a parallel dispatcher, so a later
        // selection could be overtaken by an earlier one — and the consumer kept the stale one.
        val seen = mutableListOf<List<String>>()
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val platform = FakePlatform(listOf("base"))
            val lane = SelectionSnapshotLane(
                MutableStateFlow(GalleryAccess.LIMITED),
                scope,
                Dispatchers.Default.limitedParallelism(1),
                platform,
            )
            val collecting = scope.launch(UnconfinedTestDispatcher()) { lane.snapshots.collect { seen += it.ids() } }
            withTimeout(5_000) { while (platform.onChange == null || seen.isEmpty()) kotlinx.coroutines.yield() }

            List(CHANGES) { i -> launch { platform.change("c$i") } }.joinAll()
            withTimeout(5_000) { while (seen.lastOrNull()?.size != CHANGES + 1) kotlinx.coroutines.yield() }

            collecting.cancel()
            scope.cancel()
        }
        // Each change appends, so in-order emission means every snapshot is strictly larger than the one before.
        seen.zipWithNext().forEach { (earlier, later) ->
            assertTrue(later.size > earlier.size, "a snapshot overtook a newer one: ${earlier.size} then ${later.size}")
        }
        assertEquals(CHANGES + 1, seen.last().size)
    }

    @Test
    fun a_change_during_the_baseline_is_applied_to_it_not_dropped() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val platform = FakePlatform(listOf("base")).apply { baselineGate = CompletableDeferred() }
        val source = SelectionSnapshotLane(MutableStateFlow(GalleryAccess.LIMITED), scope, lane, platform)
        val latest = MutableStateFlow<List<String>>(emptyList())
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots.collect { latest.value = it.ids() } }
        advanceUntilIdle() // observing, and the baseline read is in progress

        platform.change("taken-during-baseline")
        platform.baselineGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("base", "taken-during-baseline"), latest.value)
        scope.cancel()
    }

    @Test
    fun a_grant_upgrade_during_the_baseline_emits_nothing_and_stops_observing() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val permission = MutableStateFlow(GalleryAccess.LIMITED)
        val platform = FakePlatform(listOf("base")).apply { baselineGate = CompletableDeferred() }
        val source = SelectionSnapshotLane(permission, scope, lane, platform)
        val emitted = mutableListOf<List<String>>()
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots.collect { emitted += it.ids() } }
        advanceUntilIdle()

        permission.value = GalleryAccess.GRANTED // the full grant's walks own liveness now
        advanceUntilIdle()
        platform.baselineGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), emitted, "a baseline read for an observation that has ended must not be emitted")
        assertEquals(1, platform.stops)
        scope.cancel()
    }

    @Test
    fun observation_restarts_with_a_fresh_baseline_when_the_grant_returns_to_partial() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val permission = MutableStateFlow(GalleryAccess.LIMITED)
        val platform = FakePlatform(listOf("base"))
        val source = SelectionSnapshotLane(permission, scope, lane, platform)
        advanceUntilIdle()
        permission.value = GalleryAccess.GRANTED
        advanceUntilIdle()

        permission.value = GalleryAccess.LIMITED
        val snapshot = scope.launch { assertEquals(listOf("base"), source.snapshots.first().ids()) }
        advanceUntilIdle()

        assertTrue(snapshot.isCompleted)
        assertEquals(1, platform.stops)
        scope.cancel()
    }

    @Test
    fun changes_queued_behind_a_running_enumeration_are_folded_into_one_more() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val platform = FakePlatform(listOf("base"))
        val source = SelectionSnapshotLane(MutableStateFlow(GalleryAccess.LIMITED), scope, lane, platform)
        val emitted = mutableListOf<List<String>>()
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots.collect { emitted += it.ids() } }
        advanceUntilIdle() // the baseline is read and emitted

        val running = CompletableDeferred<Unit>().also { platform.snapshotGate = it }
        platform.change("a")
        advanceUntilIdle() // a's enumeration is running, held open
        platform.change("b")
        platform.change("c")
        platform.change("d")
        running.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(listOf("base"), listOf("base", "a"), listOf("base", "a", "b", "c", "d")),
            platform.enumerations,
            "at most one enumeration in flight, and ONE more for the latest of the changes queued behind it",
        )
        assertEquals(listOf("base", "a", "b", "c", "d"), emitted.last(), "the final snapshot is the one per-change emission ends on")
        scope.cancel()
    }

    @Test
    fun a_change_queued_before_the_grant_becomes_full_is_not_emitted_after_it() = runTest {
        // Grant-flip gap (own-work-per-wake, D14): the change path used to skip the baseline path's generation check,
        // so the running enumeration and the change queued before the end both emitted limited-scope snapshots
        // after the grant had become full — and this test asserted it.
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val permission = MutableStateFlow(GalleryAccess.LIMITED)
        val platform = FakePlatform(listOf("base"))
        val source = SelectionSnapshotLane(permission, scope, lane, platform)
        val emitted = mutableListOf<List<String>>()
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots.collect { emitted += it.ids() } }
        advanceUntilIdle()

        val running = CompletableDeferred<Unit>().also { platform.snapshotGate = it }
        platform.change("a")
        advanceUntilIdle() // a's enumeration is running, held open
        platform.change("b") // queued before the end
        permission.value = GalleryAccess.GRANTED // the end queues behind b
        advanceUntilIdle()
        running.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(listOf("base")), emitted, "nothing built under the limited grant is emitted once it is full")
        assertEquals(listOf(listOf("base"), listOf("base", "a")), platform.enumerations, "b is not enumerated")
        assertEquals(1, platform.stops, "the end the fold stopped at is still handled")
        scope.cancel()
    }

    @Test
    fun a_change_enumerated_across_the_grant_flip_is_dropped() = runTest {
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val permission = MutableStateFlow(GalleryAccess.LIMITED)
        val platform = FakePlatform(listOf("base"))
        val source = SelectionSnapshotLane(permission, scope, lane, platform)
        val emitted = mutableListOf<List<String>>()
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots.collect { emitted += it.ids() } }
        advanceUntilIdle()

        val running = CompletableDeferred<Unit>().also { platform.snapshotGate = it }
        platform.change("a")
        advanceUntilIdle() // a's enumeration is running under the limited grant
        permission.value = GalleryAccess.GRANTED
        advanceUntilIdle()
        running.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(listOf("base")), emitted, "a snapshot read across the flip is dropped, as a baseline's is")
        assertEquals(1, platform.stops)
        scope.cancel()
    }

    @Test
    fun a_change_after_observation_restarts_is_emitted_again() = runTest {
        // The check compares against the observation's own generation, so a new partial grant's changes still emit.
        val lane = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(lane + Job())
        val permission = MutableStateFlow(GalleryAccess.LIMITED)
        val platform = FakePlatform(listOf("base"))
        val source = SelectionSnapshotLane(permission, scope, lane, platform)
        val emitted = mutableListOf<List<String>>()
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots.collect { emitted += it.ids() } }
        advanceUntilIdle()
        permission.value = GalleryAccess.GRANTED
        advanceUntilIdle()
        permission.value = GalleryAccess.LIMITED
        advanceUntilIdle()

        platform.change("a")
        advanceUntilIdle()

        assertEquals(listOf("base", "a"), emitted.last())
        scope.cancel()
    }

    private companion object {
        const val CHANGES = 200
    }
}
