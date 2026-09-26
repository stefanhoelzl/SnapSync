package app.snapsync.world

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.databases.JdbcDatabases
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Forcing the composition opens no database** (`docs/architecture.md`): the storage services open through
 * `Databases` on first use, so building the app's core and the upload cycle over them opens nothing. A locked
 * background launch must not be forced into an open by composition — and a later phase that composes eagerly
 * (the listening phases) relies on this staying true.
 */
class CompositionOpensNoDatabaseTest {

    /** The real JVM adapter, counting its opens. */
    private class Counting(private val inner: Databases) : Databases {
        val opens = mutableListOf<String>()
        override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen {
            opens += name
            return inner.open(name, schema, readOnly)
        }
    }

    @Test
    fun forcing_the_composition_opens_no_database_and_first_use_does() = worldTest {
        val databases = Counting(JdbcDatabases(Files.createTempDirectory("composition").toFile().also { it.deleteOnExit() }))
        val w = World(this, databases = databases)

        w.core
        w.cycle
        assertEquals(emptyList(), databases.opens, "the composition opened a database")

        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        assertTrue("ledger.db" in databases.opens, "the first cycle is what opens the ledger")
    }
}
