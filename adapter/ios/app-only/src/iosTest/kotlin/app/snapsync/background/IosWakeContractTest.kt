package app.snapsync.background

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The real [IosWake] over the real `BGTaskScheduler`, in the simulator's test executable: the two states a process with
 * no `Info.plist` presents without any call to record — a wake iOS does not have (asked of no one), and a heartbeat
 * request the system refuses, because this binary's bundle permits no task identifier. Every other state needs the
 * entitled app, and is the device recording's.
 */
class IosWakeContractTest {

    private val binding = object : Binding<WakeState, ScheduledWakes> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(WakeState.NO_LIBRARY_WAKE, WakeState.REFUSING)

        override fun create(state: WakeState, clauseId: String, log: CallLog): Entered<ScheduledWakes> {
            if (state !in reaches) return Entered.Unreachable("a test executable's bundle permits no task identifier")
            // Neither clause reads the queue: a test executable's pending-request query has no app to answer for.
            val wake = IosWake(Logger.withTag("contract")).recorded(log)
            return Entered.Ready(ScheduledWakes(wake) { error("the queue is not read here") })
        }
    }

    @Test
    fun `BGTaskScheduler in a test executable satisfies the Wake contract's states it presents`() =
        verify(WakeContract, binding)
}
