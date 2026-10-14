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
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test

/** Runs [DatabasesContract] against the real [JdbcDatabases], each clause in a directory of its own. */
class JdbcDatabasesContractTest {

    private val binding = object : Binding<DatabasesState, DatabasesSubject> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val adapter = JdbcDatabases::class
        override val reaches = setOf(
            DatabasesState.ABSENT,
            DatabasesState.CURRENT,
            DatabasesState.OLD,
            DatabasesState.NEWER,
            DatabasesState.UNOPENABLE,
            DatabasesState.SHARED,
            DatabasesState.CONTENDED,
        )
        override fun create(state: DatabasesState, clauseId: String, log: CallLog): Entered<DatabasesSubject> {
            val dir = Files.createTempDirectory("databases-contract").toFile()
            val databases = JdbcDatabases(dir).recorded(log)
            when (state) {
                DatabasesState.ABSENT -> Unit
                DatabasesState.CURRENT, DatabasesState.SHARED, DatabasesState.CONTENDED -> DatabasesContract.enterCurrent(
                    databases,
                )
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.NEWER -> DatabasesContract.enterNewer(databases)
                DatabasesState.UNOPENABLE -> File(
                    dir,
                    DatabasesContract.NAME,
                ).writeText("this is not a database, and it is long enough to have a header\n".repeat(8))
            }
            return Entered.Ready(
                DatabasesSubject(
                    databases,
                    elsewhere = { JdbcDatabases(dir) },
                    contend = { holdWriteLock(File(dir, DatabasesContract.NAME)) },
                ),
            ) { dir.deleteRecursively() }
        }
    }

    /**
     * Another writer mid-commit on [file]: a plain JDBC connection — the driver's own hands out a connection per
     * statement outside a transaction, so it cannot hold one — that began, wrote, and has not ended.
     */
    private fun holdWriteLock(file: File): () -> Unit {
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
        connection.createStatement().use {
            it.execute("BEGIN IMMEDIATE")
            it.execute("INSERT INTO probe (id, v) VALUES (${DatabasesContract.HELD_ROW}, 'held')")
        }
        return {
            connection.createStatement().use { it.execute("ROLLBACK") }
            connection.close()
        }
    }

    @Test
    fun `satisfies the Databases contract`() = verify(DatabasesContract, binding)
}
