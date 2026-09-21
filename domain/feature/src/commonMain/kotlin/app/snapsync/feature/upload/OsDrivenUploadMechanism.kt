package app.snapsync.feature.upload

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
 * [LedgerStore] for the repair — so it names no platform API at all.
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
 * its next cycle, gated by its `joinedEventId` marker (`upload-state-reconciliation`).
 */
class OsDrivenUploadMechanism(
    private val ledgerStore: LedgerStore,
    private val registry: UploadExtensionRegistry,
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
     * Between the disable and the enable it **repairs** the `REQUESTED` rows the disable orphaned, demoting
     * them to `DISCOVERED` (see the body). This is the only place this mechanism touches the ledger.
     *
     * This ritual is **specific to this tier**: it exists to fix an OS registration record. The app-driven
     * tier has no such record, which is why applying this shape to it — the tier-blind
     * `enableBackgroundUpload()` this producer replaces — resolved to a destructive teardown followed by a
     * no-op.
     */
    override suspend fun start() = log.invocation(logScope, "photokit.start") {
        registry.setEnabled(false)
        // THE REPAIR (capability `ios-photokit-upload`, "Re-registering the extension demotes orphaned REQUESTED
        // rows"). The disable above wiped every in-flight OS job and no API surfaces a vanished one, so their
        // `REQUESTED` rows would never move again. Every `REQUESTED` row is unsettleable right now: this tier's
        // jobs are gone, and wherever the app-driven mechanism exists its `stop()` ran before this start. So the
        // whole set is demoted — through the reset family, because on this tier the extension is the one
        // recording process — and a `DISCOVERED` row returns through the ledger's work read with no walk.
        //
        // Awaited, off-main: it completes BEFORE the re-enable below, so a row the re-registered extension
        // records can never be demoted by a repair still running.
        demoteRequestedOffMain({ ledgerStore.demoteRequested() }, log = log) // Boolean; the seam returns Unit
        // The outcome IS the report. There used to be an `Info` line here claiming the extension had been
        // re-registered, logged unconditionally — so a device whose enable had just failed terminally at
        // `Error` also carried a plain statement that it had succeeded, in the one capability whose stated
        // failure mode is that "nothing else will report it". Both halves of that claim were already made
        // by the code that performed them: the enable by its own outcome, the repair by its own lines.
        //
        // Deleted rather than made conditional, and the shell gate is what forces that: this module is held
        // at `CyclomaticComplexMethod` threshold 2, so a branch on the outcome is a decision it may not
        // hold. `RegistrationOutcome` carries its own severity and message precisely so the shell renders
        // without deciding — a shell that asserts is a shell that decided.
        registry.setEnabled(true)
        Unit
    }

    /**
     * Deregister the extension — **and nothing else**, on a leave and on a relinquish to the app-driven
     * mechanism alike (capability `upload-lifecycle`).
     *
     * The disable wipes every in-flight OS job, and this deliberately repairs none of the `REQUESTED` rows it
     * leaves: the repair belongs to whichever mechanism **starts** next, the one moment it is known that no
     * other transfer is carrying those rows. On a leave nothing uploads until a start; on a relinquish the
     * app-driven mechanism's start repairs them. That is also why there is no narrower hand-off verb: this
     * `stop()` used to carry a ledger-wide delete and a cursor reset a hand-off had to avoid, so a separate
     * `deregister()` existed — with the repair moved into [start], the two were the same call.
     */
    override suspend fun stop() = log.invocation(logScope, "photokit.stop") {
        registry.setEnabled(false)
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
}
