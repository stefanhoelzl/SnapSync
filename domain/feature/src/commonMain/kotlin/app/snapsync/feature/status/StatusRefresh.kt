package app.snapsync.feature.status

import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.sharing.Contribution
import app.snapsync.domain.membership.Membership
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.downloads.RecognitionSource
import app.snapsync.services.membership.MembershipProjection
import co.touchlab.kermit.Logger

/**
 * Re-read every status the joined screen shows, **cheap local reads before the library enumeration**
 * — the foreground status refresh is not sequenced behind the upload pump.
 *
 * The order is the rule, and it has a shipped regression behind it. The ledger `aggregates()` and the
 * download projection are two SQLite reads; the own-device walk is orders of magnitude slower (~6 s).
 * Doing the walk first published a counted **total** beside counts nobody had read yet, so a device that
 * had shared everything rendered "Syncing…" with an upload arrow. The spec states it as a SHALL — *"the
 * cheap local sources … **before** the library enumeration, so the counted total and the counted
 * completed arrive together rather than the total arriving alone and the screen briefly reporting
 * `0 of N`"* — and the scenario *The cheap reads precede the enumeration* is the assertion.
 *
 * **A rule in a feature, not order in an entry point, and that is the sanctioned reading of the law rather
 * than an exception to it.** `docs/architecture.md`'s "Rules in features, order in entry points" offers both
 * remedies for a sequence found outside an entry point: restore it to the entry point's steps, *"or the rule is
 * named and kept in the feature as a rule."* This is the second, for **three** callers — the foreground's work, a
 * join's (`AfterJoin`) and a settings change's (`AfterSettings`); it moves into the layers with the status (4.7).
 *
 * It was previously an opaque `refreshStatus = { refreshStatusSources() }` lambda built in `compose/`, which made
 * the foreground's claim to decide nothing true of its own order and false of what it called.
 *
 * **Two features, one rule, and the sibling reached through a lambda.** The counts and the total are
 * `feature/status`; the "downloaded X of Y" line is `feature/download`. Features are mutually blind, so
 * [refreshDownloadLine] arrives as an injected effect the composition builds: passing the call rather than the
 * collaborator is what keeps one feature blind to its sibling.
 */
class StatusRefresh(
    /** Own-device completeness and in-flight, from one consistent ledger read. */
    private val ledgerCounts: LedgerCountsSource,
    /** The own-device upload total `N`. */
    private val gallery: OwnDeviceGalleryStatusSource,
    /** The foreign-download line — a SIBLING feature, so a lambda. */
    private val refreshDownloadLine: suspend () -> Unit,
    /** The joined membership, or `null` when unjoined. */
    private val configSource: MembershipProjection,
    /**
     * What this membership contributes — the ONE derivation (`OwnPhotos.contribution`), run
     * where the membership and the port readers are in scope. Injected because deriving it reads ports
     * (echo suppression, the grant, the denylisted-album lookup) and this zone may not make them.
     */
    private val contributionFor: suspend (Membership) -> Contribution,
    /**
     * Whether the membership's received photos have been recognised: until they have,
     * any of them could count as the member's own, so `N` is not counted — it keeps what it last honestly held, and an
     * uncounted `N` keeps the screen on its neutral line. The same record the upload gate reads.
     */
    private val recognition: RecognitionSource,
    private val log: Logger = Logger.withTag("status"),
) {

    /**
     * The **cheap local status reads** — one bounded group, defined ONCE.
     *
     * It is its own entry point because the group has two callers with different jobs: [run] reads it before
     * the library enumeration, and the foreground-gated poll re-reads it on a cadence. Stating the membership
     * in both places is how the poll came to cover only one of the two arms — which left the download line
     * unbounded and the screen able to claim "Up to date" over an outstanding burst for a whole session.
     *
     * Ordering inside the group is not significant; ordering **against the enumeration** is, and that is
     * [run]'s.
     */
    suspend fun refreshCheapLocalReads() {
        ledgerCounts.refresh()
        refreshDownloadLine()
    }

    /**
     * **No membership → nothing to count**, and `N` stays `null`, *not counted*. A download-only
     * membership still counts, and reaches `0` through the ordinary path: its policy is `DenyAll`, so
     * the admission answers a **counted** zero rather than this method guarding the walk.
     */
    suspend fun run() {
        // CHEAP LOCAL READS FIRST — the rule this class is named for. Both counts gate the screen out of
        // its neutral first frame; the walk below is the slow one, and it must not arrive alone.
        refreshCheapLocalReads()
        refreshTotal()
    }

    /**
     * The library half alone — recount `N` under the membership's policy: what a selection change runs, whose
     * snapshot the read seam then answers from. Nothing while unjoined; a failed policy read leaves `N` as it was.
     */
    suspend fun refreshTotal() {
        val membership = when (val read = configSource.membership) {
            is MembershipRead.Joined -> read.membership
            else -> return
        }
        // Before recognition the admitted set would include received photos nothing has recognised yet — a count the
        // upload gate (which withholds until then) would never match.
        if (!recognition.isRecognised(membership.event.id)) {
            return log.i { "status: received photos not recognised yet — N not counted" }
        }
        // NO GRANT CHECK. The read seam answers both halves — where candidates come from AND whether an
        // admitted set can be stated at all — and a gate here would restate the second half. It used to,
        // and restated it wrongly: `grantsPhotoAccess` is true under LIMITED, so it admitted the one case
        // that actually reaches members — a partial grant whose selection snapshot has not landed,
        // counted as a zero and settling the screen at "Up to date".
        val derived = runCatchingCancellable { contributionFor(membership) }
        derived.exceptionOrNull()?.let { failure ->
            // Cancellation is not a failed read: `runCatchingCancellable` rethrows it, so it never lands
            // here — swallowing it would break structured concurrency AND post an Error-severity line, which
            // reaches the crash reporter on production builds, for an
            // ordinary teardown.
            // Bounded, not thrown: this runs as one child of the foreground's concurrent steps, so
            // an escaping failure would cancel its SIBLINGS — the download reconcile, the staged-byte
            // reclaim, the membership refresh — none of which have anything to do with a policy read.
            // The spec requires exactly that: *"A failure in any one refresh SHALL NOT cancel its
            // siblings."* The consequence is named rather than hidden: `N` is not refreshed, so the
            // total keeps whatever it last honestly held.
            log.e(failure) { "status: policy read failed — N not refreshed" }
            return
        }
        // What the walk itself does on failure is the source's own invariant — it leaves `N` untouched
        // and logs at Error severity — so there is nothing left to catch on its behalf.
        gallery.refresh(derived.getOrThrow())
    }
}
