package app.snapsync.feature.upload

import app.snapsync.data.membership.MembershipRead
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.model.GalleryAccess
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.membership.MembershipTransition
import app.snapsync.services.upload.ExtensionRegistration
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.dropWhile

/**
 * What the membership transitions do to the app's uploader, reconciled in one tested place — the three verbs, and
 * nothing a wake triggers.
 *
 * | verb | what it does |
 * |---|---|
 * | [arm] | request the tail, which arms the first `BGProcessingTask` |
 * | [disarm] | cancel the scheduled `BGProcessingTask` — nothing else; in-flight transfers finish and record |
 * | [cancelTransfers] | cancel the in-flight transfers and delete their staged files — a **leave** only |
 *
 * The OS wakes do not reach the uploader through this seam: each wake does its own work, then runs the sequences the
 * entry points share, whose upload units are the app uploader's (decision record `changes/own-work-per-wake`, D1).
 * The entry points implement this over those sequences and that mechanism (`UploadEngine`).
 *
 * None of them clears the ledger or repairs a row: nothing a transition does orphans a `REQUESTED` row, because
 * no transition but a leave stops in-flight work and the leave clears the ledger (decision record
 * `changes/both-uploaders-active`, D6).
 */
interface AppUploadEngine {
    /** Begin or resume uploading for the configured membership. Idempotent. */
    suspend fun arm()

    /** Stop new wakes. Idempotent, and destroys no durable state and no in-flight transfer. */
    suspend fun disarm()

    /** Cancel every in-flight transfer (a leave). Idempotent, and touches no ledger row. */
    suspend fun cancelTransfers()
}

/**
 * The upload arm: **what each membership transition does** to the two uploaders, reconciled in one tested place.
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
    /** The membership — whether an event is configured is read fresh at every transition, never held. */
    private val configSource: MembershipProjection,
    /** The current grant, read fresh at every transition. */
    private val photoAccess: GalleryAccessState,
    /** The registration fact — `model/extensionRegistrable` over the OS fact, the grant and the rig's switch. */
    private val extensionRegistrable: () -> Boolean,
    /**
     * The OS-driven registration — on every platform; where the OS carries no such mechanism its port answers
     * `Unsupported` and asks nothing (and [extensionRegistrable] is never true there, so a join never forces it).
     */
    private val registration: ExtensionRegistration,
    /** The app-driven engine, obtained at first use (it owns a process-lifetime background session). */
    private val appEngine: () -> AppUploadEngine,
    private val log: Logger = Logger.withTag("UploadTransitions"),
) {

    /**
     * A join — a first join or a switch, after the share-set load and the save. The registration is **forced**:
     * the one transition allowed to repair a stale record. A re-provision of the joined event never reaches here
     * (the membership entry is not run for it), so a re-scan can never wipe the extension's in-flight jobs.
     */
    suspend fun onJoin() = run {
        if (extensionRegistrable()) registration.register()
        armIfUsable()
    }.also { log.i { "uploads.onJoin" } }

    /**
     * A reconfigure, in any direction. The registration is not touched — it spans the membership — and the app
     * engine is kicked; the selection policy decides whether anything uploads.
     */
    suspend fun onReconfigure() = run {
        if (joined()) armIfUsable()
    }.also { log.i { "uploads.onReconfigure" } }

    /**
     * The upload **launch reconcile**, then every real change of the photo grant handed to [onChanged]: launch
     * reconciles by comparison, and only a join forces the repair. Launch first, then real changes only: the value the
     * launch reconciled against is not a transition — riding the `StateFlow`'s replay would fire a "permission change"
     * on every UI launch, forcing the extension's re-registration and wiping its in-flight jobs — and a change that
     * lands during the launch reconcile is still delivered (the prefix dropped is exactly the launch-time value).
     * Returns only when the grant's flow completes, which a `StateFlow` never does.
     */
    suspend fun followGrant(onChanged: suspend (GalleryAccess) -> Unit) {
        val atLaunch = photoAccess.grant.value
        onLaunch()
        photoAccess.grant.dropWhile { it == atLaunch }.collect { onChanged(it) }
    }

    /** A real change of the photo grant — never the permission `StateFlow`'s replayed first value. */
    suspend fun onPermissionChanged() =
        run { compare(deregisterIfOff = false) }.also { log.i { "uploads.onPermissionChanged" } }

    /**
     * The rig's uploader switch was set or cleared (rig builds only). Compared, and immediate: the extension
     * cannot read the switch, so turning it off must deregister it now.
     */
    suspend fun onOverrideChanged() =
        run { compare(deregisterIfOff = true) }.also { log.i { "uploads.onOverrideChanged" } }

    /**
     * Host assembly — the app launched with its UI. Compared, so the extension's in-flight jobs survive a launch.
     * A cold background launch never reaches here.
     */
    suspend fun onLaunch() = run { compare(deregisterIfOff = false) }.also { log.i { "uploads.onLaunch" } }

    /**
     * A leave, or a switch leaving the previous membership: the one transition that stops in-flight work. The
     * caller clears the upload ledger and the configured event afterwards.
     */
    suspend fun onLeave() = run {
        registration.deregister()
        val engine = appEngine()
        engine.disarm()
        engine.cancelTransfers()
    }.also { log.i { "uploads.onLeave" } }

    /**
     * Stop what a failed leave left uploading, while [none] proves no membership is held — unless a join is in progress,
     * whose own registration this may already be.
     */
    suspend fun stopLeftovers(
        @Suppress("UNUSED_PARAMETER") none: ConfirmedNotJoined,
        transition: MembershipTransition,
    ) =
        transition.unlessJoining { onLeave() }

    /**
     * The compared reconcile. With no membership nothing is armed and nothing registered — a surviving record is
     * left as it is, because only a leave deregisters. With one, register a record the OS reads absent (only where
     * registrable, which implies `GRANTED`), deregister one the rig switched off, and arm iff access is usable.
     */
    private suspend fun compare(deregisterIfOff: Boolean) {
        if (!joined()) return
        val registrable = extensionRegistrable()
        // The OS's read is trusted only under a full grant; anything else changes nothing. A platform without the
        // registration reads `null`, so it changes nothing either.
        val observed = if (photoAccess.full) registration.isRegistered() else null
        when {
            registrable && observed == false -> registration.register()
            deregisterIfOff && !registrable && observed == true -> registration.deregister()
        }
        armIfUsable()
    }

    /**
     * Whether an event is configured now — read fresh, never held. An unreadable membership defers: it is logged
     * and nothing is armed or registered, because acting on "could not read it" as "not joined" is a false leave
     * and acting on it as "joined" arms for an event we cannot name. The next transition reads it again.
     */
    private fun joined(): Boolean = when (configSource.read()) {
        is MembershipRead.Joined -> true
        is MembershipRead.NotJoined -> false
        is MembershipRead.Unavailable -> {
            log.w { "the membership is unreadable right now — deferring this transition to the next one" }
            false
        }
    }

    private suspend fun armIfUsable() {
        if (photoAccess.usable) appEngine().arm() else appEngine().disarm()
    }
}
