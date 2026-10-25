package app.snapsync.entry

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.EntryEventsContract
import app.snapsync.contracts.EntryEventsState
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.EntryEvents
import kotlin.test.Test

/** A shipped build's entry-event sink, which keeps nothing, against the contract. */
class NoEntryEventsContractTest {

    private val binding = object : Binding<EntryEventsState, EntryEvents> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val adapter = NoEntryEvents::class
        override val reaches = setOf(EntryEventsState.FRESH)
        override fun create(state: EntryEventsState, clauseId: String, log: CallLog): Entered<EntryEvents> =
            Entered.Ready(NoEntryEvents.recorded(log))
    }

    @Test
    fun `it satisfies the EntryEvents contract`() = verify(EntryEventsContract, binding)
}
