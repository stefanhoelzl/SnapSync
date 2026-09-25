package app.snapsync.databases

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.model.runCatchingCancellable
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.isNoSuchFile
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.getVersion
import co.touchlab.sqliter.withConnection
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey

/**
 * The iOS [Databases]: SQLite files in the **App-Group container**, over SQLDelight's native driver — so the app
 * and the upload extension open the same file (WAL: one writer per kind of write, concurrent readers).
 *
 * [basePath] is the container, resolved once: the production constructor looks it up when the adapter is
 * built — a path resolve, not I/O, so building the composition still opens nothing — and a test passes a
 * directory it owns, because a bundle-less test binary has no App-Group entitlement. `null` is a build without
 * that entitlement: every open then answers [DbOpen.Failed] naming it. Before this port the store factories
 * crashed the process there instead; a store that cannot be reached now fails where it is used, which every
 * caller of a store already handles.
 *
 * **Placement is the failure to guard.** The base path travels through the driver's `onConfiguration` into
 * `extendedConfig.basePath`, a nested copy whose failure mode is not an error but a database opened *somewhere
 * else* — the process's private sandbox — where every read and write succeeds and the two processes silently
 * stop sharing it. `IosDatabasesTest` pins the file's location.
 *
 * **File protection** is set explicitly to `CompleteUntilFirstUserAuthentication` on every open, so the
 * extension can open a database on a **locked** device (readable after the first unlock since boot) without
 * that depending on the absence of a `default-data-protection = NSFileProtectionComplete` entitlement. WAL means
 * three files; a missing `-wal`/`-shm` is a harmless no-op.
 *
 * **A read-only open never creates, migrates or writes**: it checks the file exists, then opens with SQLiter's
 * version check disabled and reads `user_version` itself, answering [DbOpen.OldSchema] below the schema's.
 * (SQLiter keeps its `SQLITE_OPEN_READONLY` flag internal, so "read-only" is this class's discipline — no DDL, no
 * migration, and a caller that reads only — not the connection's mode.)
 */
@OptIn(ExperimentalForeignApi::class)
class IosDatabases(private val basePath: String?) : Databases {

    /** Production: the shared App-Group container (a secondary constructor, not a default — `docs/architecture.md`). */
    constructor() : this(
        NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path,
    )

    private val log = Logger.withTag("databases")

    override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen {
        val base = basePath
            ?: return DbOpen.Failed(
                "App Group container '$LEDGER_APP_GROUP' unavailable — the application-groups entitlement is " +
                    "missing or unprovisioned",
            )
        if (readOnly && !NSFileManager.defaultManager.fileExistsAtPath("$base/$name")) return DbOpen.Missing
        return runCatchingCancellable { if (readOnly) openReadOnly(base, name, schema) else openReadWrite(base, name, schema) }
            .getOrElse { DbOpen.Failed("${it::class.simpleName}: ${it.message}") }
    }

    private fun openReadWrite(base: String, name: String, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        // The driver creates a new file at the schema's version and migrates an older one, inside its own open.
        val driver = NativeSqliteDriver(
            schema = schema,
            name = name,
            onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = base)) },
        )
        // The driver connects lazily: until its first statement it has neither opened, created nor migrated the file,
        // so a database that cannot be opened would answer `Opened` and fail only at first use. One read here makes
        // the open real — a failure throws into `open`'s guard and answers `Failed` — and creates the file before
        // anyone looks for it.
        userVersion(driver)
        protect(base, name)
        return DbOpen.Opened(driver)
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value

    private fun openReadOnly(base: String, name: String, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        val manager = createDatabaseManager(
            DatabaseConfiguration(
                name = name,
                version = NO_VERSION_CHECK,
                create = {},
                extendedConfig = DatabaseConfiguration.Extended(basePath = base),
            ),
        )
        val version = manager.withConnection { it.getVersion() }.toLong()
        return when {
            version < schema.version -> DbOpen.OldSchema
            version > schema.version -> DbOpen.Failed("schema version $version is newer than this build's ${schema.version}")
            else -> DbOpen.Opened(NativeSqliteDriver(manager))
        }
    }

    private fun protect(base: String, name: String) {
        val attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
        for (suffix in listOf("", "-wal", "-shm")) {
            val path = "$base/$name$suffix"
            checkedObjC("setAttributes") { NSFileManager.defaultManager.setAttributes(attributes, path, error = it) }
                .onFailure { if (!it.isNoSuchFile) log.w(it) { "file protection not set on $path" } }
        }
    }
}
