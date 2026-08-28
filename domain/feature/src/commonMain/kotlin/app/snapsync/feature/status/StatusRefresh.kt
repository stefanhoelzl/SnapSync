package app.snapsync.feature.status

import app.snapsync.model.EventConfig
import app.snapsync.model.SelectionPolicy
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

/**
 * Re-read every status the joined screen shows, **cheap local reads before the library enumeration**
 * (capability `sync-status`, "Foreground status refresh is not sequenced behind the upload pump").
 *
 * The order is the rule, and it has a shipped regression behind it. The ledger `aggregates()` and the
 * download projection are two SQLite reads; the own-device walk is orders of magnitude slower (~6 s).
 * Doing the walk first published a counted **total** beside counts nobody had read yet, so a device that
 * had shared everything rendered "Syncing…" with an upload arrow. The spec states it as a SHALL — *"the
 * cheap local sources … **before** the library enumeration, so the counted total and the counted
 * completed arrive together rather than the total arriving alone and the screen briefly reporting
 * `0 of N`"* — and the scenario *The cheap reads precede the enumeration* is the assertion.
 *
 * **A rule in a feature, not order in a flow, and that is the sanctioned reading of the law rather than
 * an exception to it.** `module-architecture`'s "Rules in features, order in flows" offers both remedies
 * for a sequence found outside a flow: restore the branch to the flow, *"or the rule is named and kept
 * in the feature as a rule."* This is the second, because there are **three** callers and only two are
 * flows — `Foreground` and `Provision` coordinate it, and `ReconfigureEvent` is a `feature/membership`
 * use-case that cannot hold a flow's ordering at all. Putting the order in `Foreground.run()` would have
 * stated it twice: once transcribed into the flow, once rebuilt in `compose/` for the other two. A rule
 * with three callers is a rule.
 *
 * It was previously an opaque `refreshStatus = { refreshStatusSources() }` lambda built in `compose/`,
 * which made `Foreground`'s own KDoc — *"This flow coordinates (ordering + fan-out); it decides
 * nothing"* — true of the flow and false of what it called.
 *
 * **Two features, one rule, and the sibling reached through a lambda.** The counts and the total are
 * `feature/status`; the "downloaded X of Y" line is `feature/download`. Features are mutually blind, so
 * [refreshDownloadLine] arrives as an injected effect the composition builds — the shape
 * `ResetDeviceState` already uses for `resetDownloads`, and for the same stated reason: passing the call
 * rather than the collaborator is what keeps one feature blind to its sibling.
 */
class StatusRefresh(
    /** Own-device completeness and in-flight, from one consistent ledger read. */
    private val ledgerCounts: LedgerCountsSource,
    /** The own-device upload total `N`. */
    private val gallery: OwnDeviceGalleryStatusSource,
    /** The foreign-download line (capability `photo-download`) — a SIBLING feature, so a lambda. */
    private val refreshDownloadLine: suspend () -> Unit,
    /** The joined membership, or `null` when unjoined — the config read, injected (a port touch). */
    private val activeConfig: () -> EventConfig?,
    /**
     * What this membership contributes (capability `photo-selection-policy`) — the ONE derivation, run
     * where the config and both port readers are in scope. Injected because deriving it costs two port
     * reads (echo suppression, the denylisted-album lookup) and this zone may not make them.
     */
    private val policyFor: suspend (EventConfig) -> SelectionPolicy,
    private val log: Logger = Logger.withTag("status"),
) {

    /**
     * **No membership → nothing to count**, and `N` stays `null`, *not counted*. A download-only
     * membership still counts, and reaches `0` through the ordinary path: its policy is `DenyAll`, so
     * the admission answers a **counted** zero rather than this method guarding the walk.
     */
    suspend fun run() {
        // CHEAP LOCAL READS FIRST — the rule this class is named for. Both counts gate the screen out of
        // its neutral first frame; the walk below is the slow one, and it must not arrive alone.
        ledgerCounts.refresh()
        refreshDownloadLine()
        val config = activeConfig() ?: return
        // NO GRANT CHECK. The read seam answers both halves — where candidates come from AND whether an
        // admitted set can be stated at all — and a gate here would restate the second half. It used to,
        // and restated it wrongly: `grantsPhotoAccess` is true under LIMITED, so it admitted the one case
        // that actually reaches members — a partial grant whose selection snapshot has not landed,
        // counted as a zero and settling the screen at "In sync" (capability `gallery-status`).
        val derived = runCatching { policyFor(config) }
        derived.exceptionOrNull()?.let { failure ->
            // Cancellation is not a failed read. `runCatching` catches it like anything else, and
            // swallowing it would break structured concurrency AND post an Error-severity line — which
            // reaches the crash reporter on production builds (capability `crash-reporting`) — for an
            // ordinary teardown. [OwnDeviceGalleryStatusSource] and [LedgerCountsPoller] separate the two
            // for the same reason; this call site did not, which is the last place in this sequence that
            // still conflated them.
            if (failure is CancellationException) throw failure
            // Bounded, not thrown: this runs as one child of the Foreground flow's `coroutineScope`, so
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
