package app.snapsync.feature.upload

import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.UploadExtensionRegistry
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * **The OS-driven upload mechanism** (iOS ≥26.1) — the tier whose uploads the system performs, driven by
 * registering a background-upload extension (capability `ios-photokit-upload`; the lifecycle seam is
 * `upload-lifecycle`).
 *
 * Named for the need rather than the technology, because it lives in the platform-free core: what this
 * mechanism *is* is "the one where the OS does the uploading", and PhotoKit is merely how iOS spells that.
 * It reaches the platform through two ports — [UploadExtensionRegistry] for the registration record, and
 * [DiscoveryStore] for the cursor — so it names no platform API at all.
 *
 * It used to be `PhotoKitUploadProducer` in `:app:ios`, which is wiring-only and **untested by rule**. That
 * placement is what made the ritual below unverifiable anywhere: the two things it exists to get right —
 * the disable→enable toggle, and the repair its disable makes necessary — could only be exercised by
 * contriving a real device into the state they defend against. Here they are ordinary tested code.
 *
 * Constructed **only** where the OS carries this mechanism at all: below iOS 26.1 the registration selector
 * does not exist, and the adapter behind [UploadExtensionRegistry] would trap. That containment is
 * structural — the composition never builds this object there — rather than a runtime guard, and it is why
 * the two tiers are mutually exclusive by construction: the arm holds one mechanism reference, so starting
 * two has no expression (two `LedgerWriter`s over one App-Group ledger would breach `sync-ledger`'s
 * single-record-writer invariant).
 *
 * The app performs no upload, fetch, enumeration, or seed on this tier: the extension self-reconciles on
 * its next cycle, gated by its `joinedEventId` marker (`event-rejoin-reconciliation`).
 */
class OsDrivenUploadMechanism(
    private val ledgerStore: LedgerStore,
    private val registry: UploadExtensionRegistry,
    private val discoveryStore: DiscoveryStore,
    private val log: Logger = Logger.withTag("OsDrivenUploadMechanism"),
    private val logScope: LogScope = LogScope.NoOp,
) : UploadMechanismRuntime {

    /**
     * Register the extension — a **disable→enable toggle**, not a bare enable.
     *
     * The system's upload-job configuration record is keyed by bundle id and survives app
     * delete/reinstall and reboot, so a stale record (e.g. from a prior or differently-signed build) makes
     * a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which
     * the OS never launches the extension. The leading `enable(false)` deletes the stale record so
     * `enable(true)` re-creates it cleanly — and the re-register is what reliably prompts the OS to
     * schedule `process()`. Idempotent-safe to repeat.
     *
     * This ritual is **specific to this tier**: it exists to fix an OS registration record. The app-driven
     * tier has no such record, which is why applying this shape to it — the tier-blind
     * `enableBackgroundUpload()` this producer replaces — resolved to a destructive teardown followed by a
     * no-op.
     */
    override suspend fun start() = log.invocation(logScope, "photokit.start") {
        stop() // awaited: the off-main REQUESTED clear completes BEFORE the re-enable below
        // The outcome IS the report. There used to be an `Info` line here claiming the extension had been
        // re-registered, logged unconditionally — so a device whose enable had just failed terminally at
        // `Error` also carried a plain statement that it had succeeded, in the one capability whose stated
        // failure mode is that "nothing else will report it". Both halves of that claim were already made
        // by the code that performed them: the enable by its own outcome, the REQUESTED clear by the clear.
        //
        // Deleted rather than made conditional, and the shell gate is what forces that: this module is held
        // at `CyclomaticComplexMethod` threshold 2, so a branch on the outcome is a decision it may not
        // hold. `RegistrationOutcome` carries its own severity and message precisely so the shell renders
        // without deciding — a shell that asserts is a shell that decided.
        registry.setEnabled(true)
        Unit
    }

    /**
     * Deregister the extension AND recover the jobs the disable wipes (capability `ios-photokit-upload`).
     * `setUploadJobExtensionEnabled(false)` deletes the OS upload-job configuration, wiping every in-flight
     * job. Two clears make that recoverable:
     *
     * - `clearRequested()` — drop the now-orphaned `REQUESTED` rows. The engine never re-issues a
     *   `REQUESTED` key and no API surfaces the vanished job, so without this they stay `REQUESTED`
     *   forever. Awaited **off-main with a bounded retry** so it completes before any re-enable (a
     *   fire-and-forget clear raced the immediate re-enable and could delete the re-enabled extension's
     *   fresh rows).
     * - reset the discovery cursor — `clearRequested` only makes the keys ABSENT; a settled cursor would
     *   scan incrementally and never re-surface them, so force a full re-enumeration next cycle.
     *
     * Both are **repairs for damage this tier's OS disable causes**, not lifecycle intent — which is why
     * they have no counterpart on the app-driven tier (whose `stop()` cancels transfers and nothing else:
     * a background `URLSession` can enumerate its tasks, so stranded rows are reconciled precisely).
     *
     * `COMPLETED` rows are untouched, so stored files never re-upload. This destroys no dedup state.
     */
    override suspend fun stop() = log.invocation(logScope, "photokit.stop") {
        registry.setEnabled(false)
        // Through the port, not a second raw `NSUserDefaults` write. `DiscoveryStore.clearToken()` is the
        // same key in the same App-Group suite, and open-coding it here meant the cursor had two writers —
        // one of which no test, fake or harness could observe or substitute.
        discoveryStore.clearToken()
        clearRequestedOffMain({ ledgerStore.clearRequested() }, log = log) // Boolean; the seam returns Unit
        Unit
    }

    // ---- triggers: this mechanism is scheduled by the OS, so every app-side kick is declined ----------
    //
    // Stated here, one by one, rather than inherited from a default. `upload-lifecycle` forbids the
    // permissive-default shape on exactly this kind of seam ("a permissive default on such a port is an
    // unstated answer"), and the reason bites hardest for a mechanism whose right answer is "nothing":
    // an inherited blank and a forgotten override are the same diff.

    /** The OS owns scheduling here. An app-side pump would add nothing, and under anything less than a
     *  full grant the OS never invokes this mechanism at all (`ios-photokit-upload`). */
    override suspend fun onForeground() = Unit

    /** Declined for the same reason, and additionally: a cycle here would be the extension's, in the
     *  other process. The app cannot drive it and must not pretend to. */
    override suspend fun onSilentPush(eventId: String) = Unit

    /** This tier arms no `BGProcessingTask` heartbeat — the OS schedules `process()` itself — so a
     *  heartbeat reaching this mechanism has nothing to top up. The entry point still releases its
     *  handler, which is the half that matters. */
    override suspend fun onBackgroundTask() = Unit

    /** A selection change is a partial-grant signal, and this mechanism is never the resolved one under a
     *  partial grant. Reachable only if it were pinned there deliberately, where declining is correct. */
    override suspend fun onSelectionChanged() = Unit

    /**
     * Deregister the extension and **nothing else** — the narrow verb the tier switch takes.
     *
     * [stop] means "deregister **and** repair the jobs the disable vanished". That pairing is right when
     * this tier runs again afterwards (its re-register, and the leave path), and wrong when the disable is
     * a hand-off to the app-driven mechanism: `clearRequested()` is ledger-wide and the discovery cursor is
     * shared, so the repair would delete in-flight rows belonging to the mechanism about to start, and force
     * it into a full re-enumeration it does not need. That mechanism reconciles stranded rows precisely from
     * `getAllTasks` and by contract "SHALL NOT depend on `clearRequested`" (`ios-url-session-upload`).
     *
     * So the repair is not dropped, it is *scoped*: it belongs to re-registering this tier, where no API can
     * enumerate the vanished jobs, and it stays on [stop] for the leave path where nothing runs afterwards.
     * This method exists because the two-verb lifecycle seam has no room for a third verb, and should not
     * gain one — the composition site binds this as `RelinquishThenRun`'s relinquish lambda instead.
     */
    suspend fun deregister() = log.invocation(logScope, "photokit.deregister") {
        registry.setEnabled(false)
        Unit
    }
}
