package app.snapsync.feature.status

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The **foreground-gated status-counts poll** (capability `sync-status`): while the app is foregrounded — and
 * only then — re-read the **cheap local status reads** on a fixed cadence, so the joined screen's counts move
 * live rather than standing at whatever the last trigger happened to read.
 *
 * **It ticks the GROUP, not one arm, and that is the point.** This used to re-read the ledger alone, which
 * left the download projection refreshed once per foreground entry and never again — so a burst of foreign
 * photos discovered after that read rendered a checkmarked "In sync" for the rest of the session. The screen's
 * direction arrows are conjunctive, so a composite is only as fresh as its stalest member: bounding one arm
 * does not half-bound the screen, it leaves the screen unbounded through the other.
 *
 * It replaces the extension's cross-process Darwin liveness ding (and the app-side `CFNotificationCenter`
 * observer, `staticCFunction` bridge, and per-foreground register/unregister choreography that came with it):
 * the poll needs no cross-process channel, no platform observer lifecycle, and cannot miss a signal — the read
 * *is* the truth.
 *
 * **The cadence is this feature's rule** — the staleness bound the `sync-status` spec declares. What it bounds
 * is *how long the screen may assert something false*, not the smoothness of a counter, and [DEFAULT_CADENCE]
 * is **chosen for human perception rather than derived**: no measurement produces it and none is claimed. It
 * is also conditional on the foreground — while backgrounded the projection may be arbitrarily stale, which is
 * harmless because nothing renders it. The *ordering* (start on foreground entry, stop on backgrounding) is
 * the Foreground/Background flows' coordination.
 *
 * Each tick is **local and read-only** — no network, no storage LIST, and never the own-device library
 * enumeration, which is orders of magnitude slower and belongs only to the foreground refresh. Stated by class
 * rather than by counting reads, so a further cheap source joining the group does not falsify it. A failed
 * read retains each member's last good value, so a transient error never regresses the screen.
 *
 * [start] is idempotent while a poll is live (repeated foreground entries never stack pollers — the same
 * property the old observer's defensive re-register held); [stop] cancels the loop.
 *
 * **The first tick waits one full cadence, and NOT because it would be a duplicate.** Foreground entry does
 * refresh the status sources — but that refresh is a concurrent child of the same flow as the download
 * reconcile, so it typically reads the download projection *before* discovery has planned anything. The first
 * tick is what **repairs** that entry read, and one cadence is comfortably longer than a union fetch; a slower
 * fetch is caught by the tick after it. An immediate first tick would not help: it would fire before the fetch
 * returned and read the same stale value.
 *
 * **Containment contract:** a throwing tick must not kill the loop — each is caught, so the poll keeps its
 * cadence and the next tick retries. The sources' own implementations already swallow read failures
 * (keep-last-good), but the loop does not rely on that: a poll that dies silently on the first bad tick would
 * freeze the screen for the rest of the foreground session, which is exactly the invisible failure the poll
 * exists to prevent.
 */
class StatusCountsPoller(
    private val scope: CoroutineScope,
    /**
     * The group, as one call — `StatusRefresh.refreshCheapLocalReads`, injected rather than referenced so the
     * membership of the group has exactly one definition. Taking the ledger source directly is what let the
     * poll and the refresh disagree about what the group contains.
     */
    private val refreshCheapLocalReads: suspend () -> Unit,
    private val cadence: Duration = DEFAULT_CADENCE,
) {

    private var job: Job? = null

    /** Begin polling; a no-op while a previous [start]'s poll is still live. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                delay(cadence)
                // Containment: a throwing refresh must not kill the loop (see the class KDoc).
                // Cancellation is rethrown so [stop] still lands even mid-refresh.
                try {
                    refreshCheapLocalReads()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Swallowed by contract; the next tick retries at cadence.
                }
            }
        }
    }

    /** Stop polling (backgrounding — a suspended app cannot act on fresher counts). Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * The staleness bound the spec declares: while foregrounded, a change to **either** member of the
         * group reaches the status projection within one cadence plus one read.
         *
         * **Chosen, not derived.** What it bounds is how long the screen may assert something false, and no
         * measurement produces 2 s — it is the scale at which a member reading a status line would notice.
         * Deliberately no expiry trigger: nothing would tell us this value is wrong, and naming a trigger
         * nobody can act on is worse than admitting there is none. It costs the group's cheap local reads
         * per tick while foregrounded, and nothing at all while backgrounded.
         */
        val DEFAULT_CADENCE: Duration = 2.seconds
    }
}
