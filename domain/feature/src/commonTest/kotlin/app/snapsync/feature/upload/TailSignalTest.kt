package app.snapsync.feature.upload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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

    @Test
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
    fun `an import that reports while it is awaited is waited for and an interrupt is consumed by one wait`() =
        runTest {
            val interrupts = kotlin.concurrent.atomics.AtomicReference(CompletableDeferred<Unit>())
            val signal = TailSignal({ false }, interrupts)

            val import = CompletableDeferred<Unit>()
            val waited = async { signal.awaitUnlessInterrupted(import) }
            runCurrent()
            assertFalse(waited.isCompleted, "an import still running is awaited")
            import.complete(Unit)
            assertTrue(waited.await(), "it reported, so the unit sees its outcome")

            // A joiner interrupts the next wait — once: the wait after it waits again.
            interrupts.load().complete(Unit)
            assertFalse(signal.awaitUnlessInterrupted(CompletableDeferred<Unit>()), "the interrupt gives the wait up")
            val stalled = CompletableDeferred<Unit>()
            val again = async { signal.awaitUnlessInterrupted(stalled) }
            runCurrent()
            assertFalse(again.isCompleted, "a consumed interrupt does not give up the next wait")
            stalled.complete(Unit)
            assertTrue(again.await())
        }
}
