package app.snapsync.services.databases

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import kotlin.test.assertIs

/**
 * A database [name] as [schema] builds it — an older version, from `SqlSchema.at` — entered THROUGH THE PORT: the first
 * open creates it and stamps that version, so a service's own open migrates it from there — the path an upgrading phone
 * takes. Answers the databases and the driver, for a test that reads what no query exposes.
 */
fun databaseAt(name: String, schema: SqlSchema<QueryResult.Value<Unit>>): Pair<Databases, SqlDriver> {
    val databases = inMemoryDatabases()
    val opened = assertIs<DbOpen.Opened>(databases.open(name, schema, readOnly = false))
    return databases to opened.driver
}

/** The first column of every row [sql] answers, as text. */
fun SqlDriver.strings(sql: String): List<String> =
    executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)!!) })
    }, 0).value
