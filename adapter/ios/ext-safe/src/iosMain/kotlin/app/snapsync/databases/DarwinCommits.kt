package app.snapsync.databases

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.darwin.NOTIFY_STATUS_OK
import platform.darwin.dispatch_queue_create
import platform.darwin.notify_cancel
import platform.darwin.notify_post
import platform.darwin.notify_register_dispatch

/**
 * One opened database's half of the cross-process change notice (`Databases`, "What another process committed"): a
 * Darwin notification named for the database, posted after each commit through this driver and observed for the other
 * process's. The two processes share nothing else, so the notice carries nothing — not the tables, not the sender —
 * and a delivery is told apart by the database itself: SQLite's `data_version` changes, on one connection, only when
 * ANOTHER connection committed. It is read on the driver's writer (inside a transaction, which is where the native
 * driver runs a statement on its writer) — this process's own commits are made on that same connection, and its readers
 * never commit — so this process's own notices, which Darwin delivers to the poster too, and spurious ones fall away.
 *
 * Delivered on a serial queue of its own, never the main thread; a notice that arrives while this process is
 * suspended is not queued for it (Apple: a suspended app is not resumed by one), which costs freshness only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class DarwinCommits(
    name: String,
    driver: SqlDriver,
    private val log: Logger,
    private val elsewhere: () -> Unit,
) {
    private val notification = "app.snapsync.db.$name"
    private val writer = Writer(driver)

    /** The last `data_version` the writer saw; touched only on [queue] once registered. */
    private var seen: Long = writer.dataVersion()

    private val queue = dispatch_queue_create("app.snapsync.databases.$name", null)

    private val token: Int = memScoped {
        val out = alloc<IntVar>()
        val status = notify_register_dispatch(notification, out.ptr, queue) { _ ->
            objcBoundary(log, "commit notice $notification") { received() }
        }
        if (status != NOTIFY_STATUS_OK.toUInt()) log.w { "$notification not observed (status $status)" }
        out.value
    }

    /** Announce a commit through this driver. */
    fun post() {
        val status = notify_post(notification)
        if (status != NOTIFY_STATUS_OK.toUInt()) log.w { "$notification not posted (status $status)" }
    }

    /** Stop observing: the driver is closed. */
    fun close() {
        notify_cancel(token)
    }

    private fun received() {
        val now = writer.dataVersion()
        if (now == seen) return
        seen = now
        elsewhere()
    }

    /** `PRAGMA data_version`, read in a transaction so the native driver runs it on its writer. */
    private class Writer(driver: SqlDriver) : TransacterImpl(driver) {
        fun dataVersion(): Long = transactionWithResult {
            driver.executeQuery(null, "PRAGMA data_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            }, 0).value
        }
    }
}

/**
 * [inner], announcing each finished commit — SQLDelight's notice to its listeners, which comes after the commit and
 * never for a rollback — to [committed], and its close to [closed].
 */
internal class CommitSignalling(
    private val inner: SqlDriver,
    private val committed: () -> Unit,
    private val closed: () -> Unit,
) : SqlDriver by inner {
    override fun notifyListeners(vararg queryKeys: String) {
        inner.notifyListeners(*queryKeys)
        committed()
    }

    override fun close() {
        closed()
        inner.close()
    }
}
