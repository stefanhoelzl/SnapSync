package app.snapsync.dates

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DateFormattingContract
import app.snapsync.contracts.DateFormattingState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.DateFormatting
import kotlin.test.Test

/** [DateFormattingContract] against the real [IosDateFormatting]: Foundation's CLDR data, in the simulator's test executable. */
class IosDateFormattingContractTest {

    private val binding = object : Binding<DateFormattingState, DateFormatting> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(DateFormattingState.PLATFORM)
        override fun create(state: DateFormattingState, clauseId: String, log: CallLog): Entered<DateFormatting> =
            Entered.Ready(IosDateFormatting().recorded(log))
    }

    @Test
    fun `it satisfies the DateFormatting contract`() = verify(DateFormattingContract, binding)
}
