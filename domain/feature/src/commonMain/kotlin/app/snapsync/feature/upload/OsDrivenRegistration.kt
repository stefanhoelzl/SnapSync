package app.snapsync.feature.upload

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
 * platform through one port — [UploadExtensionRegistry] for the registration record — so it names no platform
 * API at all, and the ritual is ordinary tested code.
 *
 * Constructed **only** where the OS carries this mechanism at all: below iOS 26.1 the registration selector does
 * not exist, and the adapter behind [UploadExtensionRegistry] would trap.
 *
 * It receives no app-side trigger: triggers go to the app's own uploader, which runs beside the extension
 * (decision record `changes/both-uploaders-active`). It touches no ledger row: a registration spans the whole
 * membership and is removed only at a leave, which clears the ledger, so no deregistration orphans a row that
 * would need repair.
 */
interface ExtensionRegistration {
    /** Register the extension through the disable → enable ritual — never a bare enable. */
    suspend fun register()

    /** Deregister the extension — the disable alone. */
    suspend fun deregister()

    /**
     * The OS's own view, or `null` where the platform has no notion of it. **Grant-dependent**: it read `false`
     * under `NOT_DETERMINED` for a live record (SE2 / iOS 26.6), so a caller trusts it only under `GRANTED`.
     */
    fun isRegistered(): Boolean?
}

/** [ExtensionRegistration] over the registration port. */
class OsDrivenRegistration(
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
     * It no longer repairs anything between the two: the disable wipes the record's in-flight OS jobs, and the
     * ritual runs only where none can be live — a join (after the share-set load replaced the ledger, and after a
     * switch's leave deregistered), or a compared register where the OS reads no record at all. A re-provision of
     * the joined event does not reach here (decision record `changes/both-uploaders-active`, D5).
     *
     * This ritual is **specific to this tier**: it exists to fix an OS registration record. The app-driven
     * tier has no such record, which is why applying this shape to it — the tier-blind
     * `enableBackgroundUpload()` this producer replaces — resolved to a destructive teardown followed by a
     * no-op.
     */
    override suspend fun register() = log.invocation(logScope, "photokit.register") {
        registry.setEnabled(false)
        // The outcome IS the report. There used to be an `Info` line here claiming the extension had been
        // re-registered, logged unconditionally — so a device whose enable had just failed terminally at
        // `Error` also carried a plain statement that it had succeeded, in the one capability whose stated
        // failure mode is that "nothing else will report it". The enable reports itself through its own outcome.
        //
        // Deleted rather than made conditional, and the shell gate is what forces that: this module is held
        // at `CyclomaticComplexMethod` threshold 2, so a branch on the outcome is a decision it may not
        // hold. `RegistrationOutcome` carries its own severity and message precisely so the shell renders
        // without deciding — a shell that asserts is a shell that decided.
        registry.setEnabled(true)
        Unit
    }

    /**
     * Deregister the extension — **and nothing else**: at a leave (a switch leaves first), or the rig's
     * `extension=off` (capability `upload-lifecycle`). The disable wipes every in-flight OS job; at a leave the
     * ledger is cleared right after, and on the rig path the wipe is the test's intent.
     */
    override suspend fun deregister() = log.invocation(logScope, "photokit.deregister") {
        registry.setEnabled(false)
        Unit
    }

    override fun isRegistered(): Boolean? = registry.isEnabled()
}
