package app.snapsync.compose

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.EntryContextContract
import app.snapsync.contracts.EntryContextState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.EntryContext
import kotlin.test.Test

/**
 * The entry context Android and the JVM root compose — [NoEntryContext], a production implementation, not a double:
 * those processes tag no log line with its trigger — against the contract.
 */
class NoEntryContextContractTest {

    private val binding = object : Binding<EntryContextState, EntryContext> {
        override val host = currentHost
        override val kind = BindingKind.Live
        override val reaches = setOf(EntryContextState.NO_CONTEXT)
        override fun create(state: EntryContextState, clauseId: String): Entered<EntryContext> =
            if (state in reaches) {
                Entered.Ready(
                    NoEntryContext,
                )
            } else {
                Entered.Unreachable("this process keeps no context")
            }
    }

    @Test
    fun `the no-context scope satisfies the EntryContext contract`() = verify(EntryContextContract, binding)
}
