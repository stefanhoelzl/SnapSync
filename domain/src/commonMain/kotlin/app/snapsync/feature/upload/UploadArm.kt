package app.snapsync.feature.upload

import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * The upload arm's tier-specific **mechanism** (capability `upload-lifecycle`). Each upload tier supplies
 * exactly one implementation:
 *
 * |            | `start()`                                   | `stop()`                                              |
 * |------------|---------------------------------------------|-------------------------------------------------------|
 * | PhotoKit   | the `PHPhotosError 3202` disable→enable dance | `enable(false)` + `clearRequested` + clear the cursor |
 * | URLSession | sweep staging + pump a cycle + arm the heartbeat | cancel transfers + cancel the heartbeat           |
 *
 * There are **two** verbs and no destructive third. This is deliberate and load-bearing: the tier-blind
 * `enableBackgroundUpload()` that this seam replaces composed a PhotoKit-only "toggle off, toggle on"
 * ritual, and on the app-driven tier its "off" half resolved to a full *leave* — cancelling transfers,
 * cancelling the heartbeat, and wiping the ledger **and** discovery cursor — while its "on" half was a
 * no-op below iOS 26.1. Joining an event therefore tore the upload arm down and started nothing.
 *
 * With no destructive verb on the seam there is no edge from *provision* to *destruction* to get wrong.
 * `stop()` never clears the **ledger**: that state is device-global dedup (`sync-ledger`,
 * "Event-independent key"), it stays true across a leave / switch / re-join, and only a triggered
 * reconciliation's `resetTo` ever re-baselines it (`event-rejoin-reconciliation`).
 *
 * The **cursor** is not dedup state, which is why the PhotoKit column above may clear it: the OS's
 * extension-disable wipes every in-flight job, and `clearRequested()` alone leaves those photos behind a
 * settled cursor that would never re-surface them (`ios-photokit-upload`). Clearing it costs one full
 * re-enumeration, which creates no job for anything already `COMPLETED` — the ledger it did not touch
 * still knows. A tier may clear its own cursor only to repair its own mechanism, and only while dedup
 * survives (`upload-lifecycle`).
 */
interface UploadProducer {
    /** Begin or resume uploading for the currently-configured membership. Idempotent. */
    suspend fun start()

    /**
     * Cease uploading. Idempotent, and **destroys no durable state** — it must not clear the ledger, must
     * not clear the discovery cursor, and must not delete stored bytes.
     */
    suspend fun stop()
}

/**
 * The tier-neutral upload-arm lifecycle (capability `upload-lifecycle`): which producer verb fires on
 * which membership transition. It lives here — in a tested, platform-free capability — rather than in the
 * iOS composition root, because this *is* behavior, and the root is wiring-only and untested by the
 * project's hard rule. Parking it there is exactly why the destructive-provision bug had no test.
 *
 * The arm is enabled when photo access is granted **and** the configured membership's direction includes
 * upload (capability `join-event`). With **no event joined** there is no membership and therefore no
 * direction, so the arm is *not* enabled and neither verb fires.
 *
 * That is why [membershipIncludesUpload] is three-valued rather than a `Boolean`: "no membership" is a
 * distinct answer from "a membership that excludes upload", and collapsing the two in the composition
 * root is what previously answered *enabled* for an absent membership (a `?: true`), starting a producer
 * for an event that does not exist. Photo access can be `GRANTED` with no config — the join gate's
 * photo-access explainer raises the system dialog **before** the join is confirmed (capability
 * `join-event`), and that capability requires that "no config is saved and no upload producer is enabled
 * until the user confirms". So this is not a nicety: a two-valued seam violates it. The root now supplies
 * only a projection of the current config and defaults nothing.
 */
class UploadArm(
    private val producer: UploadProducer,
    // Read fresh at each transition rather than passed in, so a caller cannot hand the arm a stale or
    // wrong view of the membership it is deciding about.
    private val isGranted: () -> Boolean,
    // The CURRENT membership's upload posture: `true` = joined and the direction includes upload,
    // `false` = joined but download-only, `null` = **no event joined**. One read, so there is no race
    // between "is there a membership" and "does it upload".
    private val membershipIncludesUpload: () -> Boolean?,
    private val log: Logger = Logger.withTag("UploadArm"),
    private val logScope: LogScope = LogScope.NoOp,
) {

    /**
     * A membership was provisioned — a fresh join, an event switch, or a freshly-created event. (Re-scanning
     * the *already-joined* event never reaches here: `JoinEvent` short-circuits it as `AlreadyJoined`.)
     *
     * With access already granted this **starts** the producer. It does not toggle, disable, or reset
     * anything: the cycle re-reads config each run and its marker-gated reconciliation seeds already-stored
     * resources as `COMPLETED` and clears the discovery cursor before any job is created. In-flight
     * transfers are deliberately left alone — the byte URL is device-partitioned and event-independent, so
     * an upload in flight stays valid across a switch and cancelling it would only re-upload identical bytes
     * to an identical URL.
     *
     * A download-only membership **stops** the producer rather than merely skipping the start: a grant that
     * landed *before* this join may already have started it (the grant collector fires independently of the
     * event), so not-starting is not enough.
     *
     * Without access, neither verb fires — the transition to granted will drive it ([onPermissionGranted]).
     */
    suspend fun onProvision() = log.invocation(logScope, "arm.onProvision") {
        if (!isGranted()) return@invocation
        // A provision always has a membership, so `null` is unreachable here; treating it like
        // download-only (stop) is the safe reading if it ever were.
        if (membershipIncludesUpload() == true) producer.start() else producer.stop()
    }

    /**
     * Photo access became `GRANTED`. Starts the producer unless the current membership is download-only —
     * or unless there is **no membership at all**, in which case neither verb fires: there is no event to
     * upload to, and starting here would enable a producer before the join is confirmed. The join's
     * [onProvision] is what arms it.
     *
     * This fires on the *transition*, so it cannot rescue a membership provisioned while access was already
     * granted — [onProvision] owns that case.
     */
    suspend fun onPermissionGranted() = log.invocation(logScope, "arm.onPermissionGranted") {
        if (membershipIncludesUpload() == true) producer.start()
    }

    /**
     * The user left the event. Stops the producer and nothing more — the caller clears the configured
     * event. The ledger and the device-global accumulator are **kept**: they are valid across events, so a
     * later join re-uploads nothing already in this device's byte partition. The reconciler clears the
     * `joinedEventId` marker on the next cycle.
     *
     * The producer's own `stop()` may clear its discovery cursor here (PhotoKit does, to repair the jobs
     * the OS wiped). That costs a re-enumeration, not a re-upload — and a rejoin would have cleared it
     * anyway, since a mismatched marker forces the reconciler to re-baseline.
     */
    suspend fun onLeave() = log.invocation(logScope, "arm.onLeave") {
        producer.stop()
    }
}
