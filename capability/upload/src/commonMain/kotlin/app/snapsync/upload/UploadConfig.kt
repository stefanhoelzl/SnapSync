package app.snapsync.upload

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
 * What one invocation should do — the **three-way** gate, because "the config could not be read" is
 * not "no event is configured".
 *
 * The distinction is load-bearing: [NotJoined] runs the leave-side reconciliation, which **clears the
 * persisted `joinedEventId` marker** (capability `event-rejoin-reconciliation`). The upload extension
 * is invoked by the OS when the device is idle — which usually means *locked* — and a locked device
 * could not read the Keychain at all before the accessibility fix. That read failure used to arrive
 * as "not joined", so every OS-scheduled invocation performed a **false leave**: the marker was
 * cleared, and the next readable cycle paid for a full re-join reconciliation (a device listing, an
 * atomic ledger clear-and-seed, and a discovery-cursor reset forcing a complete library
 * re-enumeration). The marker never settled.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
sealed interface CycleGate {

    /** The config could not be read. Touch **nothing**: no reconcile, no marker clear, no jobs. Retry later. */
    data object Skip : CycleGate

    /** There is definitively no event configured (or no baked host): reconcile the leave side, upload nothing. */
    data object NotJoined : CycleGate

    /** Joined and configured: run the cycle. */
    data class Run(val config: UploadConfig) : CycleGate
}

/**
 * Decide what this invocation does. [configReadable] is `false` **only** when the persisted config
 * could not be read (protected data unavailable) — never when it is merely absent or undecodable,
 * both of which are genuine [CycleGate.NotJoined] states that a later retry cannot improve.
 *
 * Pure and platform-free (this module stays event- and platform-agnostic: it takes an `eventId`
 * string, not the config type), so the skip-or-leave-or-run decision is unit-tested off-device while
 * the iOS root stays trivial glue.
 *
 * Note a missing/blank [host] — a build misconfiguration, not a leave — still yields [NotJoined], as
 * it always has. Untangling that is a separate concern from this one.
 */
fun cycleGate(configReadable: Boolean, eventId: String?, host: String?): CycleGate {
    if (!configReadable) return CycleGate.Skip
    return buildUploadConfig(eventId, host)?.let(CycleGate::Run) ?: CycleGate.NotJoined
}
