package app.snapsync.ports

import app.snapsync.model.RegistrationOutcome

/**
 * **The OS's record of whether this app's background-upload extension is registered** (capability
 * `ios-photokit-upload`).
 *
 * Named for the need, not the technology: what a caller wants is to change and to read the system's
 * registration for this app, and the iOS binding of that need happens to be
 * `PHPhotoLibrary.setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled`.
 *
 * ## Why this is a port at all
 *
 * The registration change was a raw `PHPhotoLibrary` call made from `:app:ios`, which is **wiring-only**
 * and gated at `CyclomaticComplexMethod` threshold 2. That gate is what kept the call honest — it could
 * report the platform's raw facts but could hold no decision about them — and it is also what made the
 * ritual untestable anywhere: the shell has no tests by rule, and nothing else could reach the call.
 *
 * Behind a port, the disable→enable ritual, its `stop()` repair, and every arm of
 * [app.snapsync.model.registrationOutcome] become executable on any host that can implement two methods,
 * including JVM. The ports law asked for this independently: *"anything touching an external system goes
 * through a port interface in `ports/`, named for the need; adapters implement, named for technology,
 * placed by linkage"*.
 *
 * ## Placement
 *
 * Only the **app** process registers — the extension is the thing being registered, and it cannot enable
 * itself — so the iOS adapter is placed by that linkage, in `:adapter:ios:app-only`.
 *
 * ## The record is not ours
 *
 * The system's configuration record is keyed by bundle id and **survives app delete/reinstall and device
 * reboot**. So this port reads and writes state this repo does not own and cannot reset, which is why the
 * ritual is a disable→enable rather than a bare enable, and why [isEnabled] can disagree with what the app
 * last wrote.
 */
interface UploadExtensionRegistry {

    /**
     * Change the registration, and report **what the platform actually did**.
     *
     * Returns rather than throws, and returns a classified [RegistrationOutcome] rather than a boolean,
     * because the interesting cases are neither "worked" nor "threw": a disable that finds no record is the
     * expected state of a clean device, a refusal under a partial photo grant is routine and self-healing,
     * and a failing enable is invisible and terminal. Collapsing those into a boolean is what left the
     * terminal one unreported for as long as it was.
     *
     * The outcome carries its own severity and message so a wiring-only caller can render it without
     * deciding anything.
     */
    suspend fun setEnabled(enabled: Boolean): RegistrationOutcome

    /**
     * The OS's own view of the registration, or `null` where this platform has no such notion.
     *
     * Three-valued, and both absences are measured rather than defensive. `null` means the question does
     * not apply — on iOS the read is a 26.1 selector, and calling it below that traps as an unrecognized
     * selector, so a bare `false` there would state "not registered" about an OS on which registration
     * could never occur.
     *
     * A `false` is also **grant-dependent** and must not be read as "there is no record": measured on an
     * SE2 (iOS 26.6), the OS answered `false` under `NOT_DETERMINED` photo access for a record that was
     * live in that same install, then `true` for the same record once access was granted. So a `false`
     * collapses "there is no record" with "I am not permitted to see one", and a caller that must tell
     * those apart reads it beside the current permission.
     *
     * Absence: `null` means **the platform has no notion of this registration**, and it absorbs exactly
     * one cause — an OS below the version that declares the selector. It cannot absorb "the read
     * failed", because there is no failing read: the composition constructs no adapter where the
     * selector is absent, so this `null` is produced by the caller having nothing to ask rather than by
     * an answer being unavailable. That is what makes the collapse safe, and what would stop making it
     * safe if a second cause were ever added — a read that can fail needs a type keeping "nothing" and
     * "could not tell" apart, as `SecureStoreRead` does.
     */
    fun isEnabled(): Boolean?
}
