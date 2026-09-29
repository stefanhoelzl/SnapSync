package app.snapsync.android.storage

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.ports.Databases
import java.io.File
import kotlin.test.Test

/**
 * [DatabasesContract] against the real [AndroidDatabases] on the platform's SQLite, each clause in a directory of its
 * own. [DatabasesState.UNOPENABLE] is the case the platform's default error handler would "repair" by deleting the file
 * and creating an empty database — which the contract's `Failed` clauses catch.
 */
class AndroidDatabasesContractTest {

    private val binding = object : Binding<DatabasesState, Databases> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(DatabasesState.ABSENT, DatabasesState.CURRENT, DatabasesState.OLD, DatabasesState.UNOPENABLE)

        override fun create(state: DatabasesState, clauseId: String): Entered<Databases> {
            val dir = newTempDirectory()
            val databases = AndroidDatabases(context, dir)
            when (state) {
                DatabasesState.ABSENT -> Unit
                DatabasesState.CURRENT -> DatabasesContract.enterCurrent(databases)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.UNOPENABLE -> File(dir, DatabasesContract.NAME)
                    .writeText("this is not a database, and it is long enough to have a header\n".repeat(8))
            }
            return Entered.Ready(databases) { dir.deleteRecursively() }
        }
    }

    @Test
    fun `the platform SQLite satisfies the Databases contract`() = verify(DatabasesContract, binding)
}
