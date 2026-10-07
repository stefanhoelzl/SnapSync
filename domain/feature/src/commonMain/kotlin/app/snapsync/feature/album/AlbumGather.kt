package app.snapsync.feature.album

import app.snapsync.model.AssetId
import app.snapsync.model.EntryScope
import app.snapsync.model.EventConfig
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.admittedAssetIds
import app.snapsync.model.invocation
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.ledger.LedgerService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How many assets one gather hands to one library change. The platform add is one `performChangesAndWait`
 * over whatever it is given, and it blocks its thread for that whole change. Its cost was measured as
 * **linear**, about 0.65 ms per asset on an iOS 26 simulator, so the size does not change a gather's total
 * cost. It only bounds each change: about 0.3 s at 500. Measurement:
 * `changes/archive/2026-09-21-album-gathers-retroactively` design D5.
 */
const val GATHER_BATCH_SIZE: Int = 500

/**
 * The event album's **gather** (capability `event-album`, "Ensuring the album gathers what the device already
 * holds"): place into the album the photos of the event this device already holds. Enqueue-time and
 * import-time placement cover what is synced once an album exists; this covers the rest — photos shared or
 * received before the member opted in, and photos contributed during an earlier event that this event's
 * window also admits (listed in this event's manifest, never enqueued again, so never placed at enqueue).
 *
 * Two sets, and only these:
 *  - **own** — the device manifest projection: [LedgerService.manifestRows] admitted by the membership's
 *    **current** policy, exactly what the device tells the event it contributes;
 *  - **foreign** — the other-device assets of this event's union that this device has imported
 *    ([DownloadService.importedLocalIdsOf]): every reconcile tags the event's whole foreign union in the store, so
 *    the store says which imports are in THIS event without a union read of the gather's own, and an import made
 *    for another event is never gathered.
 *
 * **App-only by construction.** It is a separate class, built only in the app composition, precisely so the
 * extension — which constructs an [AlbumCoordinator] for enqueue-time placement — has nothing to call.
 *
 * **Started, never awaited.** The opt-in acts ([start] from a provision or a reconfigure Save, and
 * [onAccessObserved] from the permission subscription) launch it on the app-lifetime [scope] and return: a
 * join and a Save must not wait on a cost that grows with the photos held.
 *
 * Where the album is a folder (Android, [AlbumCoordinator.placesOwnPhotos] false) only the foreign set is gathered,
 * and gathering MOVES those photos out of the camera folder into the album
 * (`changes/archive/2026-09-30-android-event-album` D5).
 *
 * It never ensures the album: its triggers do that first, and a gather that also created albums would race
 * the grant subscription's creation into a duplicate. It keeps **no record** of what it placed: adding an
 * asset already in the collection is a no-op (measured, simulator, iOS 26.5), so a repeat costs O(N) and
 * places nothing twice. Best-effort throughout — a failed batch is swallowed by [AlbumCoordinator.place].
 */
class AlbumGather(
    private val configSource: ConfigService,
    private val ledger: LedgerService,
    /** The membership's one selection policy — the same derivation every other consumer uses. */
    private val policyFor: suspend (EventConfig) -> SelectionPolicy,
    private val downloads: DownloadService,
    /** The photo grant: a gather without usable access (`grantsPhotoAccess`) could only fail its adds. */
    private val photoAccess: GalleryAccessState,
    private val coordinator: AlbumCoordinator,
    /** The app-lifetime scope a gather is launched on — the composition lane, never the UI lane: the
     *  platform add blocks its thread for a whole library change. */
    private val scope: CoroutineScope,
    private val entryContext: EntryScope,
    private val batchSize: Int = GATHER_BATCH_SIZE,
    private val log: Logger = Logger.withTag("AlbumGather"),
) {
    // One gather at a time. A gather started while another runs waits, then re-reads the config — so a Save
    // that lands mid-gather is honoured by the queued run rather than lost.
    private val running = Mutex()

    // Whether access was usable at the previous permission emission; `null` before the first, which is the
    // StateFlow's replay rather than a change.
    private var lastUsable: Boolean? = null

    /** Start a gather for [eventId], detached. [trigger] names the opt-in act, so the log says why it ran. */
    fun start(trigger: String, eventId: String) {
        scope.launch {
            log.invocation(entryContext, "albumGather", "trigger=$trigger eventId=$eventId") { gather(eventId) }
        }
    }

    /**
     * Feed every permission emission, **after** the caller has ensured the album. Starts a gather for the
     * joined event only when access became usable while the process was running: never on the first emission
     * (an already-granted cold launch gathers nothing), and never on a usable → usable change such as
     * `LIMITED` → `GRANTED`.
     */
    fun onAccessObserved(usable: Boolean) {
        val wasUsable = lastUsable
        lastUsable = usable
        val eventId = configSource.config.value?.eventId ?: return
        if (usable && wasUsable == false) start("grant", eventId)
    }

    suspend fun gather(eventId: String) = running.withLock {
        val cfg = configSource.config.value
        when {
            cfg == null || cfg.eventId != eventId -> log.i { "gather: event=$eventId is no longer joined — skipping" }
            !cfg.saveToAlbum -> log.i { "gather: event=$eventId opted out of the album — skipping" }
            !photoAccess.usable -> log.i { "gather: photo access not usable — skipping event=$eventId" }
            else -> {
                val own = if (coordinator.placesOwnPhotos) ownSet(cfg) else emptyList()
                val foreign = downloads.importedLocalIdsOf(eventId).sorted()
                log.i { "gather: placing ${own.size} own and ${foreign.size} received asset(s) for event=$eventId" }
                own.chunked(batchSize).forEach { batch -> coordinator.place(eventId, batch) }
                foreign.chunked(batchSize).forEach { batch -> coordinator.placeReceived(eventId, batch) }
            }
        }
    }

    private suspend fun ownSet(cfg: EventConfig): List<AssetId> {
        val rows = ledger.manifestRows()
        val admitted = admittedAssetIds(rows, policyFor(cfg))
        return admitted.sorted()
    }
}
