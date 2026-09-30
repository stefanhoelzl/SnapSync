package app.snapsync.feature.membership

import app.snapsync.model.AdoptedAsset
import app.snapsync.model.AssetRef
import app.snapsync.model.EventConfig
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.MarkedPhotoLookup
import app.snapsync.services.identity.PersistedDeviceIdentity
import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound on the join-time union read — the join surface is waiting, as for [ShareSetLoad]'s listing. */
private const val UNION_TIMEOUT_MS = 15_000L

/**
 * The join-time adoption (capability `receiving-photos`, "A deleted received photo never comes back" and "Received
 * photos are never shared back"): at a provision into a **new** membership, recognise the photos still in the library
 * that an earlier install received for this event, by the SnapSync mark each one's filename carries
 * ([ReceivedPhotoName]), and record each as its ref's confirmed import.
 *
 * Deleting the app deletes the download store, which is the only record of which library photos came from SnapSync.
 * Without this, a reinstalled member who rejoins receives the event's photos again, and shares the received ones that
 * lie in their capture range back as their own. An adopted row carries the photo as its created local id — the
 * suppression handle — so the same record stops both: the ref is never planned, and the asset leaves the upload
 * universe through the existing `NotEcho` rule. No selection rule reads a filename.
 *
 * It runs at two moments, for EVERY direction — a share-only member can share a received photo back just as well:
 * - **at the join, before the config is saved** ([MembershipEntry]), so no uploader sees the membership while a
 *   received photo in its range is unsuppressed — enough when the grant is already usable;
 * - **when the photo grant becomes usable while joined**, before the uploads are armed and the staged downloads are
 *   imported (the composition's permission subscription). A reinstall resets the grant, so its rejoin ALWAYS
 *   provisions with the dialog still open and reads nothing at the first moment (measured on the SE2, 2026-09-30).
 *   The downloads it planned meanwhile wait: no import runs without a usable grant (`DownloadController`).
 *
 * It writes through [record] — the download controller's locked write, so a ref an import has claimed is never
 * adopted underneath it — and records a ref with no row, or one planned but not yet imported
 * ([DownloadService.adoptAll]). What it leaves alone, each an accepted gap of the spec: every other row (this install's
 * own record); a photo outside what the grant lets the app read; a union it could not fetch. Two library photos
 * carrying one token (a duplicate import) adopt one of them. It never throws: the join completes whatever the union or
 * the library answer.
 */
class ReceivedPhotoAdoption(
    private val union: EventUnionSource,
    /** The download store's reads: which refs are settled, which library assets it already records. */
    private val store: DownloadService,
    private val library: MarkedPhotoLookup,
    /** The adoption's write, answering the refs it recorded (`DownloadController.settleAdopted`). */
    private val record: suspend (adopted: Collection<AdoptedAsset>, eventId: String) -> Set<AssetRef>,
    /** The device identity; own refs are never foreign and never adopted. */
    private val identity: PersistedDeviceIdentity,
    private val log: Logger = Logger.withTag("ReceivedPhotoAdoption"),
) {
    suspend fun adopt(cfg: EventConfig) {
        try {
            adoptOrFail(cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "adoption failed — photos received before a reinstall may arrive again" }
        }
    }

    private suspend fun adoptOrFail(cfg: EventConfig) {
        val assets = withTimeoutOrNull(UNION_TIMEOUT_MS) { union.union(cfg.eventId) }?.getOrNull()
        if (assets == null) {
            log.w { "union unavailable — no photos adopted; photos received before a reinstall may arrive again" }
            return
        }
        val me = identity.deviceId()
        val foreign = assets.filter { it.deviceId != me }.associateBy { AssetRef(it.deviceId, it.assetId) }
        val settled = store.settledAmong(foreign.keys)
        val open = foreign.filterKeys { it !in settled }
        if (open.isEmpty()) return
        // A token two refs share is ambiguous; neither is adopted, and each downloads as it would have.
        val byToken = open.keys.groupBy(ReceivedPhotoName::token).filterValues { it.size == 1 }.mapValues { it.value.single() }
        val marked = library.markedIn(cfg.startsAt, cfg.endsAt, known = store.suppressedLocalIds())
        val adopted = marked.mapNotNull { (token, localId) ->
            byToken[token]?.let { ref -> AdoptedAsset(ref, localId, foreign.getValue(ref).creationDate) }
        }
        val recorded = record(adopted, cfg.eventId)
        log.i { "adopted ${recorded.size} received photo(s) of ${open.size} open foreign ref(s); ${marked.size} marked in window" }
    }
}
