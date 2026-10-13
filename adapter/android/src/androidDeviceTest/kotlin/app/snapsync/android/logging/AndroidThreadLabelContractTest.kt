package app.snapsync.android.logging

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

/** The app's per-thread log label — a [ThreadLocal] in the app process — against the [ThreadLabelContract]. */
class AndroidThreadLabelContractTest {

    private val binding = object : Binding<ThreadLabelState, ThreadLabel> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidThreadLabel::class
        override val reaches = setOf(ThreadLabelState.EMPTY)
        override fun create(state: ThreadLabelState, clauseId: String, log: CallLog): Entered<ThreadLabel> =
            Entered.Ready(AndroidThreadLabel().recorded(log))
    }

    @Test
    fun `the app’s thread label satisfies the ThreadLabel contract`() = verify(ThreadLabelContract, binding)
}
