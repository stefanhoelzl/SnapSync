package app.snapsync.android.storage

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.DatabasesSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import java.io.File
import kotlin.test.Test

/**
 * [DatabasesContract] against the real [AndroidDatabases] on the platform's SQLite, each clause in a directory of its
 * own. [DatabasesState.UNOPENABLE] is the case the platform's default error handler would "repair" by deleting the file
 * and creating an empty database — which the contract's `Failed` clauses catch.
 */
class AndroidDatabasesContractTest {

    private val binding = object : Binding<DatabasesState, DatabasesSubject> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            DatabasesState.ABSENT,
            DatabasesState.CURRENT,
            DatabasesState.OLD,
            DatabasesState.NEWER,
            DatabasesState.UNOPENABLE,
        )

        override fun create(state: DatabasesState, clauseId: String, log: CallLog): Entered<DatabasesSubject> {
            val dir = newTempDirectory()
            val databases = AndroidDatabases(context, dir).recorded(log)
            when (state) {
                DatabasesState.ABSENT -> Unit
                DatabasesState.CURRENT -> DatabasesContract.enterCurrent(databases)
                // One process: the platform has no second one to commit elsewhere, so the binding never reaches it.
                DatabasesState.SHARED -> return Entered.Unreachable(ONE_PROCESS)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.NEWER -> DatabasesContract.enterNewer(databases)
                DatabasesState.UNOPENABLE -> File(dir, DatabasesContract.NAME)
                    .writeText("this is not a database, and it is long enough to have a header\n".repeat(8))
            }
            return Entered.Ready(DatabasesSubject(databases)) { dir.deleteRecursively() }
        }
    }

    @Test
    fun `the platform SQLite satisfies the Databases contract`() = verify(DatabasesContract, binding)
}

private const val ONE_PROCESS = "Android runs one process: nothing commits elsewhere"
