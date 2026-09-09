package app.snapsync.feature.upload

/**
 * What the upload arm contributes to a **foreground** entry (capability `upload-lifecycle`,
 * `upload-state-reconciliation`) — the two effects `compose/` builds and `flow/Foreground` fans out.
 *
 * They travel together because the interesting fact about them is the asymmetry between them, and two
 * bare lambdas side by side say nothing about it:
 *
 * - [pump] is delivered to whichever mechanism resolution yields, and that mechanism may **decline**. On
 *   iOS ≥26.1 under a full grant it always does — the OS owns the scheduling — so on that tier a
 *   foreground does nothing at all for uploads.
 * - [check] runs in the **app** process on **both** tiers, because it belongs to no mechanism. It is the
 *   read-only comparison of what the ledger believes landed against what the backend holds, and the tier
 *   where [pump] declines is precisely the tier whose upload jobs carry no HTTP status — so a check that
 *   inherited [pump]'s decline would miss the case it exists to measure.
 *
 * Both are `suspend`, and neither returns anything: the flow awaits them and reads nothing back (law "A
 * trigger flow never outlives its own run"). What [check] finds reaches crash reporting, not this flow.
 */
class UploadForeground(
    /** Pump the app-driven tier's upload cycle; a no-op wherever the resolved mechanism declines. */
    val pump: suspend () -> Unit,
    /** The read-only backend check ([UploadLedgerAudit]); it writes nothing and reports through the log. */
    val check: suspend () -> Unit,
)
