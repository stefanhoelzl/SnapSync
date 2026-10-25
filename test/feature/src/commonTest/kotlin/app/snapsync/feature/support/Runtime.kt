@file:OptIn(InboundImplementation::class)

package app.snapsync.feature.support

import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running
import app.snapsync.mock.EntryEventsMock
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.RuntimePorts
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * The process's runtime over the test's scheduler: work it starts [outliving][Runtime.outliving] a caller runs in its
 * scope, over the test's scheduler, and outlives a cancelled caller as it does in the app.
 */
fun TestScope.testRuntime(): Runtime =
    Runtime(
        null,
        StandardTestDispatcher(testScheduler),
        RuntimePorts(NoTime, ThreadLabelMock(), EntryEventsMock().port()),
    )

/** No operating system to ask for time: a test's runs are never expired. */
private object NoTime : BackgroundTime {
    override fun begin(label: String, expiry: Running): BackgroundTimeHold = object : BackgroundTimeHold {
        override fun end() = Unit
    }
}
