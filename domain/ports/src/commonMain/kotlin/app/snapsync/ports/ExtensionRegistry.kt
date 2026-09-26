package app.snapsync.ports

import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState

/**
 * **The OS's record of whether this app's background-upload extension is registered** (capability
 * `background-upload`). One external system, deciding nothing: on iOS ≥26.1 `PHPhotoLibrary`'s
 * `setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled`.
 *
 * **Always present.** A platform without the mechanism — iOS below 26.1, whose selector would trap; the JVM; Android —
 * answers [RegistrationAnswer.Unsupported] and [RegistrationState.UNSUPPORTED] and asks the operating system nothing.
 * The version check lives inside the iOS adapter, so no root holds an `if` to leave the port unconstructed (phase
 * 11f).
 *
 * What an answer MEANS — the disable→enable ritual, which refusals are routine and which terminal — is
 * `model/registrationOutcome` and the feature's `OsDrivenRegistration`, both tested on every host.
 *
 * ## The record is not ours
 *
 * The system's configuration record is keyed by bundle id and **survives app delete/reinstall and device reboot**. So
 * this port reads and writes state this repo does not own and cannot reset, which is why the ritual is a
 * disable→enable rather than a bare enable, and why [isEnabled] can disagree with what the app last wrote.
 *
 * Only the **app** process registers — the extension is the thing being registered — so the iOS adapter is placed in
 * `:adapter:ios:app-only`.
 */
interface ExtensionRegistry {

    /** Change the registration, and report what the platform answered. Returns rather than throws. */
    suspend fun setEnabled(enabled: Boolean): RegistrationAnswer

    /**
     * The OS's own view of the registration. **Grant-dependent** on iOS: [RegistrationState.NOT_REGISTERED] collapses
     * "there is no record" with "I am not permitted to see one" (SE2, iOS 26.6: it read so under `NOT_DETERMINED` for a
     * live record), so a caller that must tell those apart reads it beside the current permission.
     */
    fun isEnabled(): RegistrationState
}
