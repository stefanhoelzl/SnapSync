package app.snapsync.feature.upload

import app.snapsync.model.UploadMechanism
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * How a mechanism is **kicked** (capability `upload-lifecycle`, "Triggers are delivered to the mechanism
 * and declined explicitly") — a seam kept **separate** from [UploadProducer] so the lifecycle seam keeps
 * exactly its two verbs and the arm is handed no trigger it could invoke.
 *
 * Every trigger is delivered **unconditionally** to whichever mechanism is currently resolved. The caller
 * does not ask whether a mechanism is interested, because a caller that asks is an *invoker-gate*, and
 * this capability has already ruled on that shape once ("The arm's direction gate lives at the choke
 * point, never at the invoker"): the enumeration of invokers is invalidated silently by a new tier or a
 * new trigger. It was also, concretely, four thunks in an untested composition root that two tiers had to
 * agree about, two of which were already identical between them.
 *
 * **There are no defaults here, deliberately.** A mechanism the OS schedules for itself answers `Unit` to
 * a foreground pump — but it writes that answer down, with its reason, at its own definition site. An
 * inherited blank would be an unstated answer, which this capability forbids for the cycle's ports for
 * exactly the same reason ("a permissive default on such a port is an unstated answer").
 *
 * **No trigger takes an OS completion handler.** Each is a `suspend` function that returns when its work
 * is done; the entry point that received the handler holds an `OsReceipt` across the call, for the
 * deadline named for that OS wake. So a mechanism cannot fail to release a handler — it never holds one
 * — and "a trigger flow never outlives its own run" applies one layer down, in the mechanism.
 */
interface UploadTriggers {
    /** The app came to the foreground. */
    suspend fun onForeground()

    /** A `content-available` push named [eventId]. */
    suspend fun onSilentPush(eventId: String)

    /** A background-task heartbeat fired. */
    suspend fun onBackgroundTask()

    /** The user's photo selection changed under a partial grant. */
    suspend fun onSelectionChanged()
}

/**
 * One resolved upload mechanism: the arm's lifecycle seam and the OS-trigger seam on one object.
 *
 * Resolution yields exactly one of these at a time (`upload-lifecycle`, "The upload mechanism is
 * resolved, never selected"), which is what makes the exactly-one-started invariant structural again —
 * the arm can only *name* one, so starting two has no expression rather than being policed by a test.
 */
interface UploadMechanismRuntime : UploadProducer, UploadTriggers

/**
 * The mechanism that runs when no upload work may occur — unusable photo access
 * (`upload-lifecycle`, "A mechanism is always resolved").
 *
 * **It is a mechanism, not an absence, and that is the whole point.** Every OS trigger carries a
 * completion handler the system waits on, and an unanswered handler "costs the app its future background
 * wakes" (`ports/OsReceipt`). Routing a trigger to a `null` mechanism strands one. This object declines
 * every trigger *and still returns*, so the entry point holding the receipt releases it normally — the
 * deliberate collapse "Absence is never silent" permits, with its consequence named: nothing is
 * uploaded, every handler is still answered.
 */
object IdleUploadMechanism : UploadMechanismRuntime {
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun onForeground() = Unit
    override suspend fun onSilentPush(eventId: String) = Unit
    override suspend fun onBackgroundTask() = Unit
    override suspend fun onSelectionChanged() = Unit
}

/**
 * The upload arm's two-verb lifecycle seam (capability `upload-lifecycle`). Each upload mechanism
 * implements it exactly once:
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
 * which membership transition, over the mechanism **resolution** yields.
 *
 * It lives here — in a tested, platform-free capability — rather than in the iOS composition root,
 * because this *is* behavior, and the root is wiring-only and untested by the project's hard rule.
 * Parking it there is exactly why the destructive-provision bug had no test.
 *
 * It holds **one** mechanism at a time. That is what makes the exactly-one-started invariant structural
 * again: starting two has no expression here, because there is only one reference to start. The invariant
 * was structural once, was given up when the mechanism choice became an input of runtime permission — no
 * once-per-process *construction* could express that — and re-resolution gets it back without giving the
 * runtime dependence up.
 *
 * The arm decides *when*, never *which*: [resolve] is the pure, exhaustively-tested rule
 * (`model/resolveUploadMechanism`), read fresh at every transition so a caller cannot hand the arm a
 * stale view of the permission it is deciding about. [mechanismFor] turns a resolved kind into the
 * instance for it, caching where a platform demands a process-lifetime singleton.
 *
 * The arm is enabled when photo access is usable **and** the configured membership's direction includes
 * upload (capability `join-event`). With **no event joined** there is no membership and therefore no
 * direction, so the arm is *not* enabled and neither verb fires. That is why [membershipIncludesUpload]
 * is three-valued rather than a `Boolean`: "no membership" is a distinct answer from "a membership that
 * excludes upload", and collapsing the two in the composition root is what previously answered *enabled*
 * for an absent membership (a `?: true`), starting a producer for an event that does not exist. Photo
 * access can be usable with no config — the join gate's photo-access explainer raises the system dialog
 * **before** the join is confirmed, and `join-event` requires that "no config is saved and no upload
 * producer is enabled until the user confirms".
 */
class UploadArm(
    /** The current resolved mechanism kind. Read fresh at every transition, never captured. */
    private val resolve: () -> UploadMechanism,
    /** The instance for a kind. Caches where the platform demands a process-lifetime singleton. */
    private val mechanismFor: (UploadMechanism) -> UploadMechanismRuntime,
    // The CURRENT membership's upload posture: `true` = joined and the direction includes upload,
    // `false` = joined but download-only, `null` = **no event joined**. One read, so there is no race
    // between "is there a membership" and "does it upload".
    private val membershipIncludesUpload: () -> Boolean?,
    private val log: Logger = Logger.withTag("UploadArm"),
    private val logScope: LogScope = LogScope.NoOp,
) {

    /**
     * The mechanism this process is currently running, and the one every OS trigger is delivered to.
     *
     * Instance state, and the only piece the arm has ever held — a **coordination primitive**, not
     * authority (`module-architecture`, "State and authority"). It is derived: a relaunch re-resolves from
     * permission and the first transition re-establishes it, so the kill-test holds and nothing durable
     * depends on it.
     *
     * It starts at [IdleUploadMechanism] rather than `null` so a trigger arriving before any transition —
     * a cold background wake is exactly that — reaches a mechanism that declines *and answers the
     * platform*, instead of stranding an OS completion handler.
     */
    private var current: UploadMechanismRuntime = IdleUploadMechanism

    /** Where every OS trigger is delivered (capability `upload-lifecycle`, "Triggers are delivered…"). */
    val triggers: UploadTriggers get() = current

    /**
     * Move to [kind], **stop-then-start** — with the hand-over performed by the *incoming* mechanism.
     *
     * The arm does not tear the outgoing one down itself, because the right teardown depends on where
     * control is going: relinquishing the OS-driven mechanism on the way to the app-driven one must be
     * deregistration **only**, while its full `stop()` (deregister plus a blanket ledger clear and a
     * shared-cursor reset) is right on a leave, where nothing runs afterwards. Only the incoming cell
     * knows which it needs, so [RelinquishThenRun] does it first and this stays a hand-over rather than a
     * decision table the arm would have to carry.
     *
     * A resolution that yields the kind already held is **not** a teardown: it is the same instance, and
     * `start()` — idempotent by contract — simply re-arms it.
     */
    private suspend fun switchTo(kind: UploadMechanism) {
        val next = mechanismFor(kind)
        current = next
        next.start()
    }

    /**
     * Stop every mechanism this composition can yield — not merely the one currently held (idempotent;
     * never destroys dedup state).
     *
     * `current` is not enough, and that is a fact about the platform rather than defensiveness. Both
     * mechanisms leave state the **OS** keeps: the OS-driven one a configuration record that survives
     * relaunch and reinstall, the app-driven one in-flight background transfers and a submitted
     * background task. A process that has just launched holds `IdleUploadMechanism` and has started
     * nothing, while work it never started may still be running on its behalf. Stopping only what this
     * process started would leave exactly that.
     */
    private suspend fun stopAll() {
        UploadMechanism.entries.map(mechanismFor).distinct().forEach { it.stop() }
    }

    /**
     * A membership was provisioned — a fresh join, an event switch, or a freshly-created event. (Re-scanning
     * the *already-joined* event never reaches here: `JoinEvent` short-circuits it as `AlreadyJoined`.)
     *
     * With access already usable this **starts** the resolved mechanism. It does not toggle, disable, or
     * reset anything: the cycle re-reads config each run and its marker-gated reconciliation seeds
     * already-stored resources as `COMPLETED` and clears the discovery cursor before any job is created.
     * In-flight transfers are deliberately left alone — the byte URL is device-partitioned and
     * event-independent, so an upload in flight stays valid across a switch and cancelling it would only
     * re-upload identical bytes to an identical URL.
     *
     * A download-only membership **stops** rather than merely skipping the start: a grant that landed
     * *before* this join may already have started a mechanism (the grant collector fires independently of
     * the event), so not-starting is not enough.
     *
     * Without usable access, neither verb fires — resolution answers [UploadMechanism.IDLE] and the
     * transition to usable access will drive it ([onPermissionChanged]).
     */
    suspend fun onProvision() = log.invocation(logScope, "arm.onProvision") {
        if (resolve() == UploadMechanism.IDLE) return@invocation
        // A provision always has a membership, so `null` is unreachable here; treating it like
        // download-only (stop) is the safe reading if it ever were.
        if (membershipIncludesUpload() == true) switchTo(resolve()) else stopAll()
    }

    /**
     * Photo access changed. With usable access and an upload-inclusive membership, starts the mechanism
     * resolution now yields — which on a `GRANTED` ↔ `LIMITED` flip is a **switch**, because the resolved
     * kind changes: the outgoing mechanism stops (the OS-driven one deregistering its extension) before
     * the incoming one starts. Where the flip does not change the resolved kind, nothing is torn down.
     *
     * With a download-only membership — or **no membership at all** — neither verb fires: there is no
     * event to upload to, and starting here would enable a mechanism before the join is confirmed. The
     * join's [onProvision] is what arms it.
     *
     * This fires on the *transition*, so it cannot rescue a membership provisioned while access was
     * already usable — [onProvision] owns that case.
     */
    suspend fun onPermissionChanged() = log.invocation(logScope, "arm.onPermissionChanged") {
        val kind = resolve()
        if (kind == UploadMechanism.IDLE) return@invocation
        if (membershipIncludesUpload() == true) switchTo(kind)
    }

    /**
     * The user left the event. Stops the mechanism and nothing more — the caller clears the configured
     * event. The ledger and the device-global accumulator are **kept**: they are valid across events, so a
     * later join re-uploads nothing already in this device's byte partition. The reconciler clears the
     * `joinedEventId` marker on the next cycle.
     *
     * The mechanism's own `stop()` may clear its discovery cursor here (the OS-driven one does, to repair
     * the jobs the OS wiped). That costs a re-enumeration, not a re-upload — and a rejoin would have
     * cleared it anyway, since a mismatched marker forces the reconciler to re-baseline.
     */
    suspend fun onLeave() = log.invocation(logScope, "arm.onLeave") {
        stopAll()
    }
}
