package app.snapsync.ports

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Settles the design's one open runtime question (design record `hold-os-receipts-until-work-completes`,
 * *Open Questions*): **a callback that arrives after its wait was abandoned must be a clean no-op.**
 *
 * The import deadline (capability `photo-download`) bounds the wait for PhotoKit's completion handler,
 * not the library call. So a stalled commit can complete minutes later and invoke a continuation nobody
 * is waiting on any more. If that threw — or worse, resumed something — abandoning a wait would be
 * unsafe and the deadline could not exist.
 *
 * This is the same shape as `IosPhotoLibraryImporter.import`, with the platform call replaced by a
 * captured continuation, so it runs on JVM **and** `iosSimulatorArm64` — where the real answer lives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LateResumeAfterTimeoutTest {

    @Test
    fun `a completion arriving after the deadline is a harmless no-op`() = runTest {
        var captured: CancellableContinuation<String>? = null

        val result = withTimeoutOrNull(5.seconds) {
            suspendCancellableCoroutine { cont ->
                captured = cont // the platform keeps this and calls back whenever it likes
            }
        }
        assertNull(result, "the wait was abandoned at its deadline")

        val cont = captured
        assertTrue(cont != null && cont.isCancelled, "the abandoned continuation is cancelled")

        // The platform finally answers, long after we stopped waiting. Resuming a cancelled
        // continuation must neither throw nor deliver — anything else would make the deadline unsafe.
        cont.resume("late answer") { _, _, _ -> }
        advanceTimeBy(1.seconds)
    }
}
