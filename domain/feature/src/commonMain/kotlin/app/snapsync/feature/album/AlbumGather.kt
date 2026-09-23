package app.snapsync.feature.album

import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.DeviceIdentity
import app.snapsync.model.EventConfig
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.admittedAssetIds
import app.snapsync.model.denormalizeAssetId
import app.snapsync.ports.AssetRef
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
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
 *  - **own** — the device manifest projection: [LedgerStore.manifestRows] admitted by the membership's
 *    **current** policy, exactly what the device tells the event it contributes;
 *  - **foreign** — the event union's other-device assets this device has imported, resolved by ref through
 *    [DownloadStore.importedLocalIds]. The store is event-blind; the union is what says an asset is in THIS
 *    event, so an import made for another event is never gathered.
 *
 * **App-only by construction.** It is a separate class, built only in the app composition, precisely so the
 * extension — which constructs an [AlbumCoordinator] for enqueue-time placement — has nothing to call.
 *
 * **Started, never awaited.** The opt-in acts ([start] from a provision or a reconfigure Save, and
 * [onAccessObserved] from the permission subscription) launch it on the app-lifetime [scope] and return: a
 * join and a Save must not wait on a cost that grows with the photos held. [awaitStarted] exists only for the
 * operator harness and tests, which drive the stack synchronously.
 *
 * It never ensures the album: its triggers do that first, and a gather that also created albums would race
 * the grant subscription's creation into a duplicate. It keeps **no record** of what it placed: adding an
 * asset already in the collection is a no-op (measured, simulator, iOS 26.5), so a repeat costs O(N) and
 * places nothing twice. Best-effort throughout — a failed union read skips only the foreign half, and a
 * failed batch is swallowed by [AlbumCoordinator.place].
 */
class AlbumGather(
    private val configSource: ConfigSource,
    private val ledger: LedgerStore,
    /** The membership's one selection policy — the same derivation every other consumer uses. */
    private val policyFor: suspend (EventConfig) -> SelectionPolicy,
    private val union: EventUnionSource,
    private val downloads: DownloadStore,
    private val identity: DeviceIdentity,
    /** The photo grant: a gather without usable access (`grantsPhotoAccess`) could only fail its adds. */
    private val photoAccess: PhotoAccessStatusSource,
    private val coordinator: AlbumCoordinator,
    /** The app-lifetime scope a gather is launched on — the composition lane, never the UI lane: the
     *  platform add blocks its thread for a whole library change. */
    private val scope: CoroutineScope,
    private val logScope: LogScope,
    private val batchSize: Int = GATHER_BATCH_SIZE,
    private val log: Logger = Logger.withTag("AlbumGather"),
) {
    // One gather at a time. A gather started while another runs waits, then re-reads the config — so a Save
    // that lands mid-gather is honoured by the queued run rather than lost.
    private val running = Mutex()
    private val started = MutableStateFlow<Set<Job>>(emptySet())

    // Whether access was usable at the previous permission emission; `null` before the first, which is the
    // StateFlow's replay rather than a change.
    private var lastUsable: Boolean? = null

    /** Start a gather for [eventId], detached. [trigger] names the opt-in act, so the log says why it ran. */
    fun start(trigger: String, eventId: String) {
        val job = scope.launch {
            log.invocation(logScope, "albumGather", "trigger=$trigger eventId=$eventId") { gather(eventId) }
        }
        started.update { it + job }
        job.invokeOnCompletion { started.update { running -> running - job } }
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

    /** Wait for every gather started so far — for the operator harness and tests only. */
    suspend fun awaitStarted() {
        started.value.joinAll()
    }

    suspend fun gather(eventId: String) = running.withLock {
        val cfg = configSource.config.value
        when {
            cfg == null || cfg.eventId != eventId -> log.i { "gather: event=$eventId is no longer joined — skipping" }
            !cfg.saveToAlbum -> log.i { "gather: event=$eventId opted out of the album — skipping" }
            !photoAccess.permission.value.grantsPhotoAccess -> log.i { "gather: photo access not usable — skipping event=$eventId" }
            else -> {
                val ids = ownSet(cfg) + foreignSet(eventId)
                log.i { "gather: placing ${ids.size} asset(s) for event=$eventId" }
                ids.chunked(batchSize).forEach { batch -> coordinator.place(eventId, batch) }
            }
        }
    }

    private suspend fun ownSet(cfg: EventConfig): List<String> {
        val rows = ledger.manifestRows()
        val admitted = admittedAssetIds(rows, policyFor(cfg))
        return admitted.sorted().map(::denormalizeAssetId)
    }

    private suspend fun foreignSet(eventId: String): List<String> {
        val assets = union.union(eventId).getOrElse {
            log.w(it) { "gather: union read failed for event=$eventId — gathering own photos only" }
            return emptyList()
        }
        val self = identity.deviceId()
        val refs = assets.filter { it.deviceId != self }.map { AssetRef(it.deviceId, it.assetId) }
        return downloads.importedLocalIds(refs).values.sorted()
    }
}
