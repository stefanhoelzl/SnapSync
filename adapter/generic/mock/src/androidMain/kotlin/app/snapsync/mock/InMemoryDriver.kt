package app.snapsync.mock

import android.content.Context
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * The Android process the mocks' databases open in. The platform's SQLite is reached through an open helper, and an
 * open helper needs the application's [Context] even for an in-memory database — so the one root that links the mocks
 * on Android (the rig build's) hands it over before it composes anything. A database opened before that fails naming
 * this, rather than guessing a context.
 */
object MockAndroidDatabases {
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    internal fun context(): Context =
        checkNotNull(context) { "the mocks' databases were opened before MockAndroidDatabases.install(context)" }
}

// The version is the mock's own business (`PRAGMA user_version`, as on every other target), so the schema the driver's
// open helper is handed creates and migrates nothing.
private object Unversioned : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver) = QueryResult.Value(Unit)

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
        QueryResult.Value(Unit)
}

private fun driver(name: String?): SqlDriver = AndroidSqliteDriver(Unversioned, MockAndroidDatabases.context(), name)

// A `null` name is the platform's in-memory database: one per helper, living as long as its connection.
internal actual fun newInMemoryDriver(): SqlDriver = driver(null)

// A file under [directory]: the persisted mock's databases (`:test:launch-adapters`). An absolute name is a path.
internal actual fun newFileDriver(directory: String, name: String): SqlDriver {
    File(directory).mkdirs()
    return driver(File(directory, name).path)
}

internal actual fun databaseFileExists(directory: String, name: String): Boolean = File(directory, name).isFile
