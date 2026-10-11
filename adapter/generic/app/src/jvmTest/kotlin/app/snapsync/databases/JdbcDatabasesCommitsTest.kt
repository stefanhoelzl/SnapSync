package app.snapsync.databases

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.DbOpen
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

    private fun JdbcDatabases.opened(): SqlDriver =
        assertIs<DbOpen.Opened>(open("t.db", Schema, readOnly = false)).driver

    @Test
    fun `an instance that never listened is not told and the commit goes on`() {
        val dir = Files.createTempDirectory("databases-commits").toFile()
        JdbcDatabases(dir).opened() // opened, never listened
        val heard = LinkedBlockingQueue<String>()
        JdbcDatabases(dir).apply {
            listen(DatabaseHandlers(onChangedElsewhere = heard::add))
            opened()
        }
        val committer = JdbcDatabases(dir).opened()

        repeat(2) { committer.notifyListeners("t") }

        // Deliveries run in order on one thread: once the second is heard, the silent instance's first ran too.
        assertEquals("t.db", heard.poll(5, TimeUnit.SECONDS))
        assertEquals("t.db", heard.poll(5, TimeUnit.SECONDS))
        dir.deleteRecursively()
    }
}
