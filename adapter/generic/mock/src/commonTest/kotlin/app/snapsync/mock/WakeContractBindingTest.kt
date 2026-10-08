package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/** The honest [app.snapsync.ports.Wake], held to the contract `IosWake` satisfies. */
class WakeContractBindingTest {

    private val binding = object : Binding<WakeState, ScheduledWakes> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(WakeState.EMPTY)

        override fun create(state: WakeState, clauseId: String, log: CallLog): Entered<ScheduledWakes> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            val pending = MutableStateFlow<Map<WakeId, WakeTrigger>>(emptyMap())
            val pendingHeartbeats = { if (WakeId.Heartbeat in pending.value) 1 else 0 }
            return Entered.Ready(ScheduledWakes(inMemoryWake(pending).recorded(log)) { pendingHeartbeats() })
        }
    }

    @Test
    fun `the honest wake satisfies the contract`() = verify(WakeContract, binding)
}
