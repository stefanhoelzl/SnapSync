package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.services.gallery.Discovery
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.services.gallery.UploadDiscovery
import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the walk memo may do with an entry whose key matches (capability `photo-sharing`, "An unchanged library is
 * answered from the walk memo").
 */
enum class WalkMemoUse {
    /**
     * Never serve: every walk enumerates, exactly as without a memo. A matching entry is only **compared** with
     * the fresh walk, and a disagreement — the token unchanged while the library was not — is logged at `Error`,
     * which reaches crash reporting. That comparison is the field evidence the memo's soundness rests on (the
     * external-change check of `changes/own-work-per-wake`, task 7.4), gathered at the cost of a 2 ms token read.
     */
    SHADOW,

    /** Serve a matching entry in place of a walk. Relied on only once the external-change check is recorded. */
    SERVE,
}

/**
 * The app process's **walk memo**: an [UploadDiscovery] that answers a walk from the last one while the library is
 * unchanged (capability `photo-sharing`, "An unchanged library is answered from the walk memo"; decision record
 * `changes/own-work-per-wake`, D9).
 *
 * An entry is keyed on all three of: the library's change token ([LibraryChangeToken.sameLibraryAs]), the
 * membership's [SelectionPolicy], and the photo grant. The policy stands in for the fetch predicate — it is a
 * **superset** of what the platform's predicate is built from, so it can only walk more often than necessary,
 * never serve a walk made under another cutoff, window or origin exclusion. The rules it adds to the key (the echo
 * and denylisted-album id sets) change only when the library does, which moves the token anyway.
 *
 * **It never upgrades a result that was not authoritative.** Only a walk that completed ([walk] returned), under a
 * full grant ([GalleryAccess.GRANTED], read before the walk), over a readable library ([Discovery.fullEnumeration])
 * is stored. A walk abandoned by a stop throws out of [discover] and stores nothing. A walk under any other grant is
 * passed through untouched, with no token read at all — so a limited or absent grant never meets the memo, and an
 * entry is never served under a grant other than the one it was taken under.
 *
 * **The token is read before the walk.** A change landing while the walk runs leaves the stored token older than
 * the library the walk saw, so the next read differs and the next walk enumerates afresh — never the reverse.
 *
 * **A served answer is the stored [Discovery] itself** — the same candidates and the same
 * [Discovery.fullEnumeration] the walk returned — which is what keeps it authoritative for deletion: it reproduces
 * what a fresh walk over the same unchanged library, under the same policy and grant, returns.
 *
 * In memory only: nothing persists it, and a new process walks afresh. **The upload extension never holds one**
 * (capability `background-upload`: its 32 MB limit, and nothing held across `process()` calls); it is composed in
 * the app process's discovery binding only, by `appUploadDiscovery`.
 */
class WalkMemo(
    private val walk: UploadDiscovery,
    private val changeToken: LibraryChangeTokenRead,
    private val grant: PhotoGrantRead,
    private val use: WalkMemoUse,
    private val log: Logger,
) : UploadDiscovery {

    private class Entry(
        val token: LibraryChangeToken,
        val policy: SelectionPolicy,
        val grant: GalleryAccess,
        val discovery: Discovery,
    ) {
        fun matches(token: LibraryChangeToken, policy: SelectionPolicy, grant: GalleryAccess): Boolean =
            this.grant == grant && this.policy == policy && this.token.sameLibraryAs(token)
    }

    /** Guards [entry] only — never a walk, so two callers never wait on each other's enumeration. */
    private val lock = Mutex()
    private var entry: Entry? = null

    override suspend fun discover(policy: SelectionPolicy): Discovery {
        val grantNow = grant.current()
        if (grantNow != GalleryAccess.GRANTED) return walk.discover(policy)
        val token = changeToken.changeToken() ?: return walk.discover(policy)
        val held = lock.withLock { entry }?.takeIf { it.matches(token, policy, grantNow) }
        if (held != null && use == WalkMemoUse.SERVE) {
            log.i { "walk memo: library unchanged, answered ${held.discovery.candidates.size} candidate(s) without a walk" }
            return held.discovery
        }
        val fresh = walk.discover(policy)
        if (held != null) compare(held.discovery, fresh)
        if (fresh.fullEnumeration) lock.withLock { entry = Entry(token, policy, grantNow, fresh) }
        return fresh
    }

    /** The resolve is id-scoped, never a walk: nothing to memoise. */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> = walk.resourcesFor(keys)

    /**
     * [WalkMemoUse.SHADOW]'s evidence: what the memo would have served against what the walk returned. Reported as
     * counts, so the line is the same size however large the library is.
     */
    private fun compare(memo: Discovery, fresh: Discovery) {
        val memoIds = memo.candidates.mapTo(HashSet()) { it.facts.assetId }
        val freshIds = fresh.candidates.mapTo(HashSet()) { it.facts.assetId }
        if (memoIds == freshIds && memo.fullEnumeration == fresh.fullEnumeration) {
            log.i { "walk memo (shadow): library unchanged and the walk agreed (${freshIds.size} candidate(s))" }
        } else {
            log.e {
                "walk memo (shadow): the change token did not move, but the walk differs — " +
                    "${(freshIds - memoIds).size} added, ${(memoIds - freshIds).size} gone, " +
                    "fullEnumeration ${memo.fullEnumeration}→${fresh.fullEnumeration}; serving would have been wrong"
            }
        }
    }
}
