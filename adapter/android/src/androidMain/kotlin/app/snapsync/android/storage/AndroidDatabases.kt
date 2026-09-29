package app.snapsync.android.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import java.io.File

/**
 * The Android [Databases]: SQLite files in [directory] (production: the app's database directory), over SQLDelight's
 * Android driver on the platform's own SQLite.
 *
 * ⚠️ **The platform deletes a corrupt database by default, and this adapter forbids it.** An open helper's
 * `onCorruption` is `DefaultDatabaseErrorHandler`'s: it deletes the file and lets the open go on to create an empty one.
 * For the ledger that is a silent reset — every photo the device shared forgotten, with no error anywhere — which is
 * exactly what [DbOpen.Failed] exists to prevent. So the helper's callback refuses instead ([Callback.onCorruption]),
 * the open fails, and the file stays for a later look.
 *
 * **A read-only open never creates, migrates or writes**: it checks the file exists, reads `user_version` over a
 * genuine `OPEN_READONLY` connection, and answers [DbOpen.OldSchema] below the schema's. At the schema's version the
 * driver is then opened over the same file with nothing left for it to create or migrate — read-only is this class's
 * discipline from there, as on iOS, because the driver's open helper takes no read-only mode.
 */
class AndroidDatabases(private val context: Context, private val directory: File) : Databases {

    /** Production: the app's database directory (a secondary constructor, not a default). */
    constructor(context: Context) : this(context, context.getDatabasePath("placeholder").parentFile!!)

    override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen {
        val file = File(directory, name)
        if (readOnly && !file.exists()) return DbOpen.Missing
        return runCatchingCancellable { if (readOnly) openReadOnly(file, schema) else openReadWrite(file, schema) }
            .getOrElse { DbOpen.Failed("${it::class.simpleName}: ${it.message}") }
    }

    private fun openReadWrite(file: File, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        directory.mkdirs()
        val driver = driver(file, schema)
        // The helper opens lazily: until its first statement it has neither opened, created nor migrated the file. One
        // read here makes the open real — a failure throws into `open`'s guard and answers `Failed`.
        userVersion(driver)
        return DbOpen.Opened(driver)
    }

    private fun openReadOnly(file: File, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        val version = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, RefuseCorruption)
            .use { it.version.toLong() }
        return when {
            version < schema.version -> DbOpen.OldSchema
            version > schema.version -> DbOpen.Failed("schema version $version is newer than this build's ${schema.version}")
            else -> DbOpen.Opened(driver(file, schema).also(::userVersion))
        }
    }

    private fun driver(file: File, schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver = AndroidSqliteDriver(
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(file.path).callback(Callback(schema)).build(),
        ),
    )

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value

    /** SQLDelight's own callback — create and migrate by the schema — with the platform's delete-on-corruption refused. */
    private class Callback(schema: SqlSchema<QueryResult.Value<Unit>>) : AndroidSqliteDriver.Callback(schema) {
        override fun onCorruption(db: SupportSQLiteDatabase) {
            throw CorruptDatabase(db.path)
        }
    }

    private object RefuseCorruption : android.database.DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            throw CorruptDatabase(db.path)
        }
    }

    private class CorruptDatabase(path: String?) :
        IllegalStateException("SQLite reports $path corrupt; it is left in place rather than deleted and recreated")
}
