package app.snapsync.databases

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
import app.snapsync.ports.DbOpen
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.withTempDirectory
import app.snapsync.testsupport.writeTextFile
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs [DatabasesContract] against the real [IosDatabases] (the native driver and SQLiter's version check), each
 * clause in a directory of its own — the App-Group container's stand-in, since a test binary has no entitlement.
 */
class IosDatabasesContractTest {

    private val binding = object : Binding<DatabasesState, DatabasesSubject> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosDatabases::class
        override val reaches = setOf(
            DatabasesState.ABSENT,
            DatabasesState.CURRENT,
            DatabasesState.OLD,
            DatabasesState.NEWER,
            DatabasesState.UNOPENABLE,
            DatabasesState.CONTENDED,
        )
        override fun create(state: DatabasesState, clauseId: String, log: CallLog): Entered<DatabasesSubject> {
            val dir = newTempDirectory()
            val databases = IosDatabases(dir).recorded(log)
            when (state) {
                DatabasesState.ABSENT -> Unit
                DatabasesState.CURRENT, DatabasesState.CONTENDED -> DatabasesContract.enterCurrent(databases)
                // A test executable is no launchd job: libnotify answers it `NOTIFY_STATUS_OPT_DISABLE`, so a commit's
                // notice cannot travel here. The simulator app runs this state (`SimAppDatabasesBinding`).
                DatabasesState.SHARED -> return Entered.Unreachable("libnotify is disabled in a test executable")
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.NEWER -> DatabasesContract.enterNewer(databases)
                DatabasesState.UNOPENABLE ->
                    writeTextFile(
                        "$dir/${DatabasesContract.NAME}",
                        "this is not a database, and it is long enough to have a header\n".repeat(8),
                    )
            }
            return Entered.Ready(
                DatabasesSubject(databases, contend = { holdWriteLock(IosDatabases(dir)) }),
            ) { removeDirectory(dir) }
        }
    }

    /**
     * Another writer mid-commit: a second instance's connection to the same file that began (`BEGIN IMMEDIATE`), wrote,
     * and has not ended. Raw statements, not the driver's transaction, which ends with its block: the native driver runs
     * a statement outside a transaction on its one writer connection, which keeps the transaction open between calls.
     */
    private fun holdWriteLock(other: IosDatabases): () -> Unit {
        val opened = assertIs<DbOpen.Opened>(
            other.open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = false),
        )
        opened.driver.execute(null, "BEGIN IMMEDIATE", 0)
        opened.driver.execute(null, "INSERT INTO probe (id, v) VALUES (${DatabasesContract.HELD_ROW}, 'held')", 0)
        return {
            opened.driver.execute(null, "ROLLBACK", 0)
            opened.driver.close()
        }
    }

    @Test
    fun `satisfies the Databases contract`() = verify(DatabasesContract, binding)

    /**
     * **Placement** — the failure a contract clause cannot see from inside the port: the base path travels
     * through the driver's `onConfiguration` into `extendedConfig.basePath`, and a copy that dropped it would open
     * a database in the process's private sandbox, where every read and write succeeds and the app and the
     * extension silently stop sharing it.
     */
    @Test
    fun `the database file lands where the container says`() {
        withTempDirectory { dir ->
            val opened = IosDatabases(dir).open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = false)
            assertIs<DbOpen.Opened>(opened).driver.close()
            assertTrue(
                fileExists("$dir/${DatabasesContract.NAME}"),
                "a driver that ignored the base path shares nothing",
            )
        }
    }

    /** A build without the App-Group entitlement fails every open, naming it — never a private database. */
    @Test
    fun `no container fails every open`() {
        assertIs<DbOpen.Failed>(
            IosDatabases(null).open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = false),
        )
        assertIs<DbOpen.Failed>(
            IosDatabases(null).open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = true),
        )
    }
}
