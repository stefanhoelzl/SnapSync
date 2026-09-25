package app.snapsync.databases

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.ports.Databases
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

    private val binding = object : Binding<DatabasesState, Databases> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(DatabasesState.ABSENT, DatabasesState.CURRENT, DatabasesState.OLD, DatabasesState.UNOPENABLE)
        override fun create(state: DatabasesState, clauseId: String): Entered<Databases> {
            val dir = newTempDirectory()
            val databases = IosDatabases(dir)
            when (state) {
                DatabasesState.ABSENT -> Unit
                DatabasesState.CURRENT -> DatabasesContract.enterCurrent(databases)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.UNOPENABLE ->
                    writeTextFile("$dir/${DatabasesContract.NAME}", "this is not a database, and it is long enough to have a header\n".repeat(8))
            }
            return Entered.Ready(databases) { removeDirectory(dir) }
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
            assertTrue(fileExists("$dir/${DatabasesContract.NAME}"), "a driver that ignored the base path shares nothing")
        }
    }

    /** A build without the App-Group entitlement fails every open, naming it — never a private database. */
    @Test
    fun `no container fails every open`() {
        assertIs<DbOpen.Failed>(IosDatabases(null).open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = false))
        assertIs<DbOpen.Failed>(IosDatabases(null).open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = true))
    }
}
