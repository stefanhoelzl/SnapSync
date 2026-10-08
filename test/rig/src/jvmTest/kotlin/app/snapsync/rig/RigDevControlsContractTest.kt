package app.snapsync.rig

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DevControlsContract
import app.snapsync.contracts.DevControlsState
import app.snapsync.contracts.DevControlsUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import kotlin.test.Test

/**
 * A rig build's developer controls — [RigDevControls], the one implementation that switches anything — against the
 * contract, its controls set as the channel's verbs set them and its reset delivered as `/device/reset` delivers it.
 */
class RigDevControlsContractTest {

    private val binding = object : Binding<DevControlsState, DevControlsUnderTest> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(DevControlsState.SWITCHED)
        override fun create(state: DevControlsState, clauseId: String): Entered<DevControlsUnderTest> {
            if (state !in reaches) return Entered.Unreachable("a rig build's controls are the channel's to set")
            val controls = RigDevControls().apply {
                pin = DevControlsContract.PINNED
                encrypts = false
            }
            return Entered.Ready(DevControlsUnderTest(controls, resetFromChannel = controls::reset))
        }
    }

    @Test
    fun `a rig build's controls satisfy the DevControls contract`() = verify(DevControlsContract, binding)
}
