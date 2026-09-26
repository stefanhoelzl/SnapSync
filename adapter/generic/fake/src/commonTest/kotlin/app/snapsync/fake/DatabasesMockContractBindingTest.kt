package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import kotlin.test.Test

/** The in-memory databases hold to the same contract the file-backed adapters do — an unopenable one by its lever. */
class DatabasesMockContractBindingTest {

    private val binding = object : Binding<DatabasesState, Databases> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DatabasesState.ABSENT, DatabasesState.CURRENT, DatabasesState.OLD, DatabasesState.UNOPENABLE)
        override fun create(state: DatabasesState, clauseId: String): Entered<Databases> {
            val databases = when (state) {
                DatabasesState.UNOPENABLE -> inMemoryDatabases(mapOf(DatabasesContract.NAME to DbOpen.Failed("unopenable")))
                else -> inMemoryDatabases()
            }
            when (state) {
                DatabasesState.CURRENT -> DatabasesContract.enterCurrent(databases)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.ABSENT, DatabasesState.UNOPENABLE -> Unit
            }
            return Entered.Ready(databases)
        }
    }

    @Test
    fun `the in-memory databases satisfy the Databases contract`() = verify(DatabasesContract, binding)
}
