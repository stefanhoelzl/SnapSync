@file:OptIn(InboundImplementation::class)

package app.snapsync.feature.support

import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.scheduling.Runtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/**
 * Runs [block] as a run of the process's runtime does — on the caller's job, so cancelling the caller cancels it, with
 * the runtime in its context — so the work it starts [outliving][app.snapsync.scheduling.outliving] the caller runs in
 * the runtime's scope, over the test's scheduler, and outlives a cancelled caller as it does in the app.
 */
suspend fun <T> underRuntime(block: suspend () -> T): T {
    val scheduler = checkNotNull(currentCoroutineContext()[TestCoroutineScheduler]) { "under a test's scheduler only" }
    val runtime = Runtime(null, StandardTestDispatcher(scheduler), NoTime, ThreadLabelMock())
    return withContext(runtime.scope.coroutineContext.minusKey(Job).minusKey(ContinuationInterceptor)) { block() }
}

/** No operating system to ask for time: a test's runs are never expired. */
private object NoTime : BackgroundTime {
    override fun begin(label: String, expiry: Running): BackgroundTimeHold = object : BackgroundTimeHold {
        override fun end() = Unit
    }
}
