package app.snapsync.databases

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import java.io.File
import java.util.Collections
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The JVM [Databases]: SQLite files in [directory], over sqlite-jdbc — the database technology of the JVM
 * binaries (the desktop harnesses and the JVM rig host), and the live host of the `Databases` contract that runs
 * on every `./gradlew build`.
 *
 * A read-only open is a real `SQLITE_OPEN_READONLY` connection: it cannot create, migrate or write.
 *
 * **Another process is another instance.** The JVM runs the app and the extension as compositions of one process, so
 * each instance stands for one of them: a commit through one instance's driver is delivered to every other instance
 * that has the same file open ([DatabaseHandlers.onChangedElsewhere]), on a thread of its own, never back to the
 * committer.
 */
class JdbcDatabases(private val directory: File) : Databases {

    /** Who is told of another process's commits; until someone listens, nobody is. */
    @Volatile
    private var handlers: DatabaseHandlers? = null

    /** The databases this instance has opened, by file path — the name each was opened by. */
    private val names = ConcurrentHashMap<String, String>()

    @CompositionOnly
    override fun inbound(handlers: DatabaseHandlers) {
        this.handlers = handlers
    }

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
        return readable(signalling(driver, file, name = file.name))
    }

    private fun openReadOnly(file: File, schema: SqlSchema<QueryResult.Value<Unit>>): DbOpen {
        val driver = JdbcSqliteDriver(url(file), Properties().apply { setProperty(OPEN_MODE, SQLITE_OPEN_READONLY) })
        val version = userVersion(driver)
        return when {
            version < schema.version -> DbOpen.OldSchema.also { driver.close() }
            version > schema.version -> DbOpen.Failed(
                "schema version $version is newer than this build's ${schema.version}",
            )
                .also { driver.close() }
            else -> DbOpen.Opened(JdbcOpenDatabase(signalling(driver, file, name = file.name)))
        }
    }

    /** [driver], announcing each commit to the other instances that have [file] open. */
    private fun signalling(driver: SqlDriver, file: File, name: String): SqlDriver {
        val path = file.canonicalPath
        names[path] = name
        Commits.opened(path, this)
        return CommitSignalling(driver) { Commits.committed(path, from = this) }
    }

    private fun changedElsewhere(path: String) {
        handlers?.let { it.onChangedElsewhere.deliver(names.getValue(path)) }
    }

    /**
     * The JVM's stand-in for the operating system's cross-process notification: which instances have each file open,
     * held weakly, so an instance a caller dropped is forgotten. Deliveries run in order on one thread of their own.
     */
    private object Commits {
        private val open = mutableMapOf<String, MutableSet<JdbcDatabases>>()
        private val delivery = Executors.newSingleThreadExecutor { task ->
            Thread(task, "databases-commits").apply { isDaemon = true }
        }

        fun opened(path: String, by: JdbcDatabases) = synchronized(open) {
            open.getOrPut(path) { Collections.newSetFromMap(WeakHashMap()) }.add(by)
        }

        fun committed(path: String, from: JdbcDatabases) {
            val others = synchronized(open) { open.getValue(path).filter { it !== from } }
            others.forEach { other -> delivery.execute { other.changedElsewhere(path) } }
        }
    }

    private fun readable(driver: SqlDriver): DbOpen {
        userVersion(driver)
        return DbOpen.Opened(JdbcOpenDatabase(driver))
    }

    // `PRAGMA user_version` answers exactly one row holding an integer on every SQLite database, a new one's being 0.
    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        }, 0).value

    private fun url(file: File) = "jdbc:sqlite:${file.absolutePath}"

    private companion object {
        /** sqlite-jdbc's open-flags property; `1` is `SQLITE_OPEN_READONLY`. */
        const val OPEN_MODE = "open_mode"
        const val SQLITE_OPEN_READONLY = "1"
    }
}
