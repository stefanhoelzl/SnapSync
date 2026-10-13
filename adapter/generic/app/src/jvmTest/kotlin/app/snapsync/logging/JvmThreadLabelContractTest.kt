package app.snapsync.logging

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ThreadLabelContract
import app.snapsync.contracts.ThreadLabelState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.ThreadLabel
import kotlin.test.Test

/** The JVM root's per-thread log label — a [ThreadLocal] — against the contract. */
class JvmThreadLabelContractTest {

    private val binding = object : Binding<ThreadLabelState, ThreadLabel> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val adapter = JvmThreadLabel::class
        override val reaches = setOf(ThreadLabelState.EMPTY)
        override fun create(state: ThreadLabelState, clauseId: String, log: CallLog): Entered<ThreadLabel> =
            Entered.Ready(JvmThreadLabel().recorded(log))
    }

    @Test
    fun `it satisfies the ThreadLabel contract`() = verify(ThreadLabelContract, binding)
}
