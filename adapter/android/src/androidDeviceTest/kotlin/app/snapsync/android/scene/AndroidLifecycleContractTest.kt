package app.snapsync.android.scene

import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LifecycleContract
import app.snapsync.contracts.LifecycleState
import app.snapsync.contracts.LifecycleUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * [AndroidLifecycle] against the [LifecycleContract], moved through the two entries the process lifecycle's resume and
 * pause call — the observer [AndroidLifecycle.listen] installs forwards to exactly these.
 */
class AndroidLifecycleContractTest {

    private val binding = object : Binding<LifecycleState, LifecycleUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(LifecycleState.MOVABLE)
        override fun create(state: LifecycleState, clauseId: String, log: CallLog): Entered<LifecycleUnderTest> {
            val lifecycle = AndroidLifecycle(Logger.withTag("contract"))
            return Entered.Ready(
                LifecycleUnderTest(
                    MainThreadListen(lifecycle).recorded(log),
                    becomeActive = { lifecycle.deliverForeground() },
                    resignActive = { lifecycle.deliverBackground() },
                ),
            )
        }
    }

    @Test
    fun `the process lifecycle satisfies the Lifecycle contract`() = verify(LifecycleContract, binding)

    /** [lifecycle], listened to on the main thread, where the process lifecycle takes observers — as the root does. */
    private class MainThreadListen(private val lifecycle: AndroidLifecycle) : Lifecycle by lifecycle {
        override fun listen(handlers: LifecycleHandlers) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync { lifecycle.listen(handlers) }
    }
}
