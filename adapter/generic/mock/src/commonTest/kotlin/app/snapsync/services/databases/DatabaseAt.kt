package app.snapsync.services.databases

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import kotlin.test.assertIs

/**
 * A database [name] at [version] holding exactly what [statements] build, entered THROUGH THE PORT: the first open
 * creates it with them and stamps the version, so a service's own open migrates it from there — the path an
 * upgrading phone takes. Answers the databases and the driver, for a test that reads what no query exposes.
 */
fun databaseAt(name: String, version: Long, vararg statements: String): Pair<Databases, SqlDriver> {
    val databases = inMemoryDatabases()
    val seeded = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = version
        override fun create(driver: SqlDriver) = QueryResult.Value(statements.forEach { driver.execute(null, it, 0) })
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
            QueryResult.Value(Unit)
    }
    val opened = assertIs<DbOpen.Opened>(databases.open(name, seeded, readOnly = false))
    return databases to opened.driver
}

/** The first column of every row [sql] answers, as text. */
fun SqlDriver.strings(sql: String): List<String> =
    executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)!!) })
    }, 0).value
