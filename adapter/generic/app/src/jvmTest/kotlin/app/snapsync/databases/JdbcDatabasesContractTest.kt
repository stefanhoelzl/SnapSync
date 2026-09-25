package app.snapsync.databases

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.ports.Databases
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** Runs [DatabasesContract] against the real [JdbcDatabases], each clause in a directory of its own. */
class JdbcDatabasesContractTest {

    private val binding = object : Binding<DatabasesState, Databases> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(DatabasesState.ABSENT, DatabasesState.CURRENT, DatabasesState.OLD, DatabasesState.UNOPENABLE)
        override fun create(state: DatabasesState, clauseId: String): Entered<Databases> {
            val dir = Files.createTempDirectory("databases-contract").toFile()
            val databases = JdbcDatabases(dir)
            when (state) {
                DatabasesState.ABSENT -> Unit
                DatabasesState.CURRENT -> DatabasesContract.enterCurrent(databases)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.UNOPENABLE -> File(dir, DatabasesContract.NAME).writeText("this is not a database, and it is long enough to have a header\n".repeat(8))
            }
            return Entered.Ready(databases) { dir.deleteRecursively() }
        }
    }

    @Test
    fun `satisfies the Databases contract`() = verify(DatabasesContract, binding)
}
