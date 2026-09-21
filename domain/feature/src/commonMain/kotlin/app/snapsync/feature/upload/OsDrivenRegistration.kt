package app.snapsync.feature.upload

import app.snapsync.ports.LedgerStore
import app.snapsync.ports.UploadExtensionRegistry
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * **The OS-driven mechanism's registration** (iOS ≥26.1) — the one thing the app does for the tier whose uploads
 * the system performs: register the background-upload extension so the OS can invoke it, and deregister it
 * (capability `ios-photokit-upload`). Which of those a membership transition needs is `UploadTransitions`'
 * decision (capability `upload-lifecycle`); this class only performs them, correctly.
 *
 * Named for the need rather than the technology, because it lives in the platform-free core. It reaches the
 * platform through two ports — [UploadExtensionRegistry] for the registration record, and [LedgerStore] for the
 * repair — so it names no platform API at all, and the ritual and its repair are ordinary tested code.
 *
 * Constructed **only** where the OS carries this mechanism at all: below iOS 26.1 the registration selector does
 * not exist, and the adapter behind [UploadExtensionRegistry] would trap.
 *
 * It receives no app-side trigger. It used to, as `OsDrivenUploadMechanism`, and declined all four one by one;
 * triggers now go to the app engine, whose entry gate declines while this mechanism is the resolved one.
 *
 * The app performs no upload or enumeration on this tier. It does touch the ledger at membership transitions —
 * the join-time load and the leave's clear, through the store's reset family, which a holder without the
 * `LedgerWriter` may invoke (`sync-ledger`) — and here, in the repair between disable and enable.
 */
interface ExtensionRegistration {
    /** Register the extension through the disable → demote → enable ritual — never a bare enable. */
    suspend fun register()

    /** Deregister the extension — the disable alone; it repairs nothing. */
    suspend fun deregister()

    /**
     * The OS's own view, or `null` where the platform has no notion of it. **Grant-dependent**: it read `false`
     * under `NOT_DETERMINED` for a live record (SE2 / iOS 26.6), so a caller trusts it only under `GRANTED`.
     */
    fun isRegistered(): Boolean?
}

/** [ExtensionRegistration] over the registration and ledger ports. */
class OsDrivenRegistration(
    private val ledgerStore: LedgerStore,
    private val registry: UploadExtensionRegistry,
    private val log: Logger = Logger.withTag("OsDrivenRegistration"),
    private val logScope: LogScope = LogScope.NoOp,
) : ExtensionRegistration {
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
    override suspend fun register() = log.invocation(logScope, "photokit.register") {
        registry.setEnabled(false)
        // THE REPAIR (capability `ios-photokit-upload`, "Re-registering the extension demotes orphaned REQUESTED
        // rows"). The disable above wiped every in-flight OS job and no API surfaces a vanished one, so their
        // `REQUESTED` rows would never move again. Every `REQUESTED` row is unsettleable right now: this tier's
        // jobs are gone, and wherever the app-driven engine exists the transition disarmed it first. So the
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
     * Deregister the extension — **and nothing else**, on a leave, a download-only join, and a pin away from this
     * mechanism alike (capability `upload-lifecycle`).
     *
     * The disable wipes every in-flight OS job, and this deliberately repairs none of the `REQUESTED` rows it
     * leaves: the repair belongs to whichever mechanism is **brought up** next, the one moment it is known that no
     * other transfer is carrying those rows. On a leave nothing uploads until then; where the app engine is armed
     * instead, its restart rule demotes them.
     */
    override suspend fun deregister() = log.invocation(logScope, "photokit.deregister") {
        registry.setEnabled(false)
        Unit
    }

    override fun isRegistered(): Boolean? = registry.isEnabled()
}
