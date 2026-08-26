@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.ios.registry

import app.snapsync.model.RegistrationOutcome
import app.snapsync.model.registrationOutcome
import app.snapsync.ports.UploadExtensionRegistry
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Photos.PHPhotoLibrary

/**
 * The PhotoKit binding of [UploadExtensionRegistry] — **the only place in the repo that calls
 * `setUploadJobExtensionEnabled` or `isUploadJobExtensionEnabled`** (capability `ios-photokit-upload`).
 *
 * It moved here from `:app:ios`, which is wiring-only: an adapter is named for its technology and placed by
 * linkage, and only the app process ever registers. What the shell kept, and what this preserves exactly,
 * is that **every branch belongs to the tested classifier**: this reports the platform's three raw facts —
 * did the write return true, and if not, which error domain and code — and renders whatever
 * [registrationOutcome] decides, with the severity the outcome carries. Nothing here chooses a severity, a
 * message, or which codes are expected.
 *
 * ## Constructed only where the selector exists
 *
 * `setUploadJobExtensionEnabled` is an iOS 26.1 selector and the app deploys to min iOS 18, so calling it
 * below that traps as an unrecognized selector and takes the process with it. This class is therefore
 * constructed only where the OS-driven mechanism is constructed at all — the same structural containment
 * that has always applied to the OS-driven mechanism, unchanged by the move.
 */
internal class PhotoKitExtensionRegistry(private val log: Logger) : UploadExtensionRegistry {

    /**
     * Change the registration, and **report a failure instead of discarding it**.
     *
     * `setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`, and this call site
     * discarded both before the reporting requirement existed. That mattered because the failure it hid is
     * invisible and terminal: if enabling fails, the extension is never registered, the OS never launches
     * it, no upload cycle ever runs, and the screen sits at "Synchronization pending…" forever with nothing
     * in the log, on the screen, or in crash reporting to say why.
     *
     * One helper for both directions, deliberately: checking one but not the other would be a blind spot
     * chosen on purpose.
     *
     * The **disable's** return is also **evidence**, not just an error check: a disable that finds a record
     * returns `true` with no error, so the write distinguishes "there was a registration" from "there was
     * not" as a side effect of doing its job — which the read-back cannot reliably do, being
     * grant-dependent.
     */
    override suspend fun setEnabled(enabled: Boolean): RegistrationOutcome = memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val ok = PHPhotoLibrary.sharedPhotoLibrary()
            .setUploadJobExtensionEnabled(enabled, error = errorVar.ptr)
        val error = errorVar.value
        val outcome = registrationOutcome(
            enabling = enabled,
            ok = ok,
            errorDomain = error?.domain,
            errorCode = error?.code,
        )
        // No branch: the outcome carries Kermit's own severity, so this renders without deciding. An
        // `Error` here is what `crash-reporting` carries onward as field telemetry.
        log.log(outcome.severity, log.tag, null, outcome.message)
        outcome
    }

    /**
     * The OS's own view. Never `null` here: this class exists only where the selector does, so "the
     * question does not apply" is answered by not constructing it at all rather than by a runtime check.
     */
    override fun isEnabled(): Boolean? = PHPhotoLibrary.sharedPhotoLibrary().isUploadJobExtensionEnabled()
}
