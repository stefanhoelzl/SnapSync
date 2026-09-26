package app.snapsync.feature.upload

import app.snapsync.model.PauseReason
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionScope
import app.snapsync.model.SuppressionReadiness
import app.snapsync.model.UploaderPin
import app.snapsync.model.grantsPhotoAccess


/** The assembled inputs for the edge upload provider: the compile-time host and the joined event. */
class UploadConfig(
    val host: String,
    val eventId: String,
)

/**
 * Combine the two edge-URL inputs — the runtime [eventId] (Keychain payload) and the compile-time
 * [host] (the baked `uploadBase`) — into an [UploadConfig]. Returns `null` — meaning "skip this
 * cycle, there is nothing to do" — when either input is absent: a `null` `eventId` (not joined yet)
 * or a missing/blank host (a build misconfiguration). Pure and platform-free, so the
 * assemble-or-skip decision is unit-tested off-device while the iOS root stays trivial glue.
 */
fun buildUploadConfig(eventId: String?, host: String?): UploadConfig? {
    if (eventId.isNullOrEmpty() || host.isNullOrEmpty()) return null
    return UploadConfig(host = host, eventId = eventId)
}

/**
 * The membership facts one cycle needs, in the shared vocabulary — present **only** when the read
 * found a joined event.
 *
 * It exists so [cycleGate] can never be handed a [SelectionPolicy] for a membership that does not exist.
 * Fold these into the gate's arguments instead and every caller must supply a policy even in the
 * not-joined case, which means inventing one — and an invented cutoff is precisely the invariant this
 * project is built against (a membership's cutoff is required, never absent). The type deletes the
 * state rather than tolerating it, the same reasoning [SelectionPolicy] itself is built on.
 *
 * The [policy] is the config-derived one ([SelectionPolicy.from]); the cycle completes it with the two
 * port-read exclusion sets (`excluding`) once it can read them. Primitives plus that pure value, never a
 * config type: this module stays event- and platform-agnostic, so a root translates *its* storage into
 * this and the decision stays testable off every device.
 */
class JoinedMembership(
    val eventId: String,
    /**
     * The membership's selection policy, as a **supplier** rather than a built value.
     *
     * The one derivation reads two ports — the download store's imported ids and the platform album
     * lookup (capability `photo-sharing`) — and the entry-gate translation that builds this
     * membership must stay **port-pure** (capability `background-upload`: a fresh three-state config read,
     * the identity probe, the host read, and nothing else). So the translation closes over the readers
     * instead of calling them; the shared composition is where both the config and the readers are in
     * scope, and invoking this is the cycle's business.
     *
     * Capturing a port is not reading one, so the gate's purity is intact. What this buys is that the
     * cycle receives an already-decided policy — never a half-built one it has to complete, which is what
     * the deleted `excluding()` step made it do, and why the capture floor kept having to be extracted
     * back out of the policy to scope the album fetch.
     */
    val policy: suspend () -> SelectionPolicy,
    val saveToAlbum: Boolean,
    /**
     * The ledger's manifest version, read by the entry-gate translation **before** the membership (capability
     * `background-upload`), and carried by the cycle to its manifest publish (capability `photo-sharing`).
     *
     * Read first because every change that could alter the projection — a ledger row, or a reconfigure's
     * save — advances it: a change the projection misses therefore happened after this read and carries a
     * higher number, so the backend can refuse an older publish without ever refusing a newer one.
     */
    val manifestVersion: Long,
)

/**
 * What one invocation should do — the **five-way** gate. At its heart, "the config could not be read" is
 * not "no event is configured".
 *
 * The distinction is load-bearing: an upload cycle runs when the device is idle — which usually means
 * *locked* — and a locked device could not read the Keychain at all before the accessibility fix. That
 * read failure used to arrive as "not joined", so every invocation performed a **false leave**, clearing
 * the join state a later readable cycle then had to rebuild. [NotJoined] now clears nothing (the explicit
 * leave clears the ledger itself, capability `manage-membership`), but "could not look" and "not joined" still
 * mean different things to every reader of this answer.
 *
 * This gate is consumed by [UploadCycle.run] — the choke point every trigger on every tier funnels
 * through — and **not** by a composition root. A root that reaches this decision itself reaches it for
 * whichever tiers its author enumerated: the OS-invoked tier had this gate and the app-driven tier did
 * not, for the same reason the direction gate once reached one tier and not the other. A root supplies the reads; the cycle decides.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access` (the three-state read),
 * `changes/archive/…-fix-upload-config-gate` (moving it to the choke point).
 */
sealed interface CycleGate {

    /**
     * A required input could not be read. Touch **nothing**: no ledger write, no manifest, no jobs.
     * Retry later.
     *
     * [detail] is the root's forensics — which read failed, and with what status. The decision is made
     * in shared code that cannot see either, and an unreadable membership is invisible on a device
     * except through this line: nothing else distinguishes "we skipped, correctly" from "we did
     * nothing, wrongly". The root supplies it; the cycle logs it verbatim, so it stays one line in one
     * file (`debug.log` is the canonical un-redacted channel for exactly this).
     */
    data class Skip(val detail: String) : CycleGate

    /** There is definitively no event configured (or no baked host): upload nothing, and touch nothing. */
    data object NotJoined : CycleGate

    /**
     * Joined, but this process may not create — the extension under any grant but `GRANTED`, the app without
     * usable access, before its partial grant's selection has been read, or switched off by the rig. Settle
     * **narrowly** (record and acknowledge what the platform presented; create nothing) and publish nothing: a
     * grant is temporary, so the empty manifest a declined direction publishes would wrongly blank this device's
     * photos from the event.
     */
    data class Withheld(val config: UploadConfig) : CycleGate

    /**
     * Joined and admitted, but the echo-suppression store is at a schema this process may not migrate (the
     * extension opens it read-only): touch **nothing**, not even the presented results, and ask to be invoked
     * again — the app migrates it on its next run (capability `receiving-photos`).
     */
    data class Paused(val reason: PauseReason) : CycleGate

    /** Joined, configured and admitted: run the cycle. */
    data class Run(val config: UploadConfig, val membership: JoinedMembership) : CycleGate
}

/**
 * The gate's last step, taken only for an admitted [run]: whether this process's echo-suppression read can
 * answer (capability `receiving-photos`). Asked after the admission, so a process that may not create never
 * opens the store (the extension under a partial grant withholds; it never pauses).
 *
 * An unreadable store is [CycleGate.Skip] — "I could not look", upload nothing this run — and an old one
 * [CycleGate.Paused]. Running without suppression is never an answer: it would re-upload downloaded photos.
 */
fun suppressionGate(run: CycleGate.Run, readiness: SuppressionReadiness): CycleGate = when (readiness) {
    SuppressionReadiness.Ready -> run
    SuppressionReadiness.OldSchema -> CycleGate.Paused(PauseReason.OLD_SCHEMA)
    is SuppressionReadiness.Unavailable -> CycleGate.Skip("echo-suppression store unavailable (${readiness.detail})")
}

/**
 * Whether THIS process may run an upload cycle now (capability `background-upload`, "The upload cycle owns
 * its entry decision") — the per-process answer a root supplies and [cycleGate] consumes.
 *
 * Asymmetric by process, and each root states its own: the app admits under any usable grant (`LIMITED`
 * scoped to the selection snapshot); the extension admits exactly under `GRANTED`, read in its own process.
 * Both may admit at once — on iOS ≥26.1 under a full grant both uploaders create, and an overlap is a duplicate
 * of the same object, never a loss (decision record `changes/both-uploaders-active`, D2/D3). Neither infers it
 * from its selection scope — the extension's default there is untrue under a partial grant.
 */
enum class UploadAdmission {
    /** This process's uploader may create. */
    Admit,

    /** This process may not create now: settle narrowly, publish nothing. */
    Withheld,
}

/**
 * The **app** process's admission: its uploader creates under any usable grant — `GRANTED` or `LIMITED`, on every
 * OS version, beside the extension where one is registered — unless the rig's [pin] turned it off.
 *
 * Without usable access it withholds rather than touching nothing: the narrow settle creates no job and reads
 * no library, and a completion that arrives meanwhile still records through the transport's guarded write.
 *
 * It withholds too while the [scope] is [SelectionScope.Unread] — a partial grant whose selection has not been
 * read yet. A read selection snapshot is an authoritative walk, so a cycle run over an unread one would take the
 * absence of a selection for the absence of every photo and delete their rows (capability
 * `photo-access`; decision record `changes/selection-is-the-walk`, D1). The scope is derived from the
 * same snapshot cell discovery reads, so admission and discovery cannot disagree about whether it was read.
 */
fun appAdmission(permission: GalleryAccess, scope: SelectionScope, pin: UploaderPin? = null): UploadAdmission =
    if (permission.grantsPhotoAccess && scope != SelectionScope.Unread && pin?.app != false) {
        UploadAdmission.Admit
    } else {
        UploadAdmission.Withheld
    }

/**
 * The **extension** process's admission: it runs exactly under a full grant, read in its own process.
 *
 * It reads permission only: the rig's uploader switch lives in the app process's memory, so the extension could
 * not honour it anyway — switching the extension off is enforced by the app deregistering it. And never its
 * selection scope: the extension's default there (`Unrestricted`) is untrue
 * under a partial grant, which is exactly the case this answer exists for.
 */
fun extensionAdmission(permission: GalleryAccess): UploadAdmission =
    if (permission == GalleryAccess.GRANTED) UploadAdmission.Admit else UploadAdmission.Withheld

/**
 * Decide what this invocation does. [configReadable] is `false` **only** when a required read failed
 * (protected data unavailable) — never when the config is merely absent or undecodable, both of which
 * are genuine [CycleGate.NotJoined] states that a later retry cannot improve.
 *
 * [configReadable] covers **every** protected read the cycle needs, not just the config: resolving the
 * device identity fails the same way (both are Keychain items) and the joined outcome needs it — the
 * engine and the manifest producer each close over it. An
 * unresolvable identity is "I could not look", never "no identity" (the Keychain-backed identity never
 * reports absence — an absent item mints), so it belongs on this side of the roll-up rather than in a
 * fourth state.
 *
 * Pure and platform-free, so the skip-or-leave-or-run decision is unit-tested off-device while each
 * root stays trivial glue.
 *
 * [admission] is decided only for a joined, configured membership: "unreadable" and "not joined" outrank
 * it, so a process that may not run still reports those states as they are.
 *
 * Note a missing/blank [host] — a build misconfiguration, not a leave — still yields [NotJoined], as
 * it always has. Untangling that is a separate concern from this one.
 */
fun cycleGate(
    configReadable: Boolean,
    membership: JoinedMembership?,
    host: String?,
    // Required, no default: "may this process create?" has no safe invented answer — a wrong `Admit` reads the
    // library without a grant, and a wrong `Withheld` silently stops uploading.
    admission: UploadAdmission,
    skipDetail: String = "",
): CycleGate {
    if (!configReadable) return CycleGate.Skip(skipDetail)
    if (membership == null) return CycleGate.NotJoined
    val config = buildUploadConfig(membership.eventId, host) ?: return CycleGate.NotJoined
    // Decided here, BEFORE the cycle builds the membership's policy: building it reads the album structure,
    // and that read under `NOT_DETERMINED` presents iOS's permission dialog — from a background wake.
    return when (admission) {
        UploadAdmission.Withheld -> CycleGate.Withheld(config)
        UploadAdmission.Admit -> CycleGate.Run(config, membership)
    }
}
