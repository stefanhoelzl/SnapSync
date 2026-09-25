package app.snapsync.databases

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import java.io.File
import java.util.Properties

/**
 * The JVM [Databases]: SQLite files in [directory], over sqlite-jdbc — the database technology of the JVM
 * binaries (the desktop harnesses and the JVM rig host), and the live host of the `Databases` contract that runs
 * on every `./gradlew build`.
 *
 * A read-only open is a real `SQLITE_OPEN_READONLY` connection: it cannot create, migrate or write.
 */
class JdbcDatabases(private val directory: File) : Databases {

    override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen {
        val file = File(directory, name)
        if (readOnly && !file.exists()) return DbOpen.Missing
        return runCatchingCancellable { if (readOnly) openReadOnly(file, schema) else openReadWrite(file, schema) }
            .getOrElse { DbOpen.Failed("${it::class.simpleName}: ${it.message}") }
    }

    private fun openReadWrite(file: File, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        directory.mkdirs()
        // The driver creates a new file at the schema's version and migrates an older one, both by `user_version`.
        val driver = JdbcSqliteDriver(url(file), Properties(), schema)
        // A file that is not a database opens lazily and fails on first statement; fail the open instead.
        return readable(driver)
    }

    private fun openReadOnly(file: File, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        val driver = JdbcSqliteDriver(url(file), Properties().apply { setProperty(OPEN_MODE, SQLITE_OPEN_READONLY) })
        val version = userVersion(driver)
        return when {
            version < schema.version -> DbOpen.OldSchema.also { driver.close() }
            version > schema.version -> DbOpen.Failed("schema version $version is newer than this build's ${schema.version}")
                .also { driver.close() }
            else -> DbOpen.Opened(driver)
        }
    }

    private fun readable(driver: SqlDriver): DbOpen {
        userVersion(driver)
        return DbOpen.Opened(driver)
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value

    private fun url(file: File) = "jdbc:sqlite:${file.absolutePath}"

    private companion object {
        /** sqlite-jdbc's open-flags property; `1` is `SQLITE_OPEN_READONLY`. */
        const val OPEN_MODE = "open_mode"
        const val SQLITE_OPEN_READONLY = "1"
    }
}
