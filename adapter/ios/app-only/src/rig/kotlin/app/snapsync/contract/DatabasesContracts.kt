package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.DatabasesSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.databases.IosDatabases

/**
 * The `Databases` contract where a commit's Darwin notice can travel: the simulator APP, a launchd job like the app and
 * the extension on a phone. The simulator's test executable is not one — libnotify answers its every call with
 * `NOTIFY_STATUS_OPT_DISABLE` (measured on CI's macos-26 simulator, 2026-10-11) — so the two-process state runs here,
 * and the own-commit clause with it, where silence means the filter held rather than that nothing was posted. Every
 * other state is the test executable's.
 */
class SimAppDatabasesBinding : Binding<DatabasesState, DatabasesSubject> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(DatabasesState.CURRENT, DatabasesState.SHARED)

    override fun create(state: DatabasesState, clauseId: String, log: CallLog): Entered<DatabasesSubject> {
        when (state) {
            DatabasesState.CURRENT, DatabasesState.SHARED -> Unit
            DatabasesState.ABSENT, DatabasesState.OLD, DatabasesState.NEWER, DatabasesState.UNOPENABLE ->
                return Entered.Unreachable(ONE_PROCESS_STATES)
        }
        val dir = scratch("Databases", clauseId)
        val databases = IosDatabases(dir).recorded(log)
        DatabasesContract.enterCurrent(databases)
        return Entered.Ready(DatabasesSubject(databases, elsewhere = { IosDatabases(dir) }))
    }
}

private const val ONE_PROCESS_STATES = "a one-process state: the simulator's test executable runs it"
