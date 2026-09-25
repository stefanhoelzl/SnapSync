package app.snapsync.contracts

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The states the database [DatabasesContract.NAME] can be found in, as far as a clause cares. */
enum class DatabasesState {
    /** No database of that name exists. */
    ABSENT,

    /** It exists at [DatabasesContract.Current]'s version, holding one row ([DatabasesContract.enterCurrent]). */
    CURRENT,

    /** It exists at [DatabasesContract.Old]'s version, holding one row ([DatabasesContract.enterOld]). */
    OLD,

    /** A file of that name exists and is not a database — the stand-in for "exists, cannot be opened". */
    UNOPENABLE,
}

/**
 * What a `Databases` promises (`docs/architecture.md`; the port's KDoc carries why).
 *
 * The obligations a lost or echoed photo would turn on: a read-write open creates what is missing and migrates
 * what is old **keeping its rows**; a read-only open creates nothing and migrates nothing, answering `Missing` and
 * `OldSchema` instead; and a database that exists but cannot be opened is `Failed` on both paths — never
 * `Missing`, which a reader would take for "nothing was ever written".
 *
 * The contract brings its own two-version schema, so a clause needs nothing from production's databases. A
 * binding enters [DatabasesState.CURRENT] and [DatabasesState.OLD] through the port itself ([enterCurrent],
 * [enterOld]) in a directory of its own; [DatabasesState.UNOPENABLE] needs a platform file write, the binding's.
 */
object DatabasesContract : Contract<DatabasesState, Databases>("Databases") {

    /** The one database every clause addresses. */
    const val NAME = "contract-probe.db"

    /** The first version of the probe schema: one table, two columns. */
    object Old : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L
        override fun create(driver: SqlDriver) = QueryResult.Value(
            driver.execute(null, "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT NOT NULL)", 0).let { },
        )

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
            QueryResult.Value(Unit)
    }

    /** The second version: a column added by migration, so a migrated database and a created one agree. */
    object Current : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 2L
        override fun create(driver: SqlDriver) = QueryResult.Value(
            driver.execute(null, "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT NOT NULL, w TEXT)", 0).let { },
        )

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
            QueryResult.Value(
                if (oldVersion < 2 && newVersion >= 2) {
                    driver.execute(null, "ALTER TABLE probe ADD COLUMN w TEXT", 0).let { }
                } else {
                    Unit
                },
            )
    }

    /** The row [enterOld] and [enterCurrent] leave behind. */
    const val KEPT = "kept"

    /** Enter [DatabasesState.OLD]: create [NAME] at [Old] with one row, then close it. */
    fun enterOld(databases: Databases) = seed(databases, Old)

    /** Enter [DatabasesState.CURRENT]: create [NAME] at [Current] with one row, then close it. */
    fun enterCurrent(databases: Databases) = seed(databases, Current)

    private fun seed(databases: Databases, schema: SqlSchema<QueryResult.Value<Unit>>) {
        val opened = databases.open(NAME, schema, readOnly = false)
        check(opened is DbOpen.Opened) { "could not enter the state: $opened" }
        opened.driver.execute(null, "INSERT INTO probe (id, v) VALUES (1, '$KEPT')", 0)
        opened.driver.close()
    }

    private fun SqlDriver.kept(): String? =
        executeQuery(null, "SELECT v FROM probe WHERE id = 1", { c ->
            QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value

    private fun SqlDriver.hasColumnW(): Boolean =
        executeQuery(null, "SELECT count(*) FROM pragma_table_info('probe') WHERE name = 'w'", { c ->
            QueryResult.Value(c.next().value && (c.getLong(0) ?: 0L) > 0L)
        }, 0).value

    private fun Databases.opened(schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): SqlDriver {
        val opened = open(NAME, schema, readOnly)
        assertIs<DbOpen.Opened>(opened, "expected the database to open (readOnly=$readOnly), got $opened")
        return opened.driver
    }

    override val clauses = clauses {

        clause("ABSENT_READ_WRITE_CREATES_IT", DatabasesState.ABSENT) { databases ->
            val driver = databases.opened(Current, readOnly = false)
            driver.execute(null, "INSERT INTO probe (id, v, w) VALUES (1, '$KEPT', 'x')", 0)
            driver.close()
            val again = databases.opened(Current, readOnly = true)
            assertEquals(KEPT, again.kept(), "a created database keeps what was written to it")
            again.close()
        }

        clause("ABSENT_READ_ONLY_IS_MISSING_AND_CREATES_NOTHING", DatabasesState.ABSENT) { databases ->
            assertEquals(DbOpen.Missing, databases.open(NAME, Current, readOnly = true))
            assertEquals(
                DbOpen.Missing,
                databases.open(NAME, Current, readOnly = true),
                "a read-only open that created the file would make the next one read an empty database",
            )
        }

        clause("OLD_READ_WRITE_MIGRATES_KEEPING_ROWS", DatabasesState.OLD) { databases ->
            val driver = databases.opened(Current, readOnly = false)
            assertTrue(driver.hasColumnW(), "the migration ran")
            assertEquals(KEPT, driver.kept(), "a migration that loses rows loses every suppression handle")
            driver.close()
        }

        clause("OLD_READ_ONLY_IS_OLD_SCHEMA_AND_MIGRATES_NOTHING", DatabasesState.OLD) { databases ->
            assertEquals(DbOpen.OldSchema, databases.open(NAME, Current, readOnly = true))
            // Still at the old version: the old schema opens it as current, with its row, and without the column.
            val stillOld = databases.opened(Old, readOnly = true)
            assertEquals(KEPT, stillOld.kept())
            assertTrue(!stillOld.hasColumnW(), "a read-only open migrated the database")
            stillOld.close()
        }

        clause("CURRENT_READ_ONLY_OPENS_AND_READS", DatabasesState.CURRENT) { databases ->
            val driver = databases.opened(Current, readOnly = true)
            assertEquals(KEPT, driver.kept())
            driver.close()
        }

        clause("CURRENT_READ_WRITE_REOPENS_WITH_ITS_ROWS", DatabasesState.CURRENT) { databases ->
            val driver = databases.opened(Current, readOnly = false)
            assertEquals(KEPT, driver.kept(), "a re-open is not a re-create")
            driver.close()
        }

        clause("UNOPENABLE_READ_ONLY_FAILS_NEVER_MISSING", DatabasesState.UNOPENABLE) { databases ->
            assertIs<DbOpen.Failed>(
                databases.open(NAME, Current, readOnly = true),
                "an unreadable database answered as missing reads as 'nothing was ever downloaded'",
            )
        }

        clause("UNOPENABLE_READ_WRITE_FAILS", DatabasesState.UNOPENABLE) { databases ->
            assertIs<DbOpen.Failed>(databases.open(NAME, Current, readOnly = false))
        }
    }
}
