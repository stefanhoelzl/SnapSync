package app.snapsync.feature.status

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.candidatesFromResources
import app.snapsync.model.excluding
import app.snapsync.ports.GalleryStatusSource
import app.snapsync.ports.PhotoLibrary
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
 * **`N` counts the admitted set, and nothing else** (capability `photo-selection-policy`). It counts
 * exactly the assets the upload cycle admits, by asking the *same* [SelectionPolicy] rather than
 * re-applying its rules — the identity is a REQUIREMENT, not a coincidence. This source runs in the app
 * process and the cycle runs in the upload path; they enumerate independently, and any rule applied there
 * but not here counts an asset that is never uploaded, pegging the joined screen below 100% forever
 * ("Synchronization pending…").
 *
 * That is not hypothetical. `N` used to re-state the policy here — cutoff, echo, origin — and when the
 * capture-date **ceiling** was added it reached the upload filter but not this one. A photo taken after
 * the event's end counted toward `N` and never uploaded, so completeness could never reach 100%. Reading
 * one admitted set is what makes that unrepresentable.
 *
 * The cutoff is passed **into** the enumeration, so the platform walk fetches only in-scope assets rather
 * than the whole library; the post-enumeration admission below still runs and remains authoritative (the
 * walk's fetch predicate may over-return).
 *
 * [size] is a level-triggered count; [refresh] re-enumerates and recomputes it (invoked on foreground
 * entry / library change / (re)join by the composition root).
 *
 * The policy is a [refresh] **parameter**, not an injected supplier: it belongs to a membership, and a
 * device with no membership has no scope to count — so the composition root simply does not refresh, and
 * `N` stays at its seeded `0`. There is deliberately no "no policy" value to pass.
 */
class OwnDeviceGalleryStatusSource(
    private val enumerator: PhotoLibrary,
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
    // Denylisted-album membership (capability `photo-selection-policy`) — the SAME lookup the upload cycle
    // is given, and the one origin fact that is not already on the asset. Takes the cutoff, which scopes
    // the album member fetch exactly as it scopes the walk.
    private val albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String> = { emptySet() },
    private val log: Logger = Logger.withTag("gallery"),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : GalleryStatusSource {

    private val _size = MutableStateFlow(0)

    /** The upload total `N`: the count of this device's OWN admitted assets. */
    override val size: StateFlow<Int> = _size.asStateFlow()

    /**
     * Re-enumerate within [configPolicy] (what the joined membership contributes) and recompute `N`.
     *
     * **The direction gate, for the total** (capability `photo-selection-policy`). A non-contributing
     * membership reports `0` **without enumerating** — the short-circuit must live here, because `N` is a
     * *parallel* computation that no upload gate feeds (unlike the download arm, whose total flows through
     * its gate and is zero for free). A walk costs one synchronous PhotoKit round-trip per in-scope asset
     * (~110 ms on an SE2), so a 4000-photo library would spend minutes of XPC to reach the empty set the
     * direction already told us.
     *
     * The enumeration's cost and shape are **logged** (capability `diagnostic-logging`). Without a line
     * here, whether the capture bound is actually bounding anything is invisible on a real device: a
     * bounded and an unbounded fetch differ only in how many assets they touch.
     */
    suspend fun refresh(configPolicy: SelectionPolicy) {
        val cutoff = configPolicy.walkFloor
        if (!configPolicy.enumerates || cutoff == null) {
            _size.value = 0
            log.i { "gallery: this membership contributes nothing → N=0 (no enumeration)" }
            return
        }
        val started = timeSource.markNow()
        val enumerated = enumerator.enumerate(cutoff.at.iso)
        val size = count(enumerated, configPolicy, cutoff)
        val elapsed = started.elapsedNow()
        log.i {
            "gallery: enumerated ${enumerated.size} resource(s) since $cutoff " +
                "→ N=$size own admitted asset(s) in ${elapsed.inWholeMilliseconds}ms"
        }
    }

    /**
     * Recompute `N` from a **provided** resource list instead of enumerating (capability
     * `limited-photo-access`, "One discovery serves both the status total and the enqueue"): under a
     * partial grant, the selection snapshot the upload discovery consumes is also what the total counts
     * — one read, and `N` and the upload set are provably the same universe. The admission is the SAME
     * one the enumerating [refresh] applies, so the policy identity holds by construction.
     */
    suspend fun refreshFrom(resources: List<Resource>, configPolicy: SelectionPolicy) {
        val cutoff = configPolicy.walkFloor
        if (!configPolicy.enumerates || cutoff == null) {
            _size.value = 0
            log.i { "gallery: this membership contributes nothing → N=0 (snapshot ignored)" }
            return
        }
        val size = count(resources, configPolicy, cutoff)
        log.i {
            "gallery: selection snapshot of ${resources.size} resource(s) → N=$size own admitted asset(s)"
        }
    }

    /**
     * The one admission, shared by [refresh] and [refreshFrom]: `N` is the **size of the admitted set**,
     * asked of the same [EventPhotoSet] abstraction the upload cycle uploads from. It counts; it does not
     * filter, and it has no disallowed asset in reach to miscount.
     */
    private suspend fun count(
        resources: List<Resource>,
        configPolicy: SelectionPolicy,
        cutoff: CaptureCutoff,
    ): Int {
        val policy = configPolicy.excluding(
            suppressedAssetIds = suppressedLocalIds(),
            albumExcludedAssetIds = albumExcludedAssetIds(cutoff),
        )
        val size = EventPhotoSet(policy) { candidatesFromResources(resources) }.count()
        _size.value = size
        return size
    }
}
