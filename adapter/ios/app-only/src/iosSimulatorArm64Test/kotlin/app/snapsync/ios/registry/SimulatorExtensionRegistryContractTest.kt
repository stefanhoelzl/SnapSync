package app.snapsync.ios.registry

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ExtensionRegistryContract
import app.snapsync.contracts.ExtensionRegistryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.ExtensionRegistry
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The simulator target's registration substitute held to the same clauses the device's recording is
 * (`docs/architecture.md`): the double every simulator scenario registers through is licensed by the
 * contract its real implementation passes on a device, or this fails naming the clause it answers differently.
 *
 * Each clause gets a fresh [SimulatorRecord] in its state — never the process-wide one the rig's levers reach. It is
 * the code the simulator app runs, so its run is a Live one (`docs/testing.md`, "Every adapter is covered"), and it
 * enters every state the real registry answers in — a partial grant, an OS without the mechanism — so a scenario on
 * the simulator can meet each answer a device gives.
 */
class SimulatorExtensionRegistryContractTest {

    private val binding = object : Binding<ExtensionRegistryState, ExtensionRegistry> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = SimulatorExtensionRegistry::class
        override val reaches = setOf(
            ExtensionRegistryState.RECORD_ABSENT,
            ExtensionRegistryState.RECORD_PRESENT,
            ExtensionRegistryState.UNDER_PARTIAL_GRANT,
            ExtensionRegistryState.NO_MECHANISM,
        )

        override fun create(state: ExtensionRegistryState, clauseId: String, log: CallLog): Entered<ExtensionRegistry> {
            val record = when (state) {
                ExtensionRegistryState.RECORD_ABSENT -> SimulatorRecord(registered = false)
                ExtensionRegistryState.RECORD_PRESENT -> SimulatorRecord(registered = true)
                ExtensionRegistryState.UNDER_PARTIAL_GRANT -> SimulatorRecord(partialGrant = true)
                ExtensionRegistryState.NO_MECHANISM -> SimulatorRecord(mechanism = false)
            }
            return Entered.Ready(SimulatorExtensionRegistry(Logger.withTag("contract"), record).recorded(log))
        }
    }

    @Test
    fun `the simulator registration substitute satisfies the contract`() = verify(ExtensionRegistryContract, binding)
}
