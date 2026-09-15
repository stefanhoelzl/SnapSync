package app.snapsync.ios.discovery

import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.logging.invocation
import app.snapsync.model.CandidateRead
import app.snapsync.model.Resource
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.SelectionPolicy
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.Discovery
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Photos.PHAsset
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary

/**
 * The iOS [UploadDiscovery]: the photo-library reads BOTH upload tiers' cycles make — the
 * persistent-change-token library walk ([discover]), the id-scoped resolve of ledger keys ([resourcesFor]),
 * and change-token (un)archiving. Each composition root binds one instance as its cycle's discovery; no
 * transport holds it, because only the *job lifecycle* (create/fetch/retry/acknowledge) differs between the
 * tiers.
 *
 * Both reads are wrapped in the same `platform.discoverResources` / `platform.resourcesFor` invocation lines
 * the transports used to emit when they forwarded here, under the logger the root passes, so a device log
 * reads exactly as it did.
 *
 * Decision-free platform code (faked in the harness); not unit-tested — the change-token subsystem is
 * exercised on device/simulator, with a smoke test confirming enumeration is callable.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDiscovery(
    private val log: Logger,
    private val source: PhotoKitCandidateSource,
) : UploadDiscovery {
    private val library: PHPhotoLibrary get() = PHPhotoLibrary.sharedPhotoLibrary()

    /**
     * The candidate assets changed since [sinceToken] (null / unarchivable / expired → a full
     * enumeration, narrowed by [policy]), plus the cursor to persist once the cycle drains. Identical for
     * both upload tiers.
     *
     * The **id-scoped** variant lives here rather than on [CandidateSource]: only this class has
     * identifiers to scope by, because only it reads the change feed. Putting it on the shared seam would
     * push an upload-only concern onto the port the status total and the join preview also hold.
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
     * `assetsd` — the change fetch, each `changeDetailsForObjectType`, the identifier fetch, the
     * per-asset `creationDate` read inside [PhotoKitCandidateSource.candidatesFrom] (which, unlike its
     * sibling `candidates`, hops nowhere), and `currentChangeToken`. Any one of them blocking on main
     * trips the 10 s scene-update watchdog and the OS kills the app (`0x8BADF00D`). Forcing proof:
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
    override suspend fun discover(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery =
        log.invocation("platform.discoverResources", result = { "${it.candidates.size} candidate(s)" }) {
            withContext(Dispatchers.Default) {
                val token = sinceToken?.let(::unarchiveToken)
                val changes = token?.let { library.fetchPersistentChangesSinceToken(it, error = null) }
                if (token == null || changes == null) {
                    // Full enumeration: every current IN-SCOPE candidate — the live set the cycle reconciles
                    // against. No change feed, so no incremental removals. The policy narrows the fetch so the
                    // walk does not touch every library asset; the cycle's own admission stays authoritative
                    // over what comes back.
                    // `PhotoKitCandidateSource` always reports a readable library — it is the raw walk, and
                    // whether a read is permitted at all is decided above it (capability `gallery-status`).
                    // The other branch is therefore unreachable today, and it still states the right answer
                    // rather than a convenient one: **keep the cursor**. Advancing it over assets nobody
                    // enumerated would step the change feed permanently past them, and no later incremental
                    // walk would return them (capability `limited-photo-access`, "The read discipline is
                    // enforced at the mechanism…"). An un-enumerated cycle must cost an idle pass, never a
                    // photo.
                    return@withContext when (val read = source.candidates(policy)) {
                        is CandidateRead.Readable -> Discovery(
                            candidates = read.candidates,
                            nextToken = archiveToken(library.currentChangeToken),
                            fullEnumeration = true,
                        )
                        CandidateRead.NotReadable -> Discovery(
                            candidates = emptyList(),
                            nextToken = sinceToken ?: ByteArray(0),
                            fullEnumeration = false,
                        )
                    }
                }
                // Incremental: derive changed assets to (re)upload and removed assets to prune. Removed ids
                // are normalized `/`→`_` so they match the `<localId>-…` key scheme.
                //
                // `fetchAssetsWithLocalIdentifiers` takes no predicate, so the policy cannot narrow this fetch.
                // It does not need to: the candidates it returns carry facts only, and the cycle's admission
                // rejects the out-of-scope ones BEFORE any of them is asked for its resources. A change feed says
                // what CHANGED, not what is in SCOPE — an iCloud sync can hand back thousands of decades-old
                // assets — and the expensive part is the per-asset resource read, which none of them now reaches.
                val identifiers = linkedSetOf<String>()
                val removed = linkedSetOf<String>()
                changes.enumerateChangesWithBlock { change, _ ->
                    val details = change?.changeDetailsForObjectType(PHObjectTypeAsset, error = null)
                        ?: return@enumerateChangesWithBlock
                    details.insertedLocalIdentifiers().forEach { identifiers.add(it as String) }
                    details.updatedLocalIdentifiers().forEach { identifiers.add(it as String) }
                    details.deletedLocalIdentifiers().forEach { removed.add((it as String).replace('/', '_')) }
                }
                Discovery(
                    candidates = source.candidatesFrom(
                        PHAsset.fetchAssetsWithLocalIdentifiers(identifiers.toList(), null),
                    ),
                    nextToken = archiveToken(library.currentChangeToken),
                    removedAssetIds = removed.toList(),
                )
            }
        }

    /**
     * Resolve ledger [keys] to uploadable resources, **by identifier** — the id-scoped read that lets a
     * producer enqueue from the ledger instead of from a walk (capability `sync-ledger`).
     *
     * The same `fetchAssetsWithLocalIdentifiers` + [PhotoKitCandidateSource.candidatesFrom] pair the
     * incremental branch of [discover] already uses, pointed at a key set rather than a change set. The
     * cost is one fetch plus the per-asset resource read for exactly the assets asked for; the walk it
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

    // Token (un)archiving is best-effort efficiency only: any failure degrades to a full
    // re-enumeration (null token), which the ledger makes harmless — it must never fail the cycle.
    private fun archiveToken(token: PHPersistentChangeToken): ByteArray =
        runCatching {
            NSKeyedArchiver.archivedDataWithRootObject(token, requiringSecureCoding = true, error = null)?.toByteArray()
        }.onFailure { log.w(it) { "archiveToken failed — cursor will not advance this cycle" } }
            .getOrNull() ?: ByteArray(0)

    private fun unarchiveToken(bytes: ByteArray): PHPersistentChangeToken? =
        runCatching {
            NSKeyedUnarchiver.unarchivedObjectOfClass(PHPersistentChangeToken, bytes.toNSData(), error = null)
                as? PHPersistentChangeToken
        }.onFailure { log.w(it) { "unarchiveToken failed — re-enumerating from scratch" } }
            .getOrNull()
}
