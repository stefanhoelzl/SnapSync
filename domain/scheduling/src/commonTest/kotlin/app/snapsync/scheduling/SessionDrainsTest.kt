@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.services.transfer

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A session's drain reports, counted (capability `sync-status`, "OS completion handlers are released only after their
 * work completes"): a relaunch waits for the first report after the ones it arrived after — however the relaunch's run
 * and the report race to the core.
 */
class SessionDrainsTest {

    @Test
    fun `a relaunch waits for the first drain report after its own arrival`() = runTest {
        val drains = SessionDrains()
        drains.reported(1)
        var ended = false
        launch {
            drains.awaitAfter(1)
            ended = true
        }
        runCurrent()
        assertFalse(ended, "the report it arrived after does not end its wait")
        drains.reported(2)
        runCurrent()
        assertTrue(ended)
    }

    @Test
    fun `a report that raced ahead of its relaunch's run still ends the wait`() = runTest {
        val drains = SessionDrains()
        drains.reported(1)
        drains.reported(0) // a late, older report changes nothing
        drains.awaitAfter(0)
    }
}
