package app.snapsync.dev

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DevControlsContract
import app.snapsync.contracts.DevControlsState
import app.snapsync.contracts.DevControlsUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import kotlin.test.Test

/** A shipped build's developer controls — [InertDevControls], every production root's — against the contract. */
class InertDevControlsContractTest {

    private val binding = object : Binding<DevControlsState, DevControlsUnderTest> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(DevControlsState.SHIPPED)
        override fun create(state: DevControlsState, clauseId: String): Entered<DevControlsUnderTest> =
            if (state in reaches) {
                Entered.Ready(DevControlsUnderTest(InertDevControls))
            } else {
                Entered.Unreachable("a shipped build switches nothing, and has no channel to reset from")
            }
    }

    @Test
    fun `a shipped build's controls satisfy the DevControls contract`() = verify(DevControlsContract, binding)
}
