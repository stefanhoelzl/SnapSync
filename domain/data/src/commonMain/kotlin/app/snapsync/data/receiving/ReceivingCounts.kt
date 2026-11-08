package app.snapsync.data.receiving

import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.held
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.read
import app.snapsync.data.receiving.ImportStatements.importable
import app.snapsync.data.receiving.RowStatements.settledAmong
import app.snapsync.data.receiving.RowStatements.valid
import app.snapsync.data.receiving.RowStatements.waiting
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.wake.StagedImports

/** What the store says of how far this device has received — each read in ONE statement, against one snapshot. */
class ReceivingCounts(private val store: DownloadStore) {

    /**
     * How far this device holds the [foreign] photos a whole read served: how many are settled here, and whether
     * anything still waits to download or import — one view, under the lock.
     */
    suspend fun held(foreign: List<AssetRef>): DownloadRead<HeldFacts> = store.locked {
        store.read { q -> HeldFacts(foreign.size, settledAmong(q, foreign).size, waiting(q)) }
    }

    /** Whether received photos wait staged to be imported — what a wake's re-arm keeps waking for. Unreadable: may. */
    fun stagedImports(): StagedImports = when (val read = store.read { q -> importable(q).size }) {
        is DownloadRead.Ok -> StagedImports.of(read.value)
        is DownloadRead.Unavailable -> StagedImports.REMAIN
    }

    /** The created local id of every imported photo of [event]'s union — the album gather's read (4.7's). */
    fun importedOf(event: EventId): DownloadRead<Set<AssetId>> = store.read { q ->
        q.selectImportedLocalIdsOfEvent(valid(event)).executeAsList().flatMapTo(mutableSetOf()) { it.held() }
    }

    /** The joined screen's counts for [event]. */
    fun counts(event: EventId): DownloadRead<DownloadCounts> = store.read { q ->
        q.projectionCountsForEvent(valid(event)).executeAsOne().let {
            DownloadCounts(
                it.imported.toInt(),
                it.stillArriving.toInt(),
                it.inFlight.toInt(),
                it.known.toInt(),
                it.othersDeclared,
            )
        }
    }

    /** The same counts over every row this device holds — a census of the device, for diagnostics. */
    fun census(): DownloadRead<DownloadCounts> = store.read { q ->
        q.projectionCounts().executeAsOne().let {
            DownloadCounts(
                it.imported.toInt(),
                it.stillArriving.toInt(),
                it.inFlight.toInt(),
                known = 0,
                othersDeclared = null,
            )
        }
    }
}

/** How far this device holds the photos a whole read served: [settled] of [foreign], and whether anything [waiting]. */
class HeldFacts(val foreign: Int, val settled: Int, val waiting: Boolean)

/**
 * The download projection's counts, read together, so a status line can never publish a torn composite. [stillArriving]
 * excludes unimportable rows (design D8): counting work that can never finish pegs the line below completion forever.
 * [known] and [othersDeclared] are an event's only (change `count-declared-photos`).
 */
class DownloadCounts(
    val imported: Int,
    val stillArriving: Int,
    val inFlight: Int,
    val known: Int,
    val othersDeclared: Long?,
)
