package app.snapsync.ios.registry

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.UploadExtensionRegistryContract
import app.snapsync.contracts.UploadExtensionRegistryState
import app.snapsync.contracts.verify
import app.snapsync.ports.UploadExtensionRegistry
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The simulator target's registration substitute held to the same clauses the device's recording is
 * (capability `port-contracts`): the double every simulator scenario registers through is licensed by the
 * contract its real implementation passes on a device, or this fails naming the clause it answers differently.
 *
 * Each clause gets a fresh [SimulatorRecord] in its state — never the process-wide one the rig's levers reach.
 * The substitute models no photo grant, so the partial-grant refusal is not a state it can enter.
 */
class SimulatorExtensionRegistryContractTest {

    private val binding = object : Binding<UploadExtensionRegistryState, UploadExtensionRegistry> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Fake
        override val reaches = setOf(UploadExtensionRegistryState.RECORD_ABSENT, UploadExtensionRegistryState.RECORD_PRESENT)

        override fun create(state: UploadExtensionRegistryState, clauseId: String): Entered<UploadExtensionRegistry> =
            when (state) {
                UploadExtensionRegistryState.RECORD_ABSENT ->
                    Entered.Ready(SimulatorExtensionRegistry(Logger.withTag("contract"), SimulatorRecord(registered = false)))
                UploadExtensionRegistryState.RECORD_PRESENT ->
                    Entered.Ready(SimulatorExtensionRegistry(Logger.withTag("contract"), SimulatorRecord(registered = true)))
                UploadExtensionRegistryState.UNDER_PARTIAL_GRANT ->
                    Entered.Unreachable("the simulator substitute models no photo grant, and a simulator has no partial one")
            }
    }

    @Test
    fun `the simulator registration substitute satisfies the contract`() = verify(UploadExtensionRegistryContract, binding)
}
