package app.snapsync.scheduling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A latest-wins watch: each value applied once its wait has passed, a newer one cancelling a pending wait. */
class GatedWatchTest {

    private fun TestScope.step(by: Duration) {
        testScheduler.advanceTimeBy(by)
        testScheduler.runCurrent()
    }

    @Test
    fun `a value is applied once its wait has passed, and one that did not last never is`() = runTest {
        val watch = GatedWatch(World(this).runtime)
        val values = MutableStateFlow("a")
        val applied = mutableListOf<String>()
        watch.start(values, { if (it == "slow") 5.seconds else Duration.ZERO }, { applied += it })
        step(Duration.ZERO)
        assertEquals(listOf("a"), applied, "no wait: at once")

        values.value = "slow"
        step(4.seconds)
        values.value = "b"
        step(Duration.ZERO)
        assertEquals(listOf("a", "b"), applied, "a newer value cancels the pending wait")

        values.value = "slow"
        step(5.seconds)
        assertEquals(listOf("a", "b", "slow"), applied, "one that lasts is applied")
    }

    @Test
    fun `a second start while running watches nothing twice, and a stop ends the collection`() = runTest {
        val watch = GatedWatch(World(this).runtime)
        val values = MutableStateFlow(1)
        var collections = 0
        val applied = mutableListOf<Int>()
        val counted = values.onCompletion { collections++ }
        watch.start(counted, { Duration.ZERO }, { applied += it })
        watch.start(counted, { Duration.ZERO }, { applied += it })
        step(Duration.ZERO)
        assertEquals(listOf(1), applied)

        watch.stop()
        watch.stop()
        step(Duration.ZERO)
        assertEquals(1, collections, "the collection ended")
        values.value = 2
        step(Duration.ZERO)
        assertEquals(listOf(1), applied, "nothing is applied once stopped")
    }

    @Test
    fun `a watch whose values ended is watched afresh at the next start`() = runTest {
        val watch = GatedWatch(World(this).runtime)
        val applied = mutableListOf<Int>()
        watch.start(flowOf(1), { Duration.ZERO }, { applied += it })
        step(Duration.ZERO)
        watch.start(flowOf(2), { Duration.ZERO }, { applied += it })
        step(Duration.ZERO)
        assertEquals(listOf(1, 2), applied)
    }
}
