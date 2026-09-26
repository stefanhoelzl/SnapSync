package app.snapsync.gallery

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.distantPast
import platform.Foundation.NSPredicate
import platform.Foundation.dateByAddingTimeInterval

/**
 * Translate a [SelectionPolicy] into a `PHFetchOptions` predicate, or `null` for no narrowing.
 *
 * The `when` is **exhaustive over the sealed rule set on purpose**: adding a rule fails to compile here
 * until someone states whether PhotoKit can express it. Before this, the predicate hardcoded a mask and a
 * cutoff, so a new rule simply never narrowed and nobody found out.
 *
 * Narrowing is an **optimization only** (capability `photo-sharing`): the caller's in-memory
 * admission runs over whatever comes back, so this may return a superset of the admitted set but never a
 * subset. Where the predicate could disagree with the authoritative decision at a boundary it is
 * **widened**, never narrowed.
 *
 * **What this returns is also the walk's presence set**, and a clause here is therefore not free even when
 * it agrees with the admission. The upload cycle deletes the in-window ledger rows of every asset an
 * authoritative walk did not return (capability `photo-sharing`, "Deletion is a presence diff over an
 * authoritative walk"), judging "in-window" by the rows' own admission, which knows only capture dates and
 * id sets. So a new clause that excludes an asset still in the library — a subtype, a flag — makes the rows
 * such an asset **already** holds look departed, and they are deleted. The two subtype clauses below are
 * safe only because nothing they exclude can hold a row: the admission rejected it before any row was
 * written. A clause that could exclude an asset after its row exists belongs in the admission, not here.
 *
 * **Three device-verified constraints on PhotoKit's predicate parser** (SE2, iOS 26.5.2; measured facts,
 * not preferences — re-verify on a device before adding any key):
 *
 * 1. A subtype exclusion MUST be written `NOT ((mediaSubtypes & N) != 0)`. The natural
 *    `(mediaSubtypes & N) == 0` form returns **zero rows** — silently, without raising. Shipping it would
 *    starve the walk of every asset. (The *singular* `mediaSubtype` key likewise returns zero rows without
 *    raising, so a one-character typo empties the library.)
 * 2. Predicate **arithmetic** raises an uncatchable `NSException` and aborts the process, so
 *    `pixelWidth * pixelHeight` is impossible — the area floors cannot ride along.
 * 3. `hasAdjustments` is not a supported key and likewise aborts the process.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun predicateFor(policy: SelectionPolicy): NSPredicate? {
    val clauses = mutableListOf<String>()
    val args = mutableListOf<Any>()

    for (rule in policy.rules) when (rule) {
        // A membership that contributes nothing, narrowed to nothing. Correctness does not depend on this
        // — the caller's admission refuses every asset regardless — but without it a non-contributing
        // membership pays a whole-library walk on every cold start to reach the empty set its own
        // configuration already stated.
        //
        // Built from a comparison that is never satisfiable on a key PhotoKit is KNOWN to evaluate (the
        // same `creationDate` comparison the bounds below use), deliberately NOT from the `(mediaSubtypes
        // & N) == 0` form documented above as returning zero rows. That form is an artefact of the
        // predicate parser, not a contract: were Apple ever to evaluate it correctly, `DenyAll` would
        // begin admitting the WHOLE LIBRARY — the worst possible direction for a membership that shares
        // nothing. An unsatisfiable comparison cannot fail that way.
        SelectionRule.DenyAll -> {
            clauses += "creationDate < %@"
            args += NSDate.distantPast
        }
        // The one REQUIRED narrowing: without a lower bound the walk is unbounded and the process is
        // watchdog-killed before the authoritative admission ever runs. Widened by a day — see [widened].
        is SelectionRule.CaptureAfter -> parseBound(rule.cutoff.at.iso)?.let {
            clauses += "creationDate >= %@"
            args += it.dateByAddingTimeInterval(-PREDICATE_WIDEN_SECONDS)
        }
        is SelectionRule.CaptureBefore -> parseBound(rule.ceiling.at.iso)?.let {
            clauses += "creationDate <= %@"
            args += it.dateByAddingTimeInterval(PREDICATE_WIDEN_SECONDS)
        }
        SelectionRule.ExcludeScreenshots ->
            clauses += "NOT ((mediaSubtypes & $SUBTYPE_SCREENSHOT) != 0)" // NB the NOT-form; see (1)
        SelectionRule.ExcludeScreenRecordings ->
            clauses += "NOT ((mediaSubtypes & $SUBTYPE_SCREEN_RECORDING) != 0)"
        // Not expressible: the area comparison needs arithmetic, which aborts the process — see (2). A
        // bounding-box approximation could ride along but is deliberately omitted: it could only ever
        // narrow, and the floors exist to be conservative.
        is SelectionRule.MinImageArea, is SelectionRule.MinVideoArea -> Unit
        // Not expressible: these are id sets resolved from the download store and the album map, not
        // properties of the asset. Both are small and applied in memory.
        is SelectionRule.NotEcho, is SelectionRule.NotInDenylistedAlbum -> Unit
    }

    if (clauses.isEmpty()) return null
    return NSPredicate.predicateWithFormat(clauses.joinToString(" AND "), argumentArray = args)
}

/**
 * Parse a canonical cutoff into an `NSDate`, tolerating **fractional seconds**.
 *
 * A bare `NSISO8601DateFormatter` uses `.withInternetDateTime`, which does not accept a `.sss` fraction and
 * returns `nil` for `2026-07-09T19:24:17.182Z`. Bounds are supposed to be second precision (capability
 * `photo-sharing`) and the join gate normalizes them — but one persisted by an older build carries
 * the backend's raw `toISOString()` milliseconds. Losing the predicate there would silently restore the
 * whole-library fetch that trips the watchdog, so parse both shapes rather than trust the invariant.
 */
private fun parseBound(iso: String): NSDate? = Iso8601.parseTolerant(iso)

/**
 * One day of slack on each date bound. The authoritative compare is a *lexicographic* string compare in
 * `commonMain`; this predicate is an `NSDate` comparison. Where the two could disagree at a boundary
 * (fractional seconds, formatter rounding) the asymmetry matters: over-returning costs a few extra
 * round-trips and the admission drops them, while under-returning silently loses a photo nothing can add
 * back.
 */
private const val PREDICATE_WIDEN_SECONDS = 24.0 * 60.0 * 60.0
