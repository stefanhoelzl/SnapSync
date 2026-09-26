package app.snapsync.ios.registry

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ExtensionRegistryContract
import app.snapsync.contracts.ExtensionRegistryState
import app.snapsync.contracts.verify
import app.snapsync.ports.ExtensionRegistry
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The simulator target's registration substitute held to the same clauses the device's recording is
 * (`docs/architecture.md`): the double every simulator scenario registers through is licensed by the
 * contract its real implementation passes on a device, or this fails naming the clause it answers differently.
 *
 * Each clause gets a fresh [SimulatorRecord] in its state — never the process-wide one the rig's levers reach.
 * The substitute models no photo grant, so the partial-grant refusal is not a state it can enter.
 */
class SimulatorExtensionRegistryContractTest {

    private val binding = object : Binding<ExtensionRegistryState, ExtensionRegistry> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Fake
        override val reaches = setOf(ExtensionRegistryState.RECORD_ABSENT, ExtensionRegistryState.RECORD_PRESENT)

        override fun create(state: ExtensionRegistryState, clauseId: String): Entered<ExtensionRegistry> =
            when (state) {
                ExtensionRegistryState.RECORD_ABSENT ->
                    Entered.Ready(SimulatorExtensionRegistry(Logger.withTag("contract"), SimulatorRecord(registered = false)))
                ExtensionRegistryState.RECORD_PRESENT ->
                    Entered.Ready(SimulatorExtensionRegistry(Logger.withTag("contract"), SimulatorRecord(registered = true)))
                ExtensionRegistryState.UNDER_PARTIAL_GRANT ->
                    Entered.Unreachable("the simulator substitute models no photo grant, and a simulator has no partial one")
            }
    }

    @Test
    fun `the simulator registration substitute satisfies the contract`() = verify(ExtensionRegistryContract, binding)
}
