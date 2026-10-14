package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.DatabasesSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.levers.WriteKind
import app.snapsync.ports.DbOpen
import kotlin.test.Test

/** The in-memory databases hold to the same contract the file-backed adapters do — an unopenable one by its lever. */
class DatabasesMockContractBindingTest {

    private val binding = object : Binding<DatabasesState, DatabasesSubject> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = Process::class
        override val reaches =
            setOf(
                DatabasesState.ABSENT,
                DatabasesState.CURRENT,
                DatabasesState.OLD,
                DatabasesState.NEWER,
                DatabasesState.UNOPENABLE,
                DatabasesState.SHARED,
                DatabasesState.CONTENDED,
            )
        override fun create(state: DatabasesState, clauseId: String, log: CallLog): Entered<DatabasesSubject> {
            val device = when (state) {
                DatabasesState.UNOPENABLE -> DatabasesMock(mapOf(DatabasesContract.NAME to DbOpen.Failed("unopenable")))
                else -> DatabasesMock()
            }
            val databases = device.port().recorded(log)
            when (state) {
                DatabasesState.CURRENT, DatabasesState.SHARED, DatabasesState.CONTENDED ->
                    DatabasesContract.enterCurrent(databases)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.NEWER -> DatabasesContract.enterNewer(databases)
                DatabasesState.ABSENT, DatabasesState.UNOPENABLE -> Unit
            }
            return Entered.Ready(
                DatabasesSubject(databases, elsewhere = device::port, contend = { device.contend() }),
            )
        }
    }

    @Test
    fun `the in-memory databases satisfy the Databases contract`() = verify(DatabasesContract, binding)
}

/**
 * [DatabasesState.CONTENDED] as the mock plays it — the `fail` lever, one statement of each kind on the probe table — so
 * the lever a test pulls is held to what a real SQLite under a held write lock was shown to do. Nothing to release: each
 * fault is spent by the statement it fails.
 */
internal fun DatabasesMock.contend(): () -> Unit {
    WriteKind.entries.forEach { fail(DatabasesContract.NAME, "probe", it) }
    return {}
}
