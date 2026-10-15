@file:OptIn(CompositionOnly::class)

package app.snapsync.databases

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.contracts.inboundOf
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import java.lang.ref.Reference
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the `Databases` contract cannot reach on the JVM adapter: an instance that never listened. A process that opened
 * a database but registered no handlers is not told — and a commit elsewhere does not fail on its account.
 */
class JdbcDatabasesCommitsTest {

    private object Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L
        override fun create(driver: SqlDriver) =
            QueryResult.Value(driver.execute(null, "CREATE TABLE t (id INTEGER PRIMARY KEY)", 0).let { })

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
            QueryResult.Value(Unit)
    }

    private fun JdbcDatabases.opened(): SqlDriver {
        val opened = assertIs<DbOpen.Opened>(open("t.db", Schema, readOnly = false))
        return assertIs<DbResult.Ok<SqlDriver>>(opened.database.execute { it }).value
    }

    @Test
    fun `an instance that never listened is not told and the commit goes on`() {
        val dir = Files.createTempDirectory("databases-commits").toFile()
        val silent = JdbcDatabases(dir).apply { opened() } // opened, never listened
        val heard = LinkedBlockingQueue<String>()
        val listening = JdbcDatabases(dir).apply {
            inbound(DatabaseHandlers(onChangedElsewhere = inboundOf { heard.add(it) }))
            opened()
        }
        val committer = JdbcDatabases(dir).opened()

        repeat(2) { committer.notifyListeners("t") }

        // Deliveries run in order on one thread: once the second is heard, the silent instance's first ran too.
        assertEquals("t.db", heard.poll(5, TimeUnit.SECONDS))
        assertEquals("t.db", heard.poll(5, TimeUnit.SECONDS))
        // The commits remember an open instance only weakly, so both are held until their deliveries ran: one the GC
        // had taken would be told nothing, and the silent instance's branch would go unexercised.
        Reference.reachabilityFence(silent)
        Reference.reachabilityFence(listening)
        dir.deleteRecursively()
    }
}
