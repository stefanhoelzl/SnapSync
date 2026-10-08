@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.status

import app.snapsync.mock.NetworkMock
import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        NetworkWatch(backgroundScope, NetworkReadings(network.port())).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.returnsOf(watch: NetworkWatch): List<Unit> =
        mutableListOf<Unit>().also { seen ->
            backgroundScope.launch { watch.returned.collect { seen += it } }
            runCurrent()
        }

    @Test
    fun a_two_second_drop_publishes_nothing() = runTest {
        val watch = watching()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.Offline
        advanceTimeBy(2.seconds)
        network.operator.access = NetworkAccess.Online(restricted = false)
        advanceTimeBy(10.seconds)
        assertEquals(NetworkAccess.Online(restricted = false), watch.access.value)
        assertEquals(emptyList(), returns, "nothing was shown, so nothing returned")
    }

    @Test
    fun a_six_second_drop_publishes_offline_after_the_grace() = runTest {
        val watch = watching()
        network.operator.access = NetworkAccess.Offline
        advanceTimeBy(4.9.seconds)
        assertEquals(NetworkAccess.Online(restricted = false), watch.access.value, "still inside the grace")
        advanceTimeBy(1.seconds)
        assertEquals(NetworkAccess.Offline, watch.access.value)
    }

    @Test
    fun the_return_clears_at_once_and_is_announced_once() = runTest {
        val watch = watching()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.Offline
        advanceTimeBy(6.seconds)
        network.operator.access = NetworkAccess.Online(restricted = false)
        runCurrent()
        assertEquals(NetworkAccess.Online(restricted = false), watch.access.value)
        assertEquals(1, returns.size)
    }

    @Test
    fun a_changed_cause_after_a_shown_warning_is_immediate() = runTest {
        val watch = watching()
        network.operator.access = NetworkAccess.Offline
        advanceTimeBy(6.seconds)
        network.operator.access = NetworkAccess.Blocked
        runCurrent()
        assertEquals(NetworkAccess.Blocked, watch.access.value)
    }

    @Test
    fun opening_offline_waits_the_same_grace() = runTest {
        network.operator.access = NetworkAccess.Blocked
        val watch = watching()
        assertEquals(
            NetworkAccess.Online(restricted = false),
            watch.access.value,
            "the first reading is held like any other",
        )
        advanceTimeBy(6.seconds)
        assertEquals(NetworkAccess.Blocked, watch.access.value)
    }

    @Test
    fun stop_resets_to_online_without_announcing_a_return_and_stops_following() = runTest {
        val watch = watching()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.Offline
        advanceTimeBy(6.seconds)
        watch.stop()
        assertEquals(NetworkAccess.Online(restricted = false), watch.access.value)
        network.operator.access = NetworkAccess.Blocked
        advanceTimeBy(10.seconds)
        assertEquals(NetworkAccess.Online(restricted = false), watch.access.value, "a stopped watch follows nothing")
        assertEquals(emptyList(), returns, "a reset is not a return")
    }

    @Test
    fun repeated_starts_never_stack_watches() = runTest {
        val watch = watching()
        watch.start()
        val returns = returnsOf(watch)
        network.operator.access = NetworkAccess.Offline
        advanceTimeBy(6.seconds)
        network.operator.access = NetworkAccess.Online(restricted = false)
        runCurrent()
        assertEquals(1, returns.size, "one watch, one return")
    }

    @Test
    fun a_watch_whose_readings_ended_is_watched_afresh_at_the_next_start() = runTest {
        // A monitor's stream may end; start is a no-op only while a previous watch is still live.
        var watches = 0
        val ending = object : NetworkMonitor {
            override fun watch(): Flow<NetworkAccess> = flow {
                watches++
                emit(if (watches == 1) NetworkAccess.Online(restricted = false) else NetworkAccess.Blocked)
            }
        }
        val watch = NetworkWatch(backgroundScope, NetworkReadings(ending))
        watch.start()
        runCurrent()
        watch.start()
        advanceTimeBy(6.seconds)
        assertEquals(2, watches)
        assertEquals(NetworkAccess.Blocked, watch.access.value, "the fresh watch is followed")
    }
}
