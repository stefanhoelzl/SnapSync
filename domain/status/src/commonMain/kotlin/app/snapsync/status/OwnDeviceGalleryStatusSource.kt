package app.snapsync.status

import app.snapsync.model.Contribution
import app.snapsync.ports.GalleryResourceEnumerator
import app.snapsync.ports.GalleryStatusSource
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.excludedAssetIds
import co.touchlab.kermit.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The own-device upload **total** `N` (capability `sync-status`): the count of this device's OWN
 * qualifying assets. It is enumeration-only — **no** storage LIST — so it stays honest the instant a
 * photo is taken, before the background extension uploads anything (completeness comes separately from
 * the ledger, see [LedgerCountsSource]).
 *
 * **The upload universe is this device's OWN photos** — the gallery minus any asset this device
 * *downloaded and imported* from other contributors (capability `photo-download`). Downloaded photos
 * live in the library (so the raw gallery count includes them) but are **suppressed from upload**, so
 * counting them would peg upload progress permanently below 100% (e.g. "9 of 11" when 2 are
 * downloaded). [suppressedLocalIds] (the download store's `createdLocalId` set, byte-identical to the
 * enumerator's `assetId` form) is excluded from the total.
 *
 * **The capture-date cutoff scopes the total too** (capability `photo-selection-policy`): an asset whose
 * `creationDate` precedes the contribution's cutoff is neither uploaded nor listed in the manifest, so counting it
 * would peg upload progress permanently below 100% ("pending" forever). The total therefore counts only
 * assets at or after the cutoff — the same set the upload cycle admits — so the joined screen settles to
 * "in sync" once every in-scope asset is uploaded. A membership always carries a cutoff, so there is no
 * whole-library total.
 *
 * The cutoff is passed **into** the enumeration, so the platform walk fetches only in-scope assets rather
 * than the whole library; the post-enumeration filter below still runs and remains authoritative (the
 * walk's fetch predicate may over-return).
 *
 * [size] is a level-triggered count; [refresh] re-enumerates and recomputes it (invoked on foreground
 * entry / library change / (re)join by the composition root).
 *
 * The cutoff is a [refresh] **parameter**, not an injected supplier: it belongs to a membership, and a
 * device with no membership has no scope to count — so the composition root simply does not refresh, and
 * `N` stays at its seeded `0`. There is deliberately no "no cutoff" value to pass.
 */
class OwnDeviceGalleryStatusSource(
    private val enumerator: GalleryResourceEnumerator,
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
    // Denylisted-album membership (capability `photo-selection-policy`) — the SAME lookup the upload cycle
    // is given. The origin rules that read facts off the resource are applied inline below via
    // `excludedAssetIds`; album membership is the one that needs a platform lookup, so it is injected.
    // Takes the cutoff, which scopes the album member fetch exactly as it scopes the walk.
    private val albumExcludedAssetIds: suspend (String) -> Set<String> = { emptySet() },
    private val log: Logger = Logger.withTag("gallery"),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : GalleryStatusSource {

    private val _size = MutableStateFlow(0)

    /** The upload total `N`: the count of this device's OWN in-scope assets (downloads + pre-cutoff excluded). */
    override val size: StateFlow<Int> = _size.asStateFlow()

    /**
     * Re-enumerate within [contribution] (what the joined membership contributes) and recompute `N`.
     *
     * **The direction gate, for the total** (capability `photo-selection-policy`). `N` must count *the same
     * set the upload cycle admits* — this source's own invariant, a few lines up. The cutoff and the origin
     * exclusions were always honoured on both sides; the participation **direction** was honoured on
     * neither, and each side improvised around its absence. The cycle's side uploaded a non-contributor's
     * camera roll (capability `upload-lifecycle`). This side left `N` reporting a library that would never
     * be uploaded, and the status screen force-hid the arrow over a total that could never settle. Same
     * omission, two symptoms.
     *
     * [Contribution.None] therefore reports `0` **without enumerating**. The short-circuit must live here:
     * `N` is a *parallel* computation that no upload gate feeds — unlike the download arm, whose total flows
     * through its gate and is zero for free.
     *
     * The enumeration's cost and shape are **logged** (capability `diagnostic-logging`). This walk is one
     * synchronous PhotoKit round-trip per in-scope asset — the cost the capture-date bound exists to
     * contain — and it runs on every foreground entry. Without a line here, whether the bound is actually
     * bounding anything is invisible on a real device: a bounded and an unbounded fetch differ only in how
     * many assets they touch, and that number is otherwise never reported.
     */
    suspend fun refresh(contribution: Contribution) {
        val cutoff = when (contribution) {
            Contribution.None -> {
                // Not a walk that finds nothing — no walk at all. A 4000-photo library would cost minutes
                // of XPC to arrive at the empty set the direction already told us.
                _size.value = 0
                log.i { "gallery: this membership contributes nothing → N=0 (no enumeration)" }
                return
            }
            is Contribution.Since -> contribution.cutoff
        }
        val started = timeSource.markNow()
        val suppressed = suppressedLocalIds()
        // Own universe = enumerated assets minus downloads (echo) minus pre-cutoff minus origin-excluded
        // (capability `photo-selection-policy`) — exactly the set the upload cycle admits, so completeness
        // can reach 100%.
        //
        // The identity with the cycle's admitted set is a REQUIREMENT, not a coincidence: this runs in the
        // app process and the cycle runs in the upload path, they enumerate independently, and any rule
        // applied there but not here would count an asset that is never uploaded — pegging the joined screen
        // below 100% forever, which is the exact failure the cutoff scoping already exists to prevent.
        val enumerated = enumerator.enumerate(cutoff)
        val preCutoff = enumerated.count { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") < cutoff }
        val originExcluded = excludedAssetIds(enumerated) + albumExcludedAssetIds(cutoff)
        val size = enumerated
            .asSequence()
            .filter { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= cutoff }
            .map { it.assetId }
            .filter { it !in suppressed }
            .filter { it !in originExcluded }
            .distinct()
            .count()
        _size.value = size
        val elapsed = started.elapsedNow()
        log.i {
            "gallery: enumerated ${enumerated.size} resource(s) since $cutoff " +
                "($preCutoff over-returned pre-cutoff, ${suppressed.size} suppressed, " +
                "${originExcluded.size} origin-excluded) " +
                "→ N=$size own in-scope asset(s) in ${elapsed.inWholeMilliseconds}ms"
        }
    }
}
