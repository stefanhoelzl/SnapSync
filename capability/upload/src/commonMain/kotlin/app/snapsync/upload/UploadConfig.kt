package app.snapsync.upload

import app.snapsync.model.Contribution

/** The assembled inputs for the edge upload provider: the compile-time host and the joined event. */
class UploadConfig(
    val host: String,
    val eventId: String,
)

/**
 * Combine the two edge-URL inputs — the runtime [eventId] (Keychain payload) and the compile-time
 * [host] (`BackgroundUploadURLBase`) — into an [UploadConfig]. Returns `null` — meaning "skip this
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
 * It exists so [cycleGate] can never be handed a [Contribution] for a membership that does not exist.
 * Fold these into the gate's arguments instead and every caller must supply a contribution even in the
 * not-joined case, which means inventing one — and an invented cutoff is precisely the invariant this
 * project is built against (a membership's cutoff is required, never absent). The type deletes the
 * state rather than tolerating it, the same reasoning [Contribution] itself is built on.
 *
 * Primitives plus [Contribution] (`:domain:gallery`), never a config type: this module stays event- and
 * platform-agnostic, so a root translates *its* storage into this and the decision stays testable off
 * every device.
 */
class JoinedMembership(
    val eventId: String,
    val contribution: Contribution,
    val saveToAlbum: Boolean,
)

/**
 * What one invocation should do — the **three-way** gate, because "the config could not be read" is
 * not "no event is configured".
 *
 * The distinction is load-bearing: [NotJoined] runs the leave-side reconciliation, which **clears the
 * persisted `joinedEventId` marker** (capability `event-rejoin-reconciliation`). An upload cycle runs
 * when the device is idle — which usually means *locked* — and a locked device could not read the
 * Keychain at all before the accessibility fix. That read failure used to arrive as "not joined", so
 * every invocation performed a **false leave**: the marker was cleared, and the next readable cycle
 * paid for a full re-join reconciliation (a device listing, an atomic ledger clear-and-seed, and a
 * discovery-cursor reset forcing a complete library re-enumeration). The marker never settled.
 *
 * This gate is consumed by [UploadCycle.run] — the choke point every trigger on every tier funnels
 * through — and **not** by a composition root. A root that reaches this decision itself reaches it for
 * whichever tiers its author enumerated: the OS-invoked tier had this gate and the app-driven tier did
 * not, for the same reason the re-join reconciliation and the direction gate each reached one tier and
 * not the other. A root supplies the reads; the cycle decides.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access` (the three-state read),
 * `changes/archive/…-fix-upload-config-gate` (moving it to the choke point).
 */
sealed interface CycleGate {

    /**
     * A required input could not be read. Touch **nothing**: no reconcile, no marker clear, no jobs.
     * Retry later.
     *
     * [detail] is the root's forensics — which read failed, and with what status. The decision is made
     * in shared code that cannot see either, and an unreadable membership is invisible on a device
     * except through this line: nothing else distinguishes "we skipped, correctly" from "we did
     * nothing, wrongly". The root supplies it; the cycle logs it verbatim, so it stays one line in one
     * file (`debug.log` is the canonical un-redacted channel for exactly this).
     */
    data class Skip(val detail: String) : CycleGate

    /** There is definitively no event configured (or no baked host): reconcile the leave side, upload nothing. */
    data object NotJoined : CycleGate

    /** Joined and configured: run the cycle. */
    data class Run(val config: UploadConfig, val membership: JoinedMembership) : CycleGate
}

/**
 * Decide what this invocation does. [configReadable] is `false` **only** when a required read failed
 * (protected data unavailable) — never when the config is merely absent or undecodable, both of which
 * are genuine [CycleGate.NotJoined] states that a later retry cannot improve.
 *
 * [configReadable] covers **every** protected read the cycle needs, not just the config: resolving the
 * device identity fails the same way (both are Keychain items) and every outcome below needs it — the
 * reconciler and the manifest producer each close over it, so even the leave-side branch touches it. An
 * unresolvable identity is "I could not look", never "no identity" (the Keychain-backed identity never
 * reports absence — an absent item mints), so it belongs on this side of the roll-up rather than in a
 * fourth state.
 *
 * Pure and platform-free, so the skip-or-leave-or-run decision is unit-tested off-device while each
 * root stays trivial glue.
 *
 * Note a missing/blank [host] — a build misconfiguration, not a leave — still yields [NotJoined], as
 * it always has. Untangling that is a separate concern from this one.
 */
fun cycleGate(
    configReadable: Boolean,
    membership: JoinedMembership?,
    host: String?,
    skipDetail: String = "",
): CycleGate {
    if (!configReadable) return CycleGate.Skip(skipDetail)
    if (membership == null) return CycleGate.NotJoined
    val config = buildUploadConfig(membership.eventId, host) ?: return CycleGate.NotJoined
    return CycleGate.Run(config, membership)
}
