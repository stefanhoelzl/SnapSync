@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    /**
     * It exists at [DatabasesContract.Newer]'s version — a later build's database, as a downgrade leaves it
     * ([DatabasesContract.enterNewer]).
     */
    NEWER,

    /** A file of that name exists and is not a database — the stand-in for "exists, cannot be opened". */
    UNOPENABLE,

    /**
     * As [CURRENT], and a second process opens it too ([DatabasesSubject.elsewhere]) — the app and the upload extension
     * over one App-Group file. A binding whose platform runs one process (Android) does not reach it.
     */
    SHARED,

    /**
     * As [CURRENT], and the binding can make this process's writes fail while the database stays open
     * ([DatabasesSubject.contend]) — on a real platform another writer holding the database's write lock, which SQLite
     * answers with `SQLITE_BUSY` once its busy timeout runs out: the app and the upload extension over one file.
     */
    CONTENDED,
}

/**
 * The port as a clause receives it: [databases], this process's — the one the clause observes — and, where the binding
 * can stand one up, [elsewhere]: another process's own [Databases] over the same storage, a new one each call.
 */
data class DatabasesSubject(
    val databases: Databases,
    val elsewhere: (() -> Databases)? = null,
    /**
     * [DatabasesState.CONTENDED]'s act: from now on, an insert, an update and a delete of the probe table each fail —
     * once, at least — until the returned release is called. A real binding takes the write lock through a connection
     * of its platform's own SQLite to the same file — `BEGIN IMMEDIATE` and an insert of
     * [DatabasesContract.HELD_ROW], never committed — as the other process's writer holds it mid-commit; a double arms
     * its faults.
     */
    val contend: (() -> () -> Unit)? = null,
)

/**
 * What a `Databases` promises (`docs/architecture.md`; the port's KDoc carries why).
 *
 * The obligations a lost or echoed photo would turn on: a read-write open creates what is missing and migrates
 * what is old **keeping its rows**; a read-only open creates nothing and migrates nothing, answering `Missing` and
 * `OldSchema` instead; and a database that exists but cannot be opened is `Failed` on both paths — never
 * `Missing`, which a reader would take for "nothing was ever written".
 *
 * Its probe clauses bring their own two-version schema, so they need nothing from production's databases; the
 * schema clauses ([schemaClauses]) run every statement of production's two, so each platform's SQLite is held to
 * them. A
 * binding enters [DatabasesState.CURRENT] and [DatabasesState.OLD] through the port itself ([enterCurrent],
 * [enterOld]) in a directory of its own; [DatabasesState.UNOPENABLE] needs a platform file write, the binding's.
 */
object DatabasesContract : Contract<DatabasesState, DatabasesSubject>("Databases") {

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

    /** A later build's version, a column past [Current]: what a downgraded app finds. Never migrated to. */
    object Newer : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 3L
        override fun create(driver: SqlDriver) = QueryResult.Value(
            driver.execute(
                null,
                "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT NOT NULL, w TEXT, x TEXT)",
                0,
            ).let { },
        )

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
            QueryResult.Value(Unit)
    }

    /** The row [enterOld], [enterCurrent] and [enterNewer] leave behind. */
    const val KEPT = "kept"

    /** Enter [DatabasesState.OLD]: create [NAME] at [Old] with one row, then close it. */
    fun enterOld(databases: Databases) = seed(databases, Old)

    /** Enter [DatabasesState.CURRENT]: create [NAME] at [Current] with one row, then close it. */
    fun enterCurrent(databases: Databases) = seed(databases, Current)

    /** Enter [DatabasesState.NEWER]: create [NAME] at [Newer] with one row, then close it. */
    fun enterNewer(databases: Databases) = seed(databases, Newer)

    /** The row a contending writer inserts and never commits ([DatabasesSubject.contend]). */
    const val HELD_ROW = 99L

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

    override val clauses = clauses {
        schemaClauses()

        clause(
            "ABSENT_READ_WRITE_CREATES_IT",
            DatabasesState.ABSENT,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Opened::class) },
        ) { (databases) ->
            val driver = databases.opened(Current, readOnly = false)
            driver.execute(null, "INSERT INTO probe (id, v, w) VALUES (1, '$KEPT', 'x')", 0)
            driver.close()
            val again = databases.opened(Current, readOnly = true)
            assertEquals(KEPT, again.kept(), "a created database keeps what was written to it")
            again.close()
        }

        clause(
            "ABSENT_READ_ONLY_IS_MISSING_AND_CREATES_NOTHING",
            DatabasesState.ABSENT,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Missing::class) },
        ) { (databases) ->
            assertEquals(DbOpen.Missing, databases.open(NAME, Current, readOnly = true))
            assertEquals(
                DbOpen.Missing,
                databases.open(NAME, Current, readOnly = true),
                "a read-only open that created the file would make the next one read an empty database",
            )
        }

        clause(
            "OLD_READ_WRITE_MIGRATES_KEEPING_ROWS",
            DatabasesState.OLD,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Opened::class) },
        ) { (databases) ->
            val driver = databases.opened(Current, readOnly = false)
            assertTrue(driver.hasColumnW(), "the migration ran")
            assertEquals(KEPT, driver.kept(), "a migration that loses rows loses every suppression handle")
            driver.close()
        }

        clause(
            "OLD_READ_ONLY_IS_OLD_SCHEMA_AND_MIGRATES_NOTHING",
            DatabasesState.OLD,
            covers = cells {
                on<Databases> {
                    answers(Databases::open).with(DbOpen.OldSchema::class)
                    answers(Databases::open).with(DbOpen.Opened::class)
                }
            },
        ) { (databases) ->
            assertEquals(DbOpen.OldSchema, databases.open(NAME, Current, readOnly = true))
            // Still at the old version: the old schema opens it as current, with its row, and without the column.
            val stillOld = databases.opened(Old, readOnly = true)
            assertEquals(KEPT, stillOld.kept())
            assertTrue(!stillOld.hasColumnW(), "a read-only open migrated the database")
            stillOld.close()
        }

        clause(
            "CURRENT_READ_ONLY_OPENS_AND_READS",
            DatabasesState.CURRENT,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Opened::class) },
        ) { (databases) ->
            val driver = databases.opened(Current, readOnly = true)
            assertEquals(KEPT, driver.kept())
            driver.close()
        }

        clause(
            "CURRENT_READ_WRITE_REOPENS_WITH_ITS_ROWS",
            DatabasesState.CURRENT,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Opened::class) },
        ) { (databases) ->
            val driver = databases.opened(Current, readOnly = false)
            assertEquals(KEPT, driver.kept(), "a re-open is not a re-create")
            driver.close()
        }

        clause(
            "NEWER_READ_ONLY_FAILS_NEVER_OPENS",
            DatabasesState.NEWER,
            covers = cells {
                on<Databases>().answers(Databases::open).with(DbOpen.Failed::class)
            },
        ) { (databases) ->
            assertIs<DbOpen.Failed>(
                databases.open(NAME, Current, readOnly = true),
                "a reader of a later build's schema cannot know what its rows mean",
            )
        }

        clause(
            "UNOPENABLE_READ_ONLY_FAILS_NEVER_MISSING",
            DatabasesState.UNOPENABLE,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Failed::class) },
        ) { (databases) ->
            assertIs<DbOpen.Failed>(
                databases.open(NAME, Current, readOnly = true),
                "an unreadable database answered as missing reads as 'nothing was ever downloaded'",
            )
        }

        changeClauses()
        contendedClauses()

        clause(
            "UNOPENABLE_READ_WRITE_FAILS",
            DatabasesState.UNOPENABLE,
            covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Failed::class) },
        ) { (databases) ->
            assertIs<DbOpen.Failed>(databases.open(NAME, Current, readOnly = false))
        }
    }
}

/**
 * The cross-process change notice (`Databases`, "What another process committed"): a commit made through another
 * process's open of the same database reaches this process's handlers, naming the database; this process's own
 * commits and a rolled-back transaction never do. The commits are made through a real SQLDelight [TransacterImpl], so
 * what is held is the order production has — the commit, then the notice to the driver's listeners.
 */
private fun ClauseList<DatabasesState, DatabasesSubject>.changeClauses() {
    clause(
        "COMMIT_ELSEWHERE_NOTIFIES_ITS_DATABASE",
        DatabasesState.SHARED,
        covers = cells {
            on<Databases> {
                answers(Databases::inbound).returns()
                answers(Databases::open).with(DbOpen.Opened::class)
                calls(DatabaseHandlers::onChangedElsewhere, String::class)
            }
        },
    ) { subject ->
        val heard = subject.listening()
        val here = subject.databases.opened(DatabasesContract.Current, readOnly = false)
        val other = subject.otherProcess().opened(DatabasesContract.Current, readOnly = false)

        ProbeWriter(other).insert(2)

        awaitWithin { DatabasesContract.NAME in heard.value }
        here.close()
        other.close()
    }

    clause(
        "READ_ONLY_OPEN_HEARS_CHANGES",
        DatabasesState.SHARED,
        covers = cells {
            on<Databases> {
                answers(Databases::inbound).returns()
                answers(Databases::open).with(DbOpen.Opened::class)
                calls(DatabaseHandlers::onChangedElsewhere, String::class)
            }
        },
    ) { subject ->
        // The extension's suppression view opens read-only; it hears the app's commits all the same.
        val heard = subject.listening()
        val here = subject.databases.opened(DatabasesContract.Current, readOnly = true)
        val other = subject.otherProcess().opened(DatabasesContract.Current, readOnly = false)

        ProbeWriter(other).insert(2)

        awaitWithin { DatabasesContract.NAME in heard.value }
        here.close()
        other.close()
    }

    clause(
        "ROLLBACK_ELSEWHERE_IS_SILENT",
        DatabasesState.SHARED,
        covers = cells {
            on<Databases> {
                answers(Databases::inbound).returns()
                answers(Databases::open).with(DbOpen.Opened::class)
            }
        },
    ) { subject ->
        val heard = subject.listening()
        val here = subject.databases.opened(DatabasesContract.Current, readOnly = false)
        val other = subject.otherProcess().opened(DatabasesContract.Current, readOnly = false)

        ProbeWriter(other).insertRolledBack(2)

        transferSettle()
        assertEquals(emptyList(), heard.value, "a transaction that rolled back changed nothing")
        here.close()
        other.close()
    }

    clause(
        "OWN_COMMIT_IS_NOT_ELSEWHERE",
        DatabasesState.CURRENT,
        covers = cells {
            on<Databases> {
                answers(Databases::inbound).returns()
                answers(Databases::open).with(DbOpen.Opened::class)
            }
        },
    ) { subject ->
        val heard = subject.listening()
        val here = subject.databases.opened(DatabasesContract.Current, readOnly = false)

        ProbeWriter(here).insert(2)

        transferSettle()
        assertEquals(emptyList(), heard.value, "this process's own commit is not another process's")
        here.close()
    }
}

/**
 * A statement that fails on a database already open (`Databases`, "A statement can fail"): SQLite answers a write it
 * cannot make — the write lock held elsewhere, the disk full — by failing that statement, and the caller is told by a
 * thrown exception; the database stays open, nothing the failed statement asked for happened, and the next write once
 * the cause is gone goes through. What the mocks' `fail` lever plays rests on this.
 */
private fun ClauseList<DatabasesState, DatabasesSubject>.contendedClauses() {
    clause(
        "A_REFUSED_WRITE_THROWS_AND_CHANGES_NOTHING",
        DatabasesState.CONTENDED,
        covers = cells { on<Databases>().answers(Databases::open).with(DbOpen.Opened::class) },
    ) { subject ->
        val here = subject.databases.opened(DatabasesContract.Current, readOnly = false)
        val release = checkNotNull(subject.contend) { "a CONTENDED binding makes the writes fail" }()
        try {
            assertFails("an insert under a held write lock") {
                here.execute(null, "INSERT INTO probe (id, v) VALUES (2, 'written')", 0)
            }
            assertFails("an update under a held write lock") {
                here.execute(null, "UPDATE probe SET v = 'changed' WHERE id = 1", 0)
            }
            assertFails("a delete under a held write lock") {
                here.execute(null, "DELETE FROM probe WHERE id = 1", 0)
            }
        } finally {
            release()
        }
        assertEquals(DatabasesContract.KEPT, here.keptRow(), "a failed statement changed nothing")
        here.execute(null, "INSERT INTO probe (id, v) VALUES (2, 'written')", 0)
        assertEquals(listOf(1L, 2L), here.ids(), "once the cause is gone the database takes writes again")
        here.close()
    }
}

private fun SqlDriver.keptRow(): String? =
    executeQuery(null, "SELECT v FROM probe WHERE id = 1", { c ->
        QueryResult.Value(if (c.next().value) c.getString(0) else null)
    }, 0).value

private fun SqlDriver.ids(): List<Long> =
    executeQuery(null, "SELECT id FROM probe ORDER BY id", { c ->
        QueryResult.Value(buildList { while (c.next().value) add(c.getLong(0)!!) })
    }, 0).value

/** Every database [DatabasesSubject.databases] heard another process commit to, in order. */
private fun DatabasesSubject.listening(): StateFlow<List<String>> {
    val heard = MutableStateFlow(emptyList<String>())
    databases.inbound(DatabaseHandlers(onChangedElsewhere = inboundOf { name -> heard.update { it + name } }))
    return heard
}

private fun DatabasesSubject.otherProcess(): Databases =
    checkNotNull(elsewhere) { "a SHARED binding stands up the second process" }()

/** [DatabasesContract.NAME] opened at [schema] — a clause that needs it open fails here, naming the answer. */
private fun Databases.opened(schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): SqlDriver {
    val opened = open(DatabasesContract.NAME, schema, readOnly)
    assertIs<DbOpen.Opened>(opened, "expected the database to open (readOnly=$readOnly), got $opened")
    return opened.driver
}

/** Writes the probe table as generated SQLDelight code does: in a transaction, its listeners notified of the table. */
private class ProbeWriter(driver: SqlDriver) : TransacterImpl(driver) {
    fun insert(id: Long) = transaction { write(id) }

    fun insertRolledBack(id: Long) = transaction {
        write(id)
        rollback()
    }

    private fun write(id: Long) {
        driver.execute(null, "INSERT INTO probe (id, v) VALUES ($id, 'written')", 0)
        notifyQueries(PROBE_QUERY) { emit -> emit("probe") }
    }

    private companion object {
        const val PROBE_QUERY = 1
    }
}
