package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.SystemUi
import kotlin.test.Test

/**
 * The inert hand-off every off-device composition stands on — the world and both desktop harnesses —
 * held to the contract the iOS adapter satisfies (`docs/architecture.md`).
 *
 * [SystemUi.None] has no platform, so it reaches only the state in which nothing is opened. It has no share
 * binding: the share contract's one state needs a surface to present over, and an inert composition has none.
 */
class HandoffContractBindingsTest {

    private val none = object : Binding<LinkOpenerState, SystemUi> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(LinkOpenerState.UNCLAIMED)

        override fun create(state: LinkOpenerState, clauseId: String): Entered<SystemUi> = when (state) {
            LinkOpenerState.UNCLAIMED -> Entered.Ready(SystemUi.None)
            LinkOpenerState.CLAIMED -> Entered.Unreachable("an inert composition has no app to hand a URL to")
        }
    }

    @Test
    fun `the inert system UI satisfies the LinkOpener contract where it reaches`() = verify(LinkOpenerContract, none)
}
