@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.status

import app.snapsync.mock.NetworkMock
import app.snapsync.model.NetworkAccess
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The foreground-gated network watch (capability `sync-status`, "The app says when it cannot reach the network"), over
 * the network mock's port: slow to warn, instant to clear, a changed cause at once, nothing after [NetworkWatch.stop],
 * and a return announced only when a shown notice clears — never on the stop's reset.
 */
class NetworkWatchTest {

    private val network = NetworkMock()

    private fun TestScope.watching(): NetworkWatch =
        NetworkWatch(backgroundScope, NetworkReadings(network.port())).also { it.start(); runCurrent() }

    private fun TestScope.returnsOf(watch: NetworkWatch): List<Unit> =
        mutableListOf<Unit>().also { seen -> backgroundScope.launch { watch.returned.collect { seen += it } }; runCurrent() }

    @Test
    fun a_two_second_drop_publishes_nothing() = runTest {
        val watch = watching()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.OFFLINE
        advanceTimeBy(2.seconds)
        network.operator.access = NetworkAccess.ONLINE
        advanceTimeBy(10.seconds)
        assertEquals(NetworkAccess.ONLINE, watch.access.value)
        assertEquals(emptyList(), returns, "nothing was shown, so nothing returned")
    }

    @Test
    fun a_six_second_drop_publishes_offline_after_the_grace() = runTest {
        val watch = watching()
        network.operator.access = NetworkAccess.OFFLINE
        advanceTimeBy(4.9.seconds)
        assertEquals(NetworkAccess.ONLINE, watch.access.value, "still inside the grace")
        advanceTimeBy(1.seconds)
        assertEquals(NetworkAccess.OFFLINE, watch.access.value)
    }

    @Test
    fun the_return_clears_at_once_and_is_announced_once() = runTest {
        val watch = watching()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.OFFLINE
        advanceTimeBy(6.seconds)
        network.operator.access = NetworkAccess.ONLINE
        runCurrent()
        assertEquals(NetworkAccess.ONLINE, watch.access.value)
        assertEquals(1, returns.size)
    }

    @Test
    fun a_changed_cause_after_a_shown_warning_is_immediate() = runTest {
        val watch = watching()
        network.operator.access = NetworkAccess.OFFLINE
        advanceTimeBy(6.seconds)
        network.operator.access = NetworkAccess.BLOCKED
        runCurrent()
        assertEquals(NetworkAccess.BLOCKED, watch.access.value)
    }

    @Test
    fun opening_offline_waits_the_same_grace() = runTest {
        network.operator.access = NetworkAccess.BLOCKED
        val watch = watching()
        assertEquals(NetworkAccess.ONLINE, watch.access.value, "the first reading is held like any other")
        advanceTimeBy(6.seconds)
        assertEquals(NetworkAccess.BLOCKED, watch.access.value)
    }

    @Test
    fun stop_resets_to_online_without_announcing_a_return_and_stops_following() = runTest {
        val watch = watching()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.OFFLINE
        advanceTimeBy(6.seconds)
        watch.stop()
        assertEquals(NetworkAccess.ONLINE, watch.access.value)
        network.operator.access = NetworkAccess.BLOCKED
        advanceTimeBy(10.seconds)
        assertEquals(NetworkAccess.ONLINE, watch.access.value, "a stopped watch follows nothing")
        assertEquals(emptyList(), returns, "a reset is not a return")
    }

    @Test
    fun repeated_starts_never_stack_watches() = runTest {
        val watch = watching()
        watch.start()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.OFFLINE
        advanceTimeBy(6.seconds)
        network.operator.access = NetworkAccess.ONLINE
        runCurrent()
        assertEquals(1, returns.size, "one watch, one return")
    }
}
