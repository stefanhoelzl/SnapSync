package app.snapsync.feature.upload

import app.snapsync.model.PermissionStatus
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * How the app-driven engine is **kicked** (capability `upload-lifecycle`, "Triggers are delivered to the mechanism
 * and declined explicitly").
 *
 * Every trigger is delivered **unconditionally**: the engine's cycle decides at its entry gate, withholding when
 * this process may not create (no usable access, or the rig's switch). The caller does not ask whether
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
 * The app-driven engine (capability `ios-url-session-upload`): its triggers, plus the three verbs the membership
 * transitions call.
 *
 * | verb | what it does |
 * |---|---|
 * | [arm] | drain, and arm the first `BGProcessingTask` |
 * | [disarm] | cancel the scheduled `BGProcessingTask` — nothing else; in-flight transfers finish and record |
 * | [cancelTransfers] | cancel the in-flight transfers and delete their staged files — a **leave** only |
 *
 * None of them clears the ledger or repairs a row: nothing a transition does orphans a `REQUESTED` row, because
 * no transition but a leave stops in-flight work and the leave clears the ledger (decision record
 * `changes/both-uploaders-active`, D6).
 */
interface AppUploadEngine : UploadTriggers {
    /** Begin or resume uploading for the configured membership. Idempotent. */
    suspend fun arm()

    /** Stop new wakes. Idempotent, and destroys no durable state and no in-flight transfer. */
    suspend fun disarm()

    /** Cancel every in-flight transfer (a leave). Idempotent, and touches no ledger row. */
    suspend fun cancelTransfers()

    /**
     * The operating system is handing back this engine's finished background transfers. [completion] is the
     * operating system's handler: the engine holds it until it has absorbed them, or their deadline expires
     * (capability `ios-app-shell`, "OS completion handlers are released only after their work completes").
     */
    fun onBackgroundTransfers(completion: () -> Unit)
}

/**
 * The upload arm: **what each membership transition does** to the two uploaders (capability `upload-lifecycle`,
 * "Membership transitions reconcile the upload mechanisms in one tested place").
 *
 * It holds **no state**. Every decision is derived afresh from whether a membership exists, the registration fact
 * ([extensionRegistrable] — `model/extensionRegistrable`, read at the moment of the transition) and the current
 * grant.
 *
 * Both uploaders run: the app's creates under any usable grant, the extension under a full one, each deciding at
 * its own entry gate. What is left to reconcile is the extension's registration — which **spans the membership**:
 * registered at the join wherever the OS allows it, download-only included, and removed only at a leave — and
 * whether the app's heartbeat is armed (decision record `changes/both-uploaders-active`, D5):
 *
 * | transition | registration | app engine |
 * |---|---|---|
 * | join (after the share-set load and the save) | **forced** toggle where registrable | armed iff access usable |
 * | re-provision of the joined event | not called — nothing | nothing |
 * | reconfigure | nothing | armed iff access usable |
 * | permission change, launch | compared: register if registrable and absent; never deregister | armed iff usable |
 * | override change (rig) | deregister if switched off; else compared | armed iff usable |
 * | leave | deregister | disarmed, transfers cancelled |
 *
 * **Forced vs compared.** A join forces the toggle — it repairs a stale record that a bare enable would fail on
 * with `3202`. Everywhere else the OS's own read decides, and only under `GRANTED` (where the extension is
 * registrable): every write is refused under `LIMITED` (3311), and the OS's read is not trustworthy under
 * `NOT_DETERMINED`. A compared register runs only where the OS reads **no** record, so it wipes no job.
 */
class UploadTransitions(
    /** Whether an event is configured now. Read fresh at every transition, never held. */
    private val joined: () -> Boolean,
    /** The current grant. */
    private val permission: () -> PermissionStatus,
    /** The registration fact — `model/extensionRegistrable` over the OS fact, the grant and the rig's switch. */
    private val extensionRegistrable: () -> Boolean,
    /** The OS-driven registration where this OS carries its selector; `null` below iOS 26.1. */
    private val registration: ExtensionRegistration?,
    /** The app-driven engine, obtained at first use (it owns a process-lifetime background session). */
    private val appEngine: () -> AppUploadEngine,
    private val log: Logger = Logger.withTag("UploadTransitions"),
    private val logScope: LogScope = LogScope.NoOp,
) {

    /**
     * A join — a first join or a switch, after the share-set load and the save. The registration is **forced**:
     * the one transition allowed to repair a stale record. A re-provision of the joined event never reaches here
     * (the membership entry is not run for it), so a re-scan can never wipe the extension's in-flight jobs.
     */
    suspend fun onJoin() = log.invocation(logScope, "uploads.onJoin") {
        if (extensionRegistrable()) registration?.register()
        armIfUsable()
    }

    /**
     * A reconfigure, in any direction. The registration is not touched — it spans the membership — and the app
     * engine is kicked; the selection policy decides whether anything uploads (capability `reconfigure-membership`).
     */
    suspend fun onReconfigure() = log.invocation(logScope, "uploads.onReconfigure") {
        if (joined()) armIfUsable()
    }

    /** A real change of the photo grant — never the permission `StateFlow`'s replayed first value. */
    suspend fun onPermissionChanged() =
        log.invocation(logScope, "uploads.onPermissionChanged") { compare(deregisterIfOff = false) }

    /**
     * The rig's uploader switch was set or cleared (rig builds only). Compared, and immediate: the extension
     * cannot read the switch, so turning it off must deregister it now.
     */
    suspend fun onOverrideChanged() =
        log.invocation(logScope, "uploads.onOverrideChanged") { compare(deregisterIfOff = true) }

    /**
     * Host assembly — the app launched with its UI. Compared, so the extension's in-flight jobs survive a launch.
     * A cold background launch never reaches here.
     */
    suspend fun onLaunch() = log.invocation(logScope, "uploads.onLaunch") { compare(deregisterIfOff = false) }

    /**
     * A leave, or a switch leaving the previous membership: the one transition that stops in-flight work. The
     * caller clears the upload ledger and the configured event afterwards (capability `leave-event`).
     */
    suspend fun onLeave() = log.invocation(logScope, "uploads.onLeave") {
        registration?.deregister()
        val engine = appEngine()
        engine.disarm()
        engine.cancelTransfers()
    }

    /**
     * The compared reconcile. With no membership nothing is armed and nothing registered — a surviving record is
     * left as it is, because only a leave deregisters. With one, register a record the OS reads absent (only where
     * registrable, which implies `GRANTED`), deregister one the rig switched off, and arm iff access is usable.
     */
    private suspend fun compare(deregisterIfOff: Boolean) {
        if (!joined()) return
        val registration = registration
        if (registration != null) {
            val registrable = extensionRegistrable()
            // The OS's read is trusted only under a full grant; anything else changes nothing.
            val observed = if (permission() == PermissionStatus.GRANTED) registration.isRegistered() else null
            when {
                registrable && observed == false -> registration.register()
                deregisterIfOff && !registrable && observed == true -> registration.deregister()
            }
        }
        armIfUsable()
    }

    private suspend fun armIfUsable() {
        if (permission().grantsPhotoAccess) appEngine().arm() else appEngine().disarm()
    }
}
