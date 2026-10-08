package app.snapsync.time

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.ClockContract
import app.snapsync.contracts.ClockState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.Clock
import kotlin.test.Test

/** The process clock every JVM and Android composition binds — [SystemClock] — against the [ClockContract]. */
class SystemClockContractTest {

    private val binding = object : Binding<ClockState, Clock> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(ClockState.RUNNING)
        override fun create(state: ClockState, clauseId: String, log: CallLog): Entered<Clock> = Entered.Ready(
            SystemClock.recorded(log),
        )
    }

    @Test
    fun `the system clock satisfies the Clock contract`() = verify(ClockContract, binding)
}
