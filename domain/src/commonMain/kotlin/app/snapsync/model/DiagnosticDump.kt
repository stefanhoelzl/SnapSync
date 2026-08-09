package app.snapsync.model

/**
 * The **total** bytes of device log one diagnostic dump may carry (capability `diagnostic-logging`).
 *
 * It is a hard bound, not a target. The reporting channel's server rejects an event over
 * `MAX_EVENT_SIZE` = 1 MiB (`1_048_576`) with a `413`, and the reporting SDK swallows transport
 * errors — so an over-budget dump completes, tells the user nothing, and never arrives. The only
 * defence is to stay clear of the ceiling.
 *
 * Measured against the real instance (2026-07-29): two 340 KB log sections plus the small state and
 * ledger sections serialised to 686,923 B — JSON overhead on log text is ~1% — and came back
 * byte-identical, while 2 × 560 KB was rejected at 1,130,997 B. At this budget an assembled dump
 * lands near 760 KB once the SDK adds its own contexts, release, and up to 100 breadcrumbs, leaving
 * ~280 KB of headroom.
 *
 * Raising this means raising `MAX_EVENT_SIZE` on the reporting server first — which the hosted plan
 * does not allow. The two move together or not at all.
 */
const val DIAGNOSTIC_LOG_BUDGET_BYTES: Int = 700_000

/**
 * One operator-initiated diagnostic dump (capability `diagnostic-logging`): five labelled sections,
 * ready for a reporter to transmit as a single event.
 *
 * Deliberately plain strings and string maps: the transport renders them as structured sections, and
 * a value type that knew about the transport would put the reporting SDK's vocabulary in `model/`.
 *
 * Identifiers ride **verbatim** — a dump is a deliberate, confirmed act whose value is precisely the
 * event, asset and device ids a scrub would destroy (capability `crash-reporting` carves this out;
 * automatic events stay redacted). That now covers the [note] and the message built from it: a report
 * reading "stuck on event ‹uuid›" has lost the one fact it carried.
 */
class DiagnosticDump(
    /**
     * What the operator wrote: what went wrong, and what they were doing. Already trimmed and
     * length-bounded by the sheet that collected it, so it arrives here ready to send.
     *
     * It is the one section a log tail can never supply, and the transport titles the report with it
     * — so two reports about different problems read as different problems.
     */
    val note: String,
    /** Build, OS, device, membership, tier, permission, backend — the facts a log tail may not hold. */
    val state: Map<String, String>,
    /** Upload/download counts. Five integers; no row lists (see `CollectDiagnosticDump`). */
    val ledger: Map<String, String>,
    /** The app process's log tail, line-aligned. Empty when unreadable. */
    val appLog: String,
    /** The extension process's log tail, line-aligned. Empty when the extension has never run. */
    val extensionLog: String,
) {
    /**
     * Bytes of **log** carried — what [DIAGNOSTIC_LOG_BUDGET_BYTES] bounds.
     *
     * The [note] is deliberately excluded. It is bounded to a couple of hundred bytes against ~280 KB
     * of measured headroom, so subtracting it would buy nothing and would couple a UI field's cap to
     * a measured transport constant.
     */
    val logBytes: Int
        get() = appLog.encodeToByteArray().size + extensionLog.encodeToByteArray().size
}

/**
 * The platform facts the shell knows and `:domain` cannot read for itself — supplied as a transcribed
 * value rather than a port, because every field is a constant of the running build.
 *
 * [uploadBase] is the same baked host the boot banner names, and [reporterEnvironment] distinguishes
 * a deliberately DSN-injected dev build from a production one — without it, a dump from the
 * on-device verification path is indistinguishable from a real user's.
 */
class DiagnosticEnvironment(
    val appVersion: String,
    val buildNumber: String,
    val osVersion: String,
    val deviceModel: String,
    val uploadTier: String,
    val uploadBase: String,
    val reporterEnvironment: String,
) {
    companion object {
        /** Off-device compositions (world, harnesses, tests) know none of these. */
        val UNKNOWN: DiagnosticEnvironment = DiagnosticEnvironment(
            appVersion = "unknown",
            buildNumber = "unknown",
            osVersion = "unknown",
            deviceModel = "unknown",
            uploadTier = "unknown",
            uploadBase = "unknown",
            reporterEnvironment = "unknown",
        )
    }
}
