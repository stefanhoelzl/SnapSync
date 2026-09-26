package app.snapsync.feature.upload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tail's import signal on its own (capability `receiving-photos`, "a stalled import blocks no other work"). It
 * stays beside the feature because its constructor is `internal`; the runner's rules over it are `TailRunnerTest`'s,
 * in `:test:feature`, which needs the wake port for the heartbeat.
 */
class TailSignalTest {

    @Test
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun `a signal returns at once for a finished import and for a stopped tail`() = runTest {
        var stopped = false
        val signal = TailSignal({ stopped }, kotlin.concurrent.atomics.AtomicReference(CompletableDeferred()))
        assertTrue(signal.awaitUnlessInterrupted(CompletableDeferred(Unit)), "a finished import is simply finished")
        stopped = true
        assertTrue(signal.stopRequested())
        assertFalse(signal.awaitUnlessInterrupted(CompletableDeferred<Unit>()), "a stopped tail waits for nothing")
    }
}
