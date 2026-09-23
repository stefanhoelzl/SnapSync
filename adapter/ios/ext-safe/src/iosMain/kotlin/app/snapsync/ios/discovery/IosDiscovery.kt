package app.snapsync.ios.discovery

import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.PermissionStatus
import app.snapsync.logging.invocation
import app.snapsync.model.CandidateRead
import app.snapsync.model.Resource
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.SelectionPolicy
import app.snapsync.ports.Discovery
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Photos.PHAsset

/**
 * The iOS [UploadDiscovery]: the photo-library reads BOTH upload tiers' cycles make — the
 * full-enumeration library walk ([discover]) and the id-scoped resolve of ledger keys ([resourcesFor]).
 * Each composition root binds one instance as its cycle's discovery; no
 * transport holds it, because only the *job lifecycle* (create/fetch/retry/acknowledge) differs between the
 * tiers.
 *
 * Both reads are wrapped in the same `platform.discoverResources` / `platform.resourcesFor` invocation lines
 * the transports used to emit when they forwarded here, under the logger the root passes, so a device log
 * reads exactly as it did.
 *
 * Held to `UploadDiscoveryContract` on the simulator's test executable (no grant) and in the simulator app
 * (a full grant); the in-memory fake the harness uses is held to the same clauses.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDiscovery(
    private val log: Logger,
    private val source: PhotoKitCandidateSource,
    /**
     * The process's photo grant, read at each walk. A walk is **authoritative for deletion only under a full
     * grant** (capability `port-contracts`, `UploadDiscoveryContract`): without one PhotoKit returns an empty
     * fetch, and under a partial one only the selection. Neither is evidence that anything left the library,
     * and the cycle deletes the in-window rows of every asset an authoritative walk did not return.
     *
     * Found by the contract on its first run against this adapter: it reported the no-grant empty fetch as a
     * full enumeration, and only the cycles' own grant gates kept that from the presence diff.
     */
    private val grant: () -> PermissionStatus,
) : UploadDiscovery {

    /** Production: the process's own photo grant (a secondary constructor, not a default — capability `module-architecture`). */
    constructor(log: Logger, source: PhotoKitCandidateSource) : this(log, source, ::currentPhotoPermission)
    /**
     * Every in-scope candidate asset — a **full enumeration**, narrowed by [policy] at the fetch. There is no
     * change-token cursor (capability `ios-photokit-upload`, "In-extension discovery by full enumeration"):
     * each walk reports what IS, so the cycle recomputes presence every time rather than remembering absence.
     * Identical for both upload tiers.
     *
     * The answer is **authoritative for deletion** ([Discovery.fullEnumeration]) only when the library was
     * read. `PhotoKitCandidateSource` always reports a readable library — it is the raw walk, and whether a
     * read is permitted at all is decided above it (capability `gallery-status`) — so the other branch is
     * unreachable today, and it still states the right answer rather than a convenient one: no candidates
     * and NOT authoritative, so an un-enumerated cycle costs an idle pass, never a photo's rows.
     *
     * **The whole body hops to [Dispatchers.Default], and that hop buys CONCURRENCY, not safety.**
     * Keeping this off the main thread is no longer this seam's job: the app's composition scope is a
     * dedicated non-UI lane, so every adapter is off-main whether it hops or not (spec
     * `module-architecture`, law "Dispatcher lanes are fixed by the composition"). What the hop still
     * buys is that this walk does not occupy that **serial** lane while it runs, so other app-scope work
     * proceeds alongside it. `Dispatchers.Default` rather than an I/O pool because Kotlin/Native exposes
     * no **public** `Dispatchers.IO` (coroutines 1.10.2: it exists in the klib and is `internal` —
     * established by compile, not by reading the symbol table). Expiry: a coroutines release that
     * publishes it.
     *
     * Why any of this matters: **every** PhotoKit touch below is a synchronous XPC round-trip into
     * `assetsd` — the policy-narrowed fetch and the per-asset `creationDate` read behind every candidate.
     * Any one of them blocking on main trips the 10 s scene-update watchdog and the OS kills the app (`0x8BADF00D`). Forcing proof:
     * build 521 died exactly this way on 2026-07-26 (iPhone11,2 / iOS 18.7.9) with `assetsd` wedged
     * inside `fetchPersistentChangesSinceToken` — 0.071 s of app CPU across the whole allowance, i.e.
     * blocked, not busy. That proof is what the composition lane now answers for every adapter; it is
     * kept here because this seam is where it was measured. Expires only if PhotoKit gains an async
     * change-feed API.
     *
     * The hop does not make a wedged `assetsd` return — that cycle still parks until it recovers. It
     * parks somewhere harmless, which is the whole point. A timeout is no substitute: cancellation is
     * cooperative and the thread is inside a synchronous XPC call, so it would free the coroutine and
     * leak the thread.
     */
    override suspend fun discover(policy: SelectionPolicy): Discovery =
        log.invocation("platform.discoverResources", result = { "${it.candidates.size} candidate(s)" }) {
            withContext(Dispatchers.Default) {
                val authoritative = grant() == PermissionStatus.GRANTED
                when (val read = source.candidates(policy)) {
                    is CandidateRead.Readable ->
                        Discovery(candidates = read.candidates, fullEnumeration = authoritative)
                    CandidateRead.NotReadable -> Discovery(candidates = emptyList(), fullEnumeration = false)
                }
            }
        }

    /**
     * Resolve ledger [keys] to uploadable resources, **by identifier** — the id-scoped read that lets a
     * producer enqueue from the ledger instead of from a walk (capability `sync-ledger`).
     *
     * A `fetchAssetsWithLocalIdentifiers` + [PhotoKitCandidateSource.candidatesFrom] pair, pointed at a key
     * set. The cost is one fetch plus the per-asset resource read for exactly the assets asked for; the walk it
     * replaces is one round-trip per asset in the whole in-scope library.
     *
     * Partial by contract: a key whose asset has left the library simply does not come back. The filter
     * at the end is what makes that true — an asset resolves to all of its resources, and only the keys
     * asked for are kept, so a Live Photo's paired video is never smuggled in beside a request for its
     * still.
     *
     * On [Dispatchers.Default] for the reason [discover] documents at length: every call below is a
     * synchronous XPC round-trip into `assetsd`, and this hop keeps them off the composition's serial
     * lane rather than off the main thread (which the composition already guarantees).
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> =
        log.invocation("platform.resourcesFor", params = "${keys.size} key(s)", result = { "${it.size} resource(s)" }) {
            withContext(Dispatchers.Default) {
                if (keys.isEmpty()) return@withContext emptyList()
                val localIds = keys.mapTo(linkedSetOf()) { denormalizeAssetId(assetIdFromUploadKey(it)) }
                val assets = PHAsset.fetchAssetsWithLocalIdentifiers(localIds.toList(), null)
                source.candidatesFrom(assets)
                    .flatMap { it.resources() }
                    .filter { it.filename in keys }
            }
        }
}
