package app.snapsync.feature.support

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.fake.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.AssetPresence
import app.snapsync.model.AssetRef
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.StartResult
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.Files
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.gallery.ImportedAssetPresence
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The download feature's test support: the REAL download pieces (`DownloadService`, `DownloadJobs`, `StagingService`,
// `GalleryImporter`) run over the ports' in-memory mocks, and what a test observes or forces is at the PORT — the
// transfers the `Download` port was asked to start, the SQL the `Databases` port's driver ran, the files in the
// `Files` cells (`docs/testing.md`, "Feature tests compose real services over port mocks").

/**
 * A presence answer for the download feature's tests: a library holding [present] identifiers, and a [readable] cell
 * saying whether it can be seen at all. Not an adapter — presence is a service over the gallery
 * (`GalleryAssetPresence`), held to its own tests — but the two questions its verdicts collapse to, as cells a test
 * can move under a running subject. With [readable] false every answer is `UNKNOWN`, the state that must never be
 * mistaken for `ABSENT`.
 */
class InMemoryAssetPresence(
    private val present: MutableStateFlow<Set<AssetId>> = MutableStateFlow(emptySet()),
    private val readable: StateFlow<Boolean> = MutableStateFlow(true),
) : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<AssetId>): Map<AssetId, AssetPresence> =
        if (!readable.value) {
            localIds.associateWith { AssetPresence.UNKNOWN }
        } else {
            localIds.associateWith { if (it in present.value) AssetPresence.PRESENT else AssetPresence.ABSENT }
        }
}

/**
 * The platform's downloads, recorded: every transfer the jobs asked the port to start, and whether it was asked to
 * cancel them. [accept] is what the port answers a start: `true` (the default) runs the transfer, so the jobs' bounded
 * window fills; `false` leaves every one unstarted, so each resource the jobs are handed reaches the port however many
 * there are — how a test counts what was handed over past the window.
 */
class RecordingDownload(var accept: Boolean = true) : Download {

    /** One start request: the url and the transfer's opaque tag. */
    data class Start(val url: String, val tag: String) {
        /** The asset the tag names — the tag's first two fields (device id, asset id). */
        val ref: AssetRef get() = tag.split(TAG_SEPARATOR).let { AssetRef(it[0], AssetId(it[1])) }

        /** The resource key the tag names — its third field. */
        val resourceKey: String get() = tag.split(TAG_SEPARATOR)[2]
    }

    val started: MutableList<Start> = mutableListOf()

    /** Whether the jobs asked the port to cancel every transfer it holds (a leave or a switch). */
    var cancelled: Boolean = false
        private set

    override fun listen(handlers: DownloadHandlers) = Unit

    override fun start(url: String, tag: String): StartResult {
        started += Start(url, tag)
        return if (accept) StartResult.Started else StartResult.NotStarted
    }

    override suspend fun cancelAll() {
        cancelled = true
    }

    private companion object {
        /** The jobs' tag separator: a newline cannot occur in a device id, a sanitized key or a filename. */
        const val TAG_SEPARATOR = "\n"
    }
}

/** The real download jobs over [download] and [staging]; nothing is staged through them unless a test finishes one. */
fun downloadJobs(
    scope: CoroutineScope,
    download: Download = RecordingDownload(),
    staging: StagingService = StagingService(RecordingFiles()),
): DownloadJobs = DownloadJobs(scope, staging, download, onStaged = { _, _, _ -> })

/**
 * [Databases] over real in-memory SQLite that records every statement a service's driver ran, and the transaction it
 * ran in — how a test counts store round-trips at the port rather than through a double of the service. The schema's
 * own create and version stamping are not recorded: they run before the driver is handed over.
 */
class RecordingDatabases(private val inner: Databases = inMemoryDatabases()) : Databases {

    /**
     * One statement: its SQL, whitespace-normalised, the values bound to it in parameter order, and the transaction it
     * ran in (`null` for autocommit).
     */
    class Statement(val sql: String, val args: List<Any?>, val transaction: Any?)

    val statements: MutableList<Statement> = mutableListOf()

    /** How many transactions were opened — an empty one runs no statement, so it is counted here. */
    var transactions: Int = 0
        private set

    /** Forget what was recorded so far — a test's setup is not what it counts. */
    fun reset() {
        statements.clear()
        transactions = 0
    }

    /** The statements whose SQL starts with [prefix] (whitespace-normalised). */
    fun matching(prefix: String): List<Statement> = statements.filter { it.sql.startsWith(normalise(prefix)) }

    override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen =
        when (val opened = inner.open(name, schema, readOnly)) {
            is DbOpen.Opened -> DbOpen.Opened(Recording(opened.driver))
            else -> opened
        }

    private inner class Recording(private val driver: SqlDriver) : SqlDriver by driver {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            record(sql, binders)
            return driver.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            record(sql, binders)
            return driver.execute(identifier, sql, parameters, binders)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            transactions++
            return driver.newTransaction()
        }

        private fun record(sql: String, binders: (SqlPreparedStatement.() -> Unit)?) {
            val bound = Bound().also { binders?.invoke(it) }
            statements += Statement(normalise(sql), bound.values(), driver.currentTransaction())
        }
    }

    /** Captures what a statement's binders bind, by parameter index. Binding is pure, so running it twice is safe. */
    private class Bound : SqlPreparedStatement {
        private val byIndex = mutableMapOf<Int, Any?>()
        fun values(): List<Any?> = byIndex.entries.sortedBy { it.key }.map { it.value }
        override fun bindBytes(index: Int, bytes: ByteArray?) { byIndex[index] = bytes }
        override fun bindLong(index: Int, long: Long?) { byIndex[index] = long }
        override fun bindDouble(index: Int, double: Double?) { byIndex[index] = double }
        override fun bindString(index: Int, string: String?) { byIndex[index] = string }
        override fun bindBoolean(index: Int, boolean: Boolean?) { byIndex[index] = boolean }
    }

    private companion object {
        fun normalise(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")
    }
}

/** A [Files] whose deletes THROW — a platform call that raised rather than answering, as a busy disk's can. */
class ThrowingDeletes(private val inner: Files) : Files by inner {
    override fun delete(area: FileArea, path: String): FileResult<Unit> = error("disk is busy")
}
