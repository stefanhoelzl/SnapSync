package app.snapsync.mock

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * The Android process the mocks' databases open in. The platform's SQLite is reached through an open helper, and an open
 * helper needs the application's [Context] even for an in-memory database, which no port hands a mock. So this module's
 * manifest registers [MockAndroidContext], which the platform creates at the start of every process that links the
 * mocks — the rig build's app and each device-test APK, and nothing that ships — before any of its code runs.
 */
internal object MockAndroidDatabases {
    @Volatile
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext ?: context
    }

    fun context(): Context =
        checkNotNull(context) { "the mocks' databases were opened before the process created MockAndroidContext" }
}

/** Hands [MockAndroidDatabases] the process's context as the platform starts it; serves nothing. */
class MockAndroidContext : ContentProvider() {
    override fun onCreate(): Boolean {
        MockAndroidDatabases.install(checkNotNull(context) { "a provider is created with its context" })
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? =
        null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
}

// The version is the mock's own business (`PRAGMA user_version`, as on every other target), so the schema the driver's
// open helper is handed creates and migrates nothing.
private object Unversioned : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver) = QueryResult.Value(Unit)

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
        QueryResult.Value(Unit)
}

/**
 * The open helper's own version check, neutralised. The platform's helper stamps ITS version (1) into `user_version` on
 * every open and refuses a file whose `user_version` is higher — which a persisted mock's database always is once the
 * mock has stamped its schema's version — with "Can't downgrade database". So a downgrade is let through, and the
 * version the file held is put back once the helper has finished, leaving `user_version` the mock's alone.
 */
private class KeepsItsVersion : AndroidSqliteDriver.Callback(Unversioned) {
    private var held = 0

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        held = oldVersion
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        if (held > 0) db.version = held
    }
}

private fun driver(name: String?): SqlDriver =
    AndroidSqliteDriver(Unversioned, MockAndroidDatabases.context(), name, callback = KeepsItsVersion())

// A `null` name is the platform's in-memory database: one per helper, living as long as its connection.
internal actual fun newInMemoryDriver(): SqlDriver = driver(null)

// A file under [directory]: the persisted mock's databases (`:test:launch-adapters`). An absolute name is a path.
internal actual fun newFileDriver(directory: String, name: String): SqlDriver {
    File(directory).mkdirs()
    return driver(File(directory, name).path)
}

internal actual fun databaseFileExists(directory: String, name: String): Boolean = File(directory, name).isFile
