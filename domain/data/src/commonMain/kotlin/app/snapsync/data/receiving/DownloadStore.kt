package app.snapsync.data.receiving

import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.read
import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.StagedDownload
import app.snapsync.inbound.AppProcessOnly
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Receiving's record of the others' photos, as the APP holds it: `downloads.db` — its
 * rows keyed by SOURCE identity, stable across syncs and events — the staging area, the transfers under way, and the
 * imports THIS process has claimed. Read and written through the classes over it ([ReceivingPlans], [ImportQueue],
 * [ImportedReleases], [RecognitionRecords], [ReceivingCleanup], [ReceivingCounts]), which share this one store.
 *
 * **One lock covers every decision-write, the import claims and the transfer queue** — the store's own transaction
 * boundary. It covers the decision, never the library call: a library import is synchronous, thread-blocking and
 * unabandonable, and holding a lock across one queues every later read, import, leave and staging behind a stalled
 * library (the SNAPSYNC-6 field hang). The PhotoKit blocks' writes take no lock at all ([ImportMarkerWrites]): their
 * statements carry their own guards.
 */
class DownloadStore @AppProcessOnly constructor(
    internal val file: DownloadDatabaseFile,
    internal val staging: StagingArea,
    internal val transfers: DownloadTransfers,
) {
    private val lock = Mutex()

    /**
     * The imports running in THIS process: claimed under the lock before the library call, released when the library
     * reports. **In memory, and its erasure is load-bearing**: a process that has ended runs none, and a durable claim
     * would outlive the process that owned the transaction and never be released, so that photo would never arrive.
     */
    internal val claims = ImportClaims()

    /** [block], under the store's lock. */
    internal suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    /** [statements] on the database. */
    internal fun <T> read(statements: (DownloadStoreQueries) -> T): DownloadRead<T> = file.execute(statements).read()
}

/** The refs whose import this process claimed. Read and written only under the store's lock. */
internal class ImportClaims {
    private val claimed = mutableSetOf<AssetRef>()

    fun claim(download: StagedDownload) {
        claimed += download.ref
    }

    fun release(ref: AssetRef) {
        claimed -= ref
    }

    operator fun contains(ref: AssetRef): Boolean = ref in claimed
}
