package app.snapsync.feature.upload

import app.snapsync.model.LedgerEntry
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.UploadMechanism
import app.snapsync.model.admittedAssetIds
import app.snapsync.model.bytesBelievedStored
import app.snapsync.ports.Clock
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.JoinedEventMarker
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Upper bound on the read-only listing `LIST`. Shorter than the re-join reconcile's, because nothing
 * waits on this answer and the fetch it bounds is small: uploads are bounded by the membership's
 * capture-date range (at most 30 days), so the largest measured production device holds 321 resources —
 * about 32 KB of JSON — against a median of 20. The reconcile's larger bound was reasoned from a
 * whole-library personal backup, which this product stopped being.
 */
private const val LISTING_TIMEOUT_MS = 15_000L

/** How rarely a foreground may re-ask. See [UploadLedgerAudit.minInterval]. */
private val DEFAULT_MIN_INTERVAL: Duration = 30.minutes

/**
 * The **read-only** check that asks the backend what it holds and compares it against what the ledger
 * believes (capability `event-rejoin-reconciliation`).
 *
 * It exists to establish whether a particular failure happens at all: the ledger records a *belief*, and
 * when that belief is wrong in one direction — the row says the bytes are on the backend and they are
 * not — the photo is invisible to every other member and nothing ever rechecks. That is the failure the
 * project's founding rule singles out ("an event photo that silently fails to upload is invisible and
 * unfixable"), it has a documented cause under the v2 byte route (whose manifest publish writes no
 * resource row, so the repair `database`'s data-loss window promises no longer happens), and a
 * structural one on iOS ≥26.1 (the OS-driven upload job carries no HTTP status, so the device cannot
 * tell a stored `201` from a `502`). What is missing is a **rate**.
 *
 * So this **detects, and never repairs**. Every risk in correcting the ledger — racing the extension's
 * writes, moving the progress screen backwards, demote/re-upload churn, deciding what a corrected row
 * should even say — comes from *writing*. Nothing here writes: no ledger row, no discovery cursor, no
 * `joinedEventId` marker, no upload job. Reverting it is deleting one call.
 *
 * **Where it runs is load-bearing.** The app process, from the foreground trigger flow, beside the
 * download arm's existing `reconcile` — which is exactly this shape for the other half of the product.
 * That placement is what makes it tier-neutral: on iOS ≥26.1 the upload mechanism declines the
 * foreground trigger entirely (the OS owns its scheduling), so a check hung off the upload mechanism
 * would never run on the very tier whose belief is least verifiable. It is also where a complaint lands
 * — a member opens the app when someone tells them their photos are missing.
 *
 * **The comparison set is policy-admitted ∩ believed-landed**, and the policy filter is not tidiness. The
 * nightly sweep collects **unreferenced** bytes (capability `scheduled-cleanup`); a policy-admitted asset
 * is declared by the manifest and therefore referenced, while a departed event's residue and
 * out-of-window rows are not. Without the filter this would report ordinary cleanup as data loss — which
 * is why it depends on the row-level admission [admittedAssetIds] (capability `photo-selection-policy`),
 * the same derivation the device-manifest projection and the cycle's enqueue take.
 *
 * **Two directions, one of them alarming** — see [Findings].
 */
class UploadLedgerAudit(
    private val files: DeviceFilesSource,
    private val ledger: LedgerStore,
    private val marker: JoinedEventMarker,
    /** The device identity; a thunk because it resolves against a protected store on first use. */
    private val deviceId: () -> String,
    /** The membership's one policy derivation, supplied by `compose/` (it reads two ports). */
    private val policy: suspend () -> SelectionPolicy,
    /** The mechanism resolution currently yields — reported, never acted on. */
    private val mechanism: () -> UploadMechanism,
    private val clock: Clock,
    /**
     * The floor on how often a foreground may re-ask.
     *
     * A listing is small but an engaged member foregrounds often, and this answers a question about a
     * *rate* rather than about this instant — so re-asking every few seconds buys nothing and spends the
     * member's cellular data. Only a fetch that actually answered consumes the floor: a failed or
     * timed-out one is no information, and the next foreground retries it.
     */
    private val minInterval: Duration = DEFAULT_MIN_INTERVAL,
    private val log: Logger = Logger.withTag("UploadLedgerAudit"),
) {

    /** The two directions of disagreement, counted separately. See [Findings.missing] and [Findings.unlisted]. */
    class Findings(
        /**
         * Rows the ledger records as landed that the listing does **not** contain — **the** failure: the
         * photo is invisible to every other member and nothing else will repair it.
         */
        val missing: Int,
        /**
         * Listed resources the ledger holds no row for — a ledger-durability signal, not a lost photo.
         * The next walk rediscovers the asset and re-uploads one resource to the same deterministic key,
         * which the backend overwrites: wasted bandwidth, nothing more. Counted, never faulted, so it
         * cannot bury the direction that matters.
         */
        val unlisted: Int,
        /** How many rows were in the comparison set — the denominator [missing] means nothing without. */
        val believed: Int,
        /** How many resources the backend reported holding for this device. */
        val listed: Int,
    )

    private var lastAnswerAt: Long? = null

    /**
     * Compare, and report. Returns the [Findings] when a comparison actually happened, `null` when it was
     * skipped — no event, a pending re-join, the floor, or a fetch that could not answer.
     *
     * Never throws. It runs as one branch of a fan-out the foreground trigger awaits, so a failure here
     * must not take the other branches with it; and it is a detector, whose worst acceptable outcome is
     * telling us nothing.
     */
    suspend fun check(configuredEventId: String?): Findings? =
        runCatching { run(configuredEventId) }.getOrElse { failure ->
            // No information, told out loud (`module-architecture`, "Absence is never silent"). A warning
            // rather than a report: it says something about this process, not about whether the ledger is
            // right, which is the only thing that earns an `Error` here.
            log.w(failure) { "upload-ledger check could not run — nothing compared, nothing reported" }
            null
        }

    private suspend fun run(configuredEventId: String?): Findings? {
        // Both skips are cases where the answer is already known (design D6). No event: nothing to
        // compare. A marker mismatch: the ledger is known-divergent and the marker-gated re-join
        // reconciliation is pending, so a disagreement now is expected rather than informative.
        if (configuredEventId == null) return null
        if (configuredEventId != marker.read()) return null
        if (!due()) return null

        // THE LEDGER IS READ FIRST, and the ordering is the whole race guard. This runs as a sibling of
        // the foreground's upload pump, so on the app-driven tier a cycle may be landing rows underneath
        // it. Reading the ledger at T0 and the listing at T1 > T0 makes the fault direction sound: a row
        // believed landed at T0 had its bytes confirmed before T0, and the storage LIST is
        // read-after-write consistent, so the listing must contain it. The reverse order would report
        // every upload that landed mid-check as a lost photo.
        val rows = ledger.manifestRows()
        val believed = believedLanded(rows, policy())

        val listing = withTimeoutOrNull(LISTING_TIMEOUT_MS) { files.list(deviceId()) }
        if (listing == null) {
            log.w { "device listing timed out — nothing compared; the next foreground retries" }
            return null
        }
        val stored = listing.getOrElse { cause ->
            // Two failures, two severities, exactly as `UploadReconciler` tells them apart: a transport
            // failure heals on the next foreground, a shape failure never does. Neither is a fault of
            // the ledger's, so neither is this check's `Error`.
            if (cause is DeviceListingShapeException) {
                log.e(cause) { "device listing was not understood — the upload-ledger check cannot run on this build" }
            } else {
                log.w(cause) { "device listing fetch failed — nothing compared; the next foreground retries" }
            }
            return null
        }.toSet()

        lastAnswerAt = clock.now().toEpochMilliseconds()
        return report(Findings(
            missing = (believed - stored).size,
            unlisted = countUnlisted(stored, rows),
            believed = believed.size,
            listed = stored.size,
        ))
    }

    /**
     * The comparison set: the keys the membership's **current** policy admits, intersected with the rows
     * the ledger records as landed.
     *
     * [LedgerStore.manifestRows] is the read because it is already exactly "what this device still holds
     * or shares" — it excludes rows marked `absent`, and an absent asset is not declared, therefore not
     * referenced, therefore collectable. Admission is asked over the **whole** manifest read rather than
     * over the landed subset, so this set is a slice of precisely what the manifest declares.
     */
    private suspend fun believedLanded(rows: List<LedgerEntry>, policy: SelectionPolicy): Set<String> {
        val admitted = admittedAssetIds(rows, policy)
        return rows.filter { it.state.bytesBelievedStored && it.assetId in admitted }
            .mapTo(mutableSetOf()) { it.key }
    }

    /**
     * How many listed resources the ledger holds **no row at all** for.
     *
     * The candidates are asked one by one rather than diffed against [LedgerStore.manifestRows], because
     * that read excludes `absent` rows — and an asset that left the library still has its bytes on the
     * backend and still appears in the listing. Diffing would count every deleted photo here, which is
     * the ordinary case, and would drown the signal this direction exists to carry. The candidate set is
     * empty on a healthy device, so the point reads cost nothing in the common case.
     */
    private suspend fun countUnlisted(stored: Set<String>, rows: List<LedgerEntry>): Int {
        val known = rows.mapTo(mutableSetOf()) { it.key }
        return (stored - known).count { ledger.get(it) == null }
    }

    private fun due(): Boolean {
        val last = lastAnswerAt ?: return true
        return clock.now().toEpochMilliseconds() - last >= minInterval.inWholeMilliseconds
    }

    /**
     * Tell what was found — **counts**, never identifiers.
     *
     * That is forced rather than chosen: crash reporting scrubs every UUID-shaped token before send
     * (capability `crash-reporting`), so an asset id or a storage key would arrive redacted anyway. It is
     * also all the question needs, which is why the fault carries the sizes of both sets and the resolved
     * mechanism — the tiers are what the structural hypothesis distinguishes, and Bugsink's own context
     * supplies the OS version.
     *
     * The fault rides at `Error` so it reaches crash reporting as an **event**; the other direction rides
     * lower, as a breadcrumb, because its cost is one idempotent re-upload.
     */
    private fun report(findings: Findings): Findings {
        if (findings.missing > 0) {
            log.e {
                "the backend does not hold ${findings.missing} of ${findings.believed} resource(s) this " +
                    "device's ledger records as landed (listing holds ${findings.listed}; " +
                    "mechanism=${mechanism().diagnosticName}). Nothing was changed — this is a detector."
            }
        }
        if (findings.unlisted > 0) {
            log.i {
                "${findings.unlisted} of ${findings.listed} listed resource(s) have no ledger row; the " +
                    "next walk re-uploads them idempotently — bandwidth, not a lost photo"
            }
        }
        if (findings.missing == 0 && findings.unlisted == 0) {
            log.i { "upload ledger agrees with the backend (${findings.believed} believed-landed row(s))" }
        }
        return findings
    }
}
