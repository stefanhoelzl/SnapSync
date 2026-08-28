package app.snapsync.feature.status

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.EventPhotoSet
import app.snapsync.ports.GalleryStatusSource
import app.snapsync.ports.CandidateSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
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
 * **Not counted is not zero.** [size] is `null` until a [refresh] has produced a count, and the two
 * states must never be collapsed: the status projection settles to "In sync" once the synced count
 * reaches the total, so a placeholder `0` standing in for an unread count renders a checkmark reading
 * "everything shared" on a device that has counted nothing. This source used to seed `0`, and that is
 * what members reported as a status going backwards across launches — a settled frame that was never
 * true, followed by the first honest one (`SNAPSYNC-14`, `SNAPSYNC-16`; capability `gallery-status`).
 *
 * A **counted** zero is a real answer and still settles the screen: a non-contributing membership
 * carries `DenyAll`, admits nothing, and publishes `0` through the ordinary path below.
 *
 * The policy is a [refresh] **parameter**, not an injected supplier: it belongs to a membership, and a
 * device with no membership has no scope to count — so the composition root simply does not refresh, and
 * `N` stays `null`, *not counted*. There is deliberately no "no policy" value to pass.
 */
class OwnDeviceGalleryStatusSource(
    private val source: CandidateSource,
    // The echo-suppression and denylisted-album readers used to sit here, so this source could complete a
    // config-derived policy itself. They are gone with the two-phase construction: the one derivation runs
    // in the shared composition, and `refresh` receives a finished policy (capability
    // `photo-selection-policy`). Their `{ emptySet() }` defaults are gone with them — a default that
    // silently admits a member's WhatsApp album is exactly what the required-ports rule exists to prevent.
    private val log: Logger = Logger.withTag("gallery"),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : GalleryStatusSource {

    // `null` until a count has been taken. NOT `0`: a placeholder zero is indistinguishable from a
    // membership that genuinely contributes nothing, and the status projection settles to "In sync" the
    // moment the synced count reaches the total — so a seeded `0` renders a checkmark on a device that
    // has counted nothing (capability `gallery-status`; reported as `SNAPSYNC-14` / `SNAPSYNC-16`).
    private val _size = MutableStateFlow<Int?>(null)

    /** The upload total `N`: the count of this device's OWN admitted assets, or `null` if not counted. */
    override val size: StateFlow<Int?> = _size.asStateFlow()

    /**
     * Re-read within [configPolicy] (what the joined membership contributes) and recompute `N`.
     *
     * **One entry point, both grants.** There used to be a second — `refreshFrom(resources, policy)` — for
     * the `LIMITED` snapshot, which meant the caller decided which mode was in play. That restated the
     * mode difference the source already owns, and it is the restatement rather than the reading that lets
     * two paths drift apart (capability `limited-photo-access`). The permission-aware source now answers
     * "where do candidates come from"; this asks only for the count.
     *
     * **The direction gate, for the total** (capability `photo-selection-policy`). A non-contributing
     * membership reports `0` **without enumerating** — the short-circuit must live here, because `N` is a
     * *parallel* computation that no upload gate feeds (unlike the download arm, whose total flows through
     * its gate and is zero for free). A walk costs one synchronous platform round-trip per in-scope asset,
     * so a 4000-photo library would spend minutes of XPC to reach the empty set the direction already
     * told us.
     *
     * The cost and shape are **logged** (capability `diagnostic-logging`). Without a line here, whether the
     * capture bound is actually bounding anything is invisible on a real device: a bounded and an
     * unbounded fetch differ only in how many assets they touch.
     *
     * **A failed enumeration does not propagate.** It leaves [size] untouched and logs at Error severity;
     * only cancellation is rethrown. This belongs here rather than at a call site because it is an
     * invariant about *this source's own state* — the same shape as [ReadingLedgerCountsSource], which
     * retains its last good counts on a failed read for the same reason. A caller still contains whatever
     * IT does around the call (deriving the policy costs two port reads); it no longer has to remember to
     * protect a rule it does not own.
     */
    suspend fun refresh(policy: SelectionPolicy) {
        // The policy arrives COMPLETE (capability `photo-selection-policy`): there is one derivation, and
        // it runs where the config and the two port readers are both in scope — the shared composition.
        // This source therefore receives a decision and never the material to re-decide, and there is no
        // half-built policy for it to finish. A non-contributing membership carries `DenyAll`, so the
        // count below reaches 0 through the same admission as every other answer, and the platform fetch
        // returns nothing rather than the source guarding the walk itself.
        val started = timeSource.markNow()
        // `count()` reads facts only — no per-asset resource round-trip is issued for a number
        // (capability `photo-selection-policy`, *Admission is decidable on asset facts alone*).
        val counted = runCatching { EventPhotoSet(policy, source::candidates).count() }
        counted.exceptionOrNull()?.let { failure ->
            // Cancellation is not a failed walk. `runCatching` catches it like anything else, and
            // swallowing it would break structured concurrency AND post an Error-severity line — which
            // reaches the crash reporter on production builds (capability `crash-reporting`) — for an
            // ordinary teardown. `LedgerCountsPoller` separates the two for the same reason.
            if (failure is CancellationException) throw failure
            // **The invariant is this source's, so the containment is too.** A walk that blew up must
            // leave `size` exactly as it was — the previous good count, or the un-counted seed if there
            // was none — because "could not count" settling the screen as "counted nothing" is the
            // regression this class exists to prevent (`SNAPSYNC-14`, `SNAPSYNC-16`). It used to be the
            // caller that wrapped this, which made an invariant about this source's own state depend on
            // every caller remembering to protect it.
            //
            // Error severity: the log line is the ONLY channel that distinguishes "could not count" from
            // "not counted yet", since both render the same neutral status line.
            log.e(failure) { "gallery: enumeration failed — N stays ${_size.value ?: "un-counted"}" }
            return
        }
        val size = counted.getOrThrow()
        _size.value = size
        val elapsed = started.elapsedNow()
        log.i { "gallery: N=$size own admitted asset(s) in ${elapsed.inWholeMilliseconds}ms" }
    }
}
