package app.snapsync.scene

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
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * [IosLifecycle] against the [LifecycleContract] in the simulator's test executable: the app's active state moved
 * through the entries `didBecomeActive` and `willResignActive` deliver to.
 */
class IosLifecycleContractTest {

    private val binding = object : Binding<LifecycleState, LifecycleUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosLifecycle::class
        override val reaches = setOf(LifecycleState.MOVABLE)
        override fun create(state: LifecycleState, clauseId: String, log: CallLog): Entered<LifecycleUnderTest> {
            val lifecycle = IosLifecycle(SceneRecord(), Logger.withTag("contract"))
            return Entered.Ready(
                LifecycleUnderTest(
                    lifecycle.recorded(log),
                    becomeActive = { lifecycle.deliverForeground() },
                    resignActive = { lifecycle.deliverBackground() },
                ),
            )
        }
    }

    @Test
    fun `the scene's transitions satisfy the Lifecycle contract`() = verify(LifecycleContract, binding)
}
