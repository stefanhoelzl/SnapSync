package app.snapsync.fake

import app.snapsync.contracts.BackgroundSchedulerContract
import app.snapsync.contracts.BackgroundSchedulerState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/** The honest [app.snapsync.ports.BackgroundScheduler], held to the contract `IosBackgroundScheduler` satisfies. */
class BackgroundSchedulerContractBindingTest {

    private val binding = object : Binding<BackgroundSchedulerState, ScheduledWakes> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(BackgroundSchedulerState.EMPTY)

        override fun create(state: BackgroundSchedulerState, clauseId: String): Entered<ScheduledWakes> {
            val armed = MutableStateFlow(false)
            return Entered.Ready(ScheduledWakes(inMemoryBackgroundScheduler(armed)) { if (armed.value) 1 else 0 })
        }
    }

    @Test
    fun `the honest scheduler satisfies the contract`() = verify(BackgroundSchedulerContract, binding)
}
