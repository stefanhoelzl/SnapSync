package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.receiving.Adjudicated
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ImportQueue
import app.snapsync.data.receiving.ImportedReleases
import app.snapsync.data.receiving.LibraryImport
import app.snapsync.data.receiving.LibraryPresence
import app.snapsync.data.receiving.ReceivingCleanup
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.album.ImportAlbumVerdict
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetPresence
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.ImportVerdict
import app.snapsync.domain.receiving.ImportedAsset
import app.snapsync.domain.receiving.Recognised
import app.snapsync.domain.receiving.StagedDownload
import app.snapsync.domain.receiving.UnconfirmedImport
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

/**
 * One pass of the import drain: the event album its imports file into ([album] — read once, before the pass, so the
 * platform's change block does no lookup of its own), the photos it has attempted, and whether one of them landed in
 * the album. Attempted-in-this-pass is what keeps a failed import that leaves its row importable from being offered
 * again in the same pass, forever.
 */
class ImportPass(val album: ImportAlbumVerdict) {
    internal val attempted = mutableSetOf<AssetRef>()
    private var intoAlbum = false

    /** The album a photo of this pass was imported into, if one was — how a folder album learns it has held a photo. */
    val landedIn: EventAlbumId?
        get() = when (album) {
            is ImportAlbumVerdict.Into -> album.album.takeIf { intoAlbum }
            ImportAlbumVerdict.CameraFolder -> null
        }

    internal fun imported() {
        intoAlbum = true
    }
}

/**
 * Import the next received photo whose every resource is staged, and free its staged bytes — ONE at a time,
 * deliberately: a batch claimed up front strands every other photo of it behind a single import that never reports,
 * moving recovery from "the next wake" to "the next launch". Answers whether there may be more.
 *
 * It requires [Recognised]: an import that overtakes the recognition of an earlier install's received photos lands a
 * second copy of a photo the library holds (measured on the SE2: 2026-09-30, 2026-10-01).
 *
 * The library call is the only thing outside the store's lock. A throw or a cancellation leaves the photo CLAIMED and
 * propagates: nothing says whether a transaction was submitted first, and a claim released on "this coroutine is gone"
 * is the inference that clears a live marker (Bugsink SNAPSYNC-9). So nothing here catches.
 */
class ImportNextStaged(
    private val queue: ImportQueue,
    private val releases: ImportedReleases,
    private val library: LibraryImport,
) {
    private val log = Log("ImportNextStaged")

    @EntryPointCall
    suspend operator fun invoke(@Suppress("UNUSED_PARAMETER") recognised: Recognised, pass: ImportPass): Boolean =
        logged(stepOf(queue.claimNext(pass.attempted), pass)).more

    private suspend fun stepOf(claim: DownloadRead<StagedDownload?>, pass: ImportPass): ImportStep = when (claim) {
        is DownloadRead.Ok -> next(claim.value, pass)
        is DownloadRead.Unavailable -> ImportStep.Unavailable(claim.detail)
    }

    private suspend fun next(claim: StagedDownload?, pass: ImportPass): ImportStep = when (claim) {
        null -> ImportStep.NothingLeft
        else -> imported(claim, pass)
    }

    private suspend fun imported(claim: StagedDownload, pass: ImportPass): ImportStep {
        pass.attempted += claim.ref
        log.i("importing", "asset" of claim.ref.sourceAssetId)
        return settled(claim, ImportVerdict.of(library.import(claim, pass.album)), pass)
    }

    private suspend fun settled(claim: StagedDownload, verdict: ImportVerdict, pass: ImportPass): ImportStep =
        when (verdict) {
            is ImportVerdict.Imported -> released(queue.imported(claim, verdict.created), pass)
            is ImportVerdict.Unimportable -> ImportStep.Unimportable(claim, queue.unimportable(claim), verdict.reason)
            is ImportVerdict.Deferred -> deferred(claim, verdict.reason)
        }

    private suspend fun deferred(claim: StagedDownload, reason: String): ImportStep {
        queue.deferred(claim)
        return ImportStep.Deferred(claim, reason)
    }

    /** AFTER the confirming write, never before: a crash between them leaves extra bytes, never a row naming none. */
    private suspend fun released(
        imported: DownloadRead<ImportedAsset>,
        pass: ImportPass,
    ): ImportStep = when (imported) {
        is DownloadRead.Ok -> freed(imported.value, pass)
        is DownloadRead.Unavailable -> ImportStep.Unrecorded(imported.detail)
    }

    private suspend fun freed(imported: ImportedAsset, pass: ImportPass): ImportStep {
        pass.imported()
        releases.release(imported)
        return ImportStep.Imported(imported)
    }

    private suspend fun logged(step: ImportStep): ImportStep = step.also { it.reported(log) }

    /** What one step of the drain came to — and whether the drain goes on ([more]). */
    private sealed class ImportStep(val more: Boolean = true) {
        abstract suspend fun reported(log: Log)

        class Imported(val asset: ImportedAsset) : ImportStep() {
            override suspend fun reported(log: Log) = log.i(
                "imported",
                "asset" of asset.ref.sourceAssetId,
                "as" of asset.created,
            )
        }

        class Unimportable(
            val claim: StagedDownload,
            val applied: DownloadRead<Boolean>,
            val reason: String,
        ) : ImportStep() {
            /**
             * ERROR, not WARN, and that severity is the decision: this photo will never arrive, and it is otherwise
             * absent from the member's library with no error surface. A guarded write that matched nothing is a row
             * that moved on while the import ran: settling it would overwrite whatever it moved on to.
             */
            override suspend fun reported(log: Log) = when (applied) {
                is DownloadRead.Ok -> settled(log, applied.value)
                is DownloadRead.Unavailable -> log.w(
                    "an unimportable photo could not be recorded",
                    "why" of applied.detail,
                )
            }

            private suspend fun settled(log: Log, applied: Boolean) = when (applied) {
                true -> log.e(
                    "a received photo is unimportable and will not arrive",
                    "asset" of claim.ref.sourceAssetId,
                    "why" of reason,
                )
                false -> log.i("an import failed, but its row already settled", "asset" of claim.ref.sourceAssetId)
            }
        }

        class Deferred(val claim: StagedDownload, val reason: String) : ImportStep() {
            override suspend fun reported(log: Log) =
                log.w("an import is deferred; it is retried", "asset" of claim.ref.sourceAssetId, "why" of reason)
        }

        class Unrecorded(val detail: String) : ImportStep() {
            override suspend fun reported(log: Log) = log.w("an import could not be recorded", "why" of detail)
        }

        class Unavailable(val detail: String) : ImportStep(more = false) {
            override suspend fun reported(log: Log) = log.w("nothing can be imported now", "why" of detail)
        }

        data object NothingLeft : ImportStep(more = false) {
            override suspend fun reported(log: Log) = Unit
        }
    }
}

/**
 * Free the staged bytes of every imported photo whose bytes are still on disk — imports confirmed before bytes were
 * ever released, or released by a pass a crash cut short. Self-extinguishing: releasing drops the rows that made the
 * work findable. Driven by the foreground, unconditionally: the backlog is the device's, not a membership's.
 */
class ReleaseImported(private val releases: ImportedReleases) {
    private val log = Log("ReleaseImported")

    @EntryPointCall
    suspend operator fun invoke() = when (val imported = releases.holdingBytes()) {
        is DownloadRead.Ok -> released(imported.value)
        is DownloadRead.Unavailable -> log.w("the imported photos' bytes could not be read", "why" of imported.detail)
    }

    private suspend fun released(imported: List<ImportedAsset>) {
        imported.forEach { releases.release(it) }
        logged(imported.size)
    }

    private suspend fun logged(released: Int) = when (released) {
        0 -> Unit
        else -> log.i("released the staged bytes of imported photos", "photos" of released)
    }
}

/**
 * Delete every staged file no download claims — bytes nothing will ever import or release. Retried at the next
 * foreground when the staging directory cannot be read.
 */
class SweepUnclaimedStaging(private val cleanup: ReceivingCleanup) {
    private val log = Log("SweepUnclaimedStaging")

    @EntryPointCall
    suspend operator fun invoke() = when (val swept = cleanup.sweepUnclaimed()) {
        is DownloadRead.Ok -> log.i("swept unclaimed staged files", "files" of swept.value)
        is DownloadRead.Unavailable -> log.w("the unclaimed staged files could not be swept", "why" of swept.detail)
    }
}

/**
 * The process's recovery: settle the rows a dead process left behind — an asset was created for them and the
 * confirmation never arrived. Run once per process, before the first drain, and from nowhere else: during a burst the
 * only unconfirmed row is the import in flight, whose transaction is open, so the library can only answer "absent" —
 * and that answer the verdict must discard (measured, iPhone XS: 1,149 of 1,164 verdicts thrown away).
 *
 * Its dominant outcome is "present": a library commit survives the death of the process that opened it (SE2, iOS
 * 26.5.2, 2026-08-09). The library is asked outside the store's lock — a synchronous call no timeout can abandon — and
 * each verdict applied under it, through a write guarded on the marker it was computed for. A process that inherited
 * nothing asks the library nothing.
 */
class SettleInterruptedImports(private val releases: ImportedReleases, private val presence: LibraryPresence) {
    private val log = Log("SettleInterruptedImports")

    @EntryPointCall
    suspend operator fun invoke() = when (val rows = releases.unconfirmed()) {
        is DownloadRead.Ok -> inherited(rows.value)
        is DownloadRead.Unavailable -> log.w("the unconfirmed imports could not be read", "why" of rows.detail)
    }

    private suspend fun inherited(rows: List<UnconfirmedImport>) = when (rows.isEmpty()) {
        true -> Unit
        false -> adjudicated(rows, presence.of(rows.mapTo(mutableSetOf()) { it.createdLocalId }))
    }

    /** The library answers every id it is asked about ([LibraryPresence.of]): each row meets its own verdict. */
    private suspend fun adjudicated(rows: List<UnconfirmedImport>, found: Map<AssetId, AssetPresence>) {
        rows.forEach { row ->
            found.filterKeys { it == row.createdLocalId }.values.forEach { logged(row, releases.adjudicate(row, it)) }
        }
    }

    private suspend fun logged(row: UnconfirmedImport, adjudicated: DownloadRead<Adjudicated>) = when (adjudicated) {
        is DownloadRead.Ok -> log.i(
            "adjudicated",
            "asset" of row.ref.sourceAssetId,
            "verdict" of adjudicated.value.verdict,
            "applied" of adjudicated.value.applied,
        )
        is DownloadRead.Unavailable -> log.w(
            "an unconfirmed import could not be adjudicated",
            "why" of adjudicated.detail,
        )
    }
}
