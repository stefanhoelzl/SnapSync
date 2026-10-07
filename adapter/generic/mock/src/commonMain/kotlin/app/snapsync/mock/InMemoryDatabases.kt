package app.snapsync.mock

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen

/**
 * The in-memory [Databases]: a REAL SQLite database per name, held in memory for this instance's lifetime — so the
 * storage services run their own SQL over it rather than being replaced by a double of their own (`docs/testing.md`).
 *
 * It keeps the port's open semantics by `user_version`, as the file-backed adapters do: a read-write open creates a
 * database at the schema's version or migrates an older one; a read-only open never creates or migrates — no
 * database is [DbOpen.Missing], an older one [DbOpen.OldSchema], a newer one [DbOpen.Failed].
 *
 * Durable across a composition's death: an instance holds its databases until it is dropped, and a second
 * composition over the same instance opens what the first wrote — which is how the world expresses a relaunch.
 *
 * With a [directory] each database is a FILE there instead — the launch-time adapters' persisted mock (`:test:launch-adapters`), whose
 * databases outlive the process and are shared, as files are, by every process of the device. Everything else is the
 * same: the same opens, the same versions, the same refusals.
 */
internal class InMemoryDatabases(
    private val refusals: Map<String, DbOpen>,
    private val directory: String? = null,
) : Databases {

    // Both processes open from their own threads, and an inspector reads [opened] from another: the opens are one at a
    // time, as a file's create-or-migrate is.
    private val lock = mockLock()

    private val held = mutableMapOf<String, SqlDriver>()

    private val openLog = mutableListOf<String>()

    /** Every open asked for, by name, in order. */
    val opened: List<String> get() = lock.locked { openLog.toList() }

    override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen = lock.locked {
        openLog += name
        refusals[name] ?: runCatchingCancellable { if (readOnly) openReadOnly(name, schema) else openReadWrite(name, schema) }
            .getOrElse { DbOpen.Failed("${it::class.simpleName}: ${it.message}") }
    }

    private fun openReadOnly(name: String, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        val driver = held[name] ?: onDisk(name) ?: return DbOpen.Missing
        val version = userVersion(driver)
        return when {
            version < schema.version -> DbOpen.OldSchema
            version > schema.version -> DbOpen.Failed(
                "schema version $version is newer than this build's ${schema.version}",
            )
            else -> DbOpen.Opened(driver)
        }
    }

    private fun openReadWrite(name: String, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        val existing = held[name] ?: onDisk(name)
        val driver = existing ?: Kept(newDriver(name)).also { held[name] = it }
        val version = if (existing == null) 0L else userVersion(driver)
        when {
            existing == null -> schema.create(driver)
            version < schema.version -> schema.migrate(driver, version, schema.version)
            version > schema.version ->
                return DbOpen.Failed("schema version $version is newer than this build's ${schema.version}")
        }
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        return DbOpen.Opened(driver)
    }

    /**
     * Delete every database, as deleting the app deletes its container: the next open of any name creates it anew.
     * In-memory databases only — file-backed ones are the launch-time adapters' persisted state, which the app host's
     * own uninstall deletes.
     */
    fun deleteAll() {
        check(
            directory == null,
        ) { "deleting file-backed databases is not modelled; the app host's uninstall deletes them" }
        lock.locked { held.clear() }
    }

    /** A database a file already holds — another process's, or an earlier launch's — opened and kept. */
    private fun onDisk(name: String): SqlDriver? = directory
        ?.takeIf { databaseFileExists(it, name) }
        ?.let { Kept(newFileDriver(it, name)).also { driver -> held[name] = driver } }

    private fun newDriver(name: String): SqlDriver = directory?.let { newFileDriver(it, name) } ?: newInMemoryDriver()

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value
}

/**
 * A held database's handle: [close] keeps it. A caller that closes what it opened — as a file-backed caller may — must
 * not destroy the only copy: an in-memory database lives exactly as long as its connection.
 */
private class Kept(private val driver: SqlDriver) : SqlDriver by driver {
    override fun close() = Unit
}

/** A new, empty in-memory SQLite database — the platform's own SQLite, one connection's worth of memory. */
internal expect fun newInMemoryDriver(): SqlDriver

/** The SQLite database file [name] under [directory], created (with the directory) when absent. */
internal expect fun newFileDriver(directory: String, name: String): SqlDriver

/** Whether [directory] holds the database file [name]. */
internal expect fun databaseFileExists(directory: String, name: String): Boolean
