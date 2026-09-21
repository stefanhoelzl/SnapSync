package app.snapsync.feature.upload

import app.snapsync.model.PermissionStatus
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.UploadMechanism
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * How the app-driven engine is **kicked** (capability `upload-lifecycle`, "Triggers are delivered to the mechanism
 * and declined explicitly").
 *
 * Every trigger is delivered **unconditionally**, whatever mechanism is resolved: the engine's cycle decides at
 * its entry gate, declining as not resolved while the OS-driven mechanism runs. The caller does not ask whether
 * the engine is interested, because a caller that asks is an *invoker-gate* — the shape this capability ruled
 * against ("The arm's direction gate lives at the choke point, never at the invoker"). Delivering to a held
 * mechanism instead is what used to strand every cold background wake on an idle stand-in: nothing but a
 * UI-launch transition ever moved it, so the heartbeat's re-submission never ran.
 *
 * **No trigger takes an OS completion handler.** Each is a `suspend` function that returns when its work is
 * done; the entry point that received the handler holds an `OsReceipt` across the call, so a declining cycle
 * still returns and the handler is still released.
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
 * The app-driven engine (capability `ios-url-session-upload`): its triggers, plus the two verbs the membership
 * transitions call.
 *
 * | verb | what it does |
 * |---|---|
 * | [arm] | signal a restart to the cycle (the start-time stranded rule), drain, arm the first `BGProcessingTask` |
 * | [disarm] | cancel in-flight transfers and the scheduled `BGProcessingTask`; the session stays intact |
 *
 * Neither clears the ledger or repairs a row — a disarm's orphaned `REQUESTED` rows are demoted by the next arm's
 * restart rule, or by the OS-driven ritual's demote.
 */
interface AppUploadEngine : UploadTriggers {
    /** Begin or resume uploading for the configured membership. Idempotent. */
    suspend fun arm()

    /** Cease uploading. Idempotent, and destroys no durable state. */
    suspend fun disarm()
}

/**
 * The upload arm: **what each membership transition does** to the two upload mechanisms (capability
 * `upload-lifecycle`, "Membership transitions reconcile the upload mechanisms in one tested place").
 *
 * It holds **no state**. Every decision is derived afresh from the resolved kind ([resolve] —
 * `model/resolveUploadMechanism`, read at the moment of the transition), the membership's three-valued upload
 * posture, and, for the registration compare, the current grant. It replaced an arm that held one mechanism
 * instance, a kind → instance table, a relinquish wrapper and an idle stand-in: the facts they carried are the
 * table in [desired], and the rest was indirection.
 *
 * Exactly one mechanism writes the ledger, and that is now **gated** rather than structural: each engine's cycle
 * declines at its own entry gate when it may not run, and these transitions keep the OS registration consistent
 * with resolution. The `:test:architecture` guard drives both.
 *
 * **Forced vs compared.** A join forces the registration — the disable → demote → enable ritual repairs a stale
 * record that a bare enable would fail on with `3202`. Every other transition compares against what the OS
 * reports, and only under `GRANTED`: every write is refused under `LIMITED` (3311), and the OS's read is not
 * trustworthy under `NOT_DETERMINED`. So a launch no longer wipes and demotes the extension's in-flight jobs; a
 * stale record that still reads "enabled" waits for the next join.
 *
 * **Stand-down first.** The app engine is disarmed before the extension is registered, and the extension
 * deregistered before the engine is armed, so neither is brought up over the other's live transfers.
 */
class UploadTransitions(
    /** The current resolved kind. Read fresh at every transition, never held. */
    private val resolve: () -> UploadMechanism,
    // The CURRENT membership's upload posture: `true` = joined and the direction includes upload, `false` =
    // joined but download-only, `null` = **no event joined**. Three-valued, because collapsing "no membership"
    // into a Boolean is what once armed a producer for an event that did not exist.
    private val membershipIncludesUpload: () -> Boolean?,
    /** The current grant — the only condition under which the OS's registration read can be trusted. */
    private val permission: () -> PermissionStatus,
    /** The OS-driven registration where this OS carries its selector; `null` below iOS 26.1. */
    private val registration: ExtensionRegistration?,
    /** The app-driven engine, obtained at first use (it owns a process-lifetime background session). */
    private val appEngine: () -> AppUploadEngine,
    private val log: Logger = Logger.withTag("UploadTransitions"),
    private val logScope: LogScope = LogScope.NoOp,
) {

    /**
     * A join — a first join or a switch, after the join-time load; or a re-provision of the joined event. The
     * registration is **forced**: the one transition allowed to repair a stale record.
     */
    suspend fun onJoin() = log.invocation(logScope, "uploads.onJoin") { reconcile(forced = true) }

    /**
     * A reconfigure whose new direction includes upload. (A disabling reconfigure calls nothing: in-flight
     * uploads drain and the cycle's direction gate withholds new work — capability `reconfigure-membership`.)
     */
    suspend fun onReconfigure() = log.invocation(logScope, "uploads.onReconfigure") { reconcile(forced = false) }

    /** A real change of the photo grant — never the permission `StateFlow`'s replayed first value. */
    suspend fun onPermissionChanged() =
        log.invocation(logScope, "uploads.onPermissionChanged") { reconcile(forced = false) }

    /**
     * The development mechanism override was set or cleared (rig builds only). Compared, and immediate: the
     * extension cannot read the override, so a pin away from the OS-driven mechanism must deregister it now
     * rather than leave its permission-only gate to admit a second writer until the next transition.
     */
    suspend fun onOverrideChanged() =
        log.invocation(logScope, "uploads.onOverrideChanged") { reconcile(forced = false) }

    /**
     * Host assembly — the app launched with its UI. Compared, so the extension's in-flight jobs survive a launch;
     * and an arm where the app engine is wanted, which carries the start-time restart signal and the first
     * heartbeat exactly as the old replay-driven start did. A cold background launch never reaches here.
     */
    suspend fun onLaunch() = log.invocation(logScope, "uploads.onLaunch") { reconcile(forced = false) }

    /**
     * A leave, or a switch leaving the previous membership: stand everything down. The caller clears the upload
     * ledger and the configured event afterwards (capability `leave-event`); nothing here touches either.
     */
    suspend fun onLeave() = log.invocation(logScope, "uploads.onLeave") {
        registration?.deregister()
        appEngine().disarm()
    }

    /** What the registration should be. [KEEP] is "no write is permitted, and none is needed". */
    private enum class Registration { WANTED, NOT_WANTED, KEEP }

    private class Desired(val registration: Registration, val appArmed: Boolean)

    /**
     * The desired state. With no usable access the resolved kind is idle: disarm the app engine, and leave the
     * registration alone — the extension withholds at its own gate, and every write would be refused anyway.
     * Idle **with** usable access is a development pin ("run nothing"), and that is a pin away from the OS-driven
     * mechanism like any other: the extension cannot read it, so it is deregistered.
     */
    private fun desired(): Desired {
        if (membershipIncludesUpload() != true) return Desired(Registration.NOT_WANTED, appArmed = false)
        return when (resolve()) {
            UploadMechanism.PHOTOKIT -> Desired(Registration.WANTED, appArmed = false)
            UploadMechanism.URL_SESSION -> Desired(Registration.NOT_WANTED, appArmed = true)
            UploadMechanism.IDLE -> Desired(idleRegistration(), appArmed = false)
        }
    }

    private fun idleRegistration(): Registration =
        if (permission().grantsPhotoAccess) Registration.NOT_WANTED else Registration.KEEP

    private suspend fun reconcile(forced: Boolean) {
        val desired = desired()
        // Stand-down first: disarming before a registration means the ritual's demote meets no live transfer,
        // and disarming whenever the engine is unwanted cancels what a PREVIOUS process left running.
        if (!desired.appArmed) appEngine().disarm()
        reconcileRegistration(desired.registration, forced)
        if (desired.appArmed) appEngine().arm()
    }

    private suspend fun reconcileRegistration(wanted: Registration, forced: Boolean) {
        val registration = registration ?: return
        // Compared: the OS's read, trusted only under a full grant; anything else changes nothing.
        val observed = if (permission() == PermissionStatus.GRANTED) registration.isRegistered() else null
        when (wanted) {
            Registration.WANTED -> if (forced || observed == false) registration.register()
            Registration.NOT_WANTED -> if (forced || observed == true) registration.deregister()
            Registration.KEEP -> Unit
        }
    }
}
