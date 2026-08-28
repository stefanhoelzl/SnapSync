package app.snapsync.feature.status

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The **foreground-gated ledger-counts poll** (capability `sync-status`; migration step 12): while
 * the app is foregrounded — and only then — re-read the ledger's `aggregates()` on a fixed cadence
 * so upload status moves live as the extension records completions in its own process. This
 * replaces the extension's cross-process Darwin liveness ding (and the app-side
 * `CFNotificationCenter` observer, `staticCFunction` bridge, and per-foreground
 * register/unregister choreography that came with it): the poll needs no cross-process channel, no
 * platform observer lifecycle, and cannot miss a signal — the read *is* the truth.
 *
 * The **cadence is this feature's rule** — it is the staleness bound the `sync-status` spec
 * declares (a foregrounded screen shows counts at most [DEFAULT_CADENCE] stale) — while the
 * *ordering* (start on foreground entry, stop on backgrounding) is the Foreground/Background
 * flows' coordination. Each tick is one local, read-only SQLite aggregate over the App-Group
 * ledger (no network, no storage LIST — the same read the ding handler performed); a failed read
 * retains the last good counts ([ReadingLedgerCountsSource]'s posture), so a transient error never
 * regresses the screen.
 *
 * [start] is idempotent while a poll is live (repeated foreground entries never stack pollers —
 * the same property the old observer's defensive re-register held); [stop] cancels the loop. The
 * first tick waits one full cadence: foreground entry already refreshes the status sources through
 * the Foreground flow, so an immediate read would be a duplicate.
 *
 * **Containment contract:** a throwing [LedgerCountsSource.refresh] must not kill the loop — each
 * tick is caught, so the poll keeps its cadence and the next tick retries. The seam's own
 * implementations already swallow read failures (keep-last-good), but the loop does not rely on
 * that: a poll that dies silently on the first bad tick would freeze the screen for the rest of
 * the foreground session, which is exactly the invisible failure the poll exists to prevent.
 */
class LedgerCountsPoller(
    private val scope: CoroutineScope,
    private val counts: LedgerCountsSource,
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
                    counts.refresh()
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
         * The staleness bound the spec declares: while foregrounded, a ledger change reaches the
         * status projection within one cadence. 2 s keeps the joined screen visibly live during an
         * upload burst while costing one cheap local read per tick — and nothing at all while
         * backgrounded, where the ding's observer was already deaf by design.
         */
        val DEFAULT_CADENCE: Duration = 2.seconds
    }
}
