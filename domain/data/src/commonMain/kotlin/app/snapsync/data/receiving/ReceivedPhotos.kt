package app.snapsync.data.receiving

import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.MintsRecognised
import app.snapsync.domain.receiving.Recognised
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.OpenDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What every process reads of the photos this device RECEIVED through SnapSync — so that none of them is shared back
 * as the member's own — a received photo is never shared back: the suppression handles (each
 * received photo's library asset) and whether a membership's received photos have been recognised. A read type only:
 * neither implementation offers a write a sharing reader could reach.
 */
interface ReceivedPhotosRead {
    /**
     * Whether this process can read the store now. The upload cycle asks it once, after its admission, before it
     * touches anything: an older schema pauses the cycle, an unreadable store skips it.
     */
    suspend fun readiness(): ReceivedReadiness

    /** Every received photo's library asset. Never "none" from a store it could not read. */
    suspend fun handles(): HandlesRead

    /** Whether [event]'s received photos have been recognised. Never "yes" from a store it could not read. */
    suspend fun recognition(event: EventId): RecognitionRead
}

/** Whether a process may read the received photos now. */
sealed interface ReceivedReadiness {
    data object Ready : ReceivedReadiness

    /** The store is at an older schema than this build's: the app has not run since the update. */
    data object OldSchema : ReceivedReadiness

    /** The store could not be read. [detail] is diagnostics only. */
    class Unavailable(val detail: String) : ReceivedReadiness
}

/** The received photos' library assets, or that the store could not say. */
sealed interface HandlesRead {
    class Ok(val ids: Set<AssetId>) : HandlesRead

    class Unavailable(val detail: String) : HandlesRead
}

/** Whether a membership's received photos have been recognised, as the store answered. */
sealed interface RecognitionRead {
    class Recognised(val evidence: app.snapsync.domain.receiving.Recognised) : RecognitionRead

    data object NotRecognised : RecognitionRead

    class Unavailable(val detail: String) : RecognitionRead
}

/**
 * The APP's received photos, over its read-write store: the reads, and the recognition record a recognition pass
 * writes (`changes/archive/2026-10-11-close-share-back-gaps`).
 */
class ReceivedPhotos(private val file: DownloadDatabaseFile) : ReceivedPhotosRead {

    /** Always ready: a read-write open creates and migrates, so this process never finds the store at an old schema. */
    override suspend fun readiness(): ReceivedReadiness = ReceivedReadiness.Ready

    override suspend fun handles(): HandlesRead = handlesOf(file.execute(::suppressed))

    override suspend fun recognition(event: EventId): RecognitionRead =
        recognitionOf(file.execute { q -> recognised(q, event) }, event)

    /**
     * Record that [event]'s received photos have been recognised: what a pass writes once it has READ the event's union
     * — never after a read that failed. The upload gate of both processes shares nothing before it.
     */
    suspend fun markRecognised(event: EventId): StoreWrite =
        written(file.execute { q -> q.markRecognised(Stored.Valid(event)).let { } })

    /**
     * Withdraw [event]'s recognition: the photos the app can see now include marked ones no pass has recognised, so
     * nothing of the membership may be shared until the next pass has.
     */
    suspend fun withdrawRecognition(event: EventId): StoreWrite =
        written(file.execute { q -> q.unmarkRecognised(Stored.Valid(event)).let { } })
}

/**
 * The upload EXTENSION's received photos: the store opened **read-only** — it never creates, migrates or writes it,
 * because it may run before an updated app has. What it finds decides the cycle: no store is nothing received (the
 * cycle runs); an older schema pauses it — running without suppression would upload received photos back; an
 * unopenable one is "I could not look". Opened on first use and kept only once open; every other answer is asked again
 * next time, so a store the app creates or migrates meanwhile is seen at once.
 */
class ReceivedPhotosReader(private val databases: Databases) : ReceivedPhotosRead {
    private val lock = Mutex()
    private var opened: OpenDatabase? = null

    override suspend fun readiness(): ReceivedReadiness = when (val found = found()) {
        is Found.Open, Found.Missing -> ReceivedReadiness.Ready
        Found.OldSchema -> ReceivedReadiness.OldSchema
        is Found.Failed -> ReceivedReadiness.Unavailable(found.detail)
    }

    override suspend fun handles(): HandlesRead = when (val found = found()) {
        is Found.Open -> handlesOf(found.read(::suppressed))
        Found.Missing -> HandlesRead.Ok(emptySet())
        Found.OldSchema -> HandlesRead.Unavailable(OLDER_SCHEMA)
        is Found.Failed -> HandlesRead.Unavailable(found.detail)
    }

    override suspend fun recognition(event: EventId): RecognitionRead = when (val found = found()) {
        is Found.Open -> recognitionOf(found.read { q -> recognised(q, event) }, event)
        // No store: the app has run no recognition on this device.
        Found.Missing -> RecognitionRead.NotRecognised
        Found.OldSchema -> RecognitionRead.Unavailable(OLDER_SCHEMA)
        is Found.Failed -> RecognitionRead.Unavailable(found.detail)
    }

    private suspend fun found(): Found = lock.withLock { opened?.let(Found::Open) ?: opening() }

    private fun opening(): Found = when (
        val open = databases.open(
            DOWNLOADS_DB_NAME,
            DownloadDatabase.Schema,
            readOnly = true,
        )
    ) {
        is DbOpen.Opened -> Found.Open(open.database.also { opened = it })
        DbOpen.Missing -> Found.Missing
        DbOpen.OldSchema -> Found.OldSchema
        is DbOpen.Failed -> Found.Failed(open.detail)
    }

    private sealed interface Found {
        class Open(private val database: OpenDatabase) : Found {
            fun <T> read(statements: (DownloadStoreQueries) -> T): DbResult<T> =
                database.execute { statements(downloadDatabase(it).downloadStoreQueries) }
        }

        data object Missing : Found

        data object OldSchema : Found

        class Failed(val detail: String) : Found
    }

    private companion object {
        const val OLDER_SCHEMA = "older schema than this build's"
    }
}

private fun suppressed(q: DownloadStoreQueries): Set<AssetId> =
    q.suppressedLocalIds().executeAsList().flatMapTo(mutableSetOf()) { it.held() }

private fun recognised(q: DownloadStoreQueries, event: EventId): Boolean = q.isRecognised(
    Stored.Valid(event),
).executeAsOne()

private fun handlesOf(read: DbResult<Set<AssetId>>): HandlesRead = when (read) {
    is DbResult.Ok -> HandlesRead.Ok(read.value)
    is DbResult.Failed -> HandlesRead.Unavailable(read.detail)
}

@OptIn(MintsRecognised::class)
private fun recognitionOf(read: DbResult<Boolean>, event: EventId): RecognitionRead = when (read) {
    is DbResult.Ok -> when (read.value) {
        true -> RecognitionRead.Recognised(Recognised(event))
        false -> RecognitionRead.NotRecognised
    }
    is DbResult.Failed -> RecognitionRead.Unavailable(read.detail)
}

private fun written(result: DbResult<Unit>): StoreWrite = when (result) {
    is DbResult.Ok -> StoreWrite.Written
    is DbResult.Failed -> StoreWrite.Failed(result.detail)
}
