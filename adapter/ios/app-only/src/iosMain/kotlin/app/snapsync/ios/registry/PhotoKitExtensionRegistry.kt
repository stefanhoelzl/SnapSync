@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.ios.registry

import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.checkedObjC
import app.snapsync.ports.ExtensionRegistry
import kotlinx.cinterop.cValue
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Photos.PHPhotoLibrary

/**
 * The PhotoKit binding of [ExtensionRegistry] — **the only place in the repo that calls
 * `setUploadJobExtensionEnabled` or `isUploadJobExtensionEnabled`** (capability `background-upload`).
 *
 * It reports the platform's three raw facts — did the write return true, and if not, which error domain and code —
 * and nothing else: what they mean is `model/registrationOutcome`, and the line that reports them is the feature's
 * `OsDrivenRegistration` (phase 11f moved it out of here).
 *
 * ## Present on every OS; asks only where the selector exists
 *
 * `setUploadJobExtensionEnabled` is an iOS 26.1 selector and the app deploys to min iOS 18, so calling it below that
 * traps as an unrecognized selector and takes the process with it. The check is [supported], made here once per
 * process: below 26.1 every call answers `Unsupported` and asks the OS nothing, so the root constructs this on every
 * OS and holds no `if` (phase 11f — before it, the root left the adapter unconstructed there).
 */
internal class PhotoKitExtensionRegistry(
    private val log: Logger,
    private val api: ExtensionRegistrationApi = SystemExtensionRegistrationApi,
    /** Whether this OS carries the selector. The device's, unless a replay supplies the recording's (it did). */
    private val supported: Boolean = osCarriesUploadExtension(),
) : ExtensionRegistry {

    /**
     * Change the registration, and **report a failure instead of discarding it**.
     *
     * `setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`; both are carried out. The
     * **disable's** return is also **evidence**: a disable that finds a record returns `true` with no error, so the
     * write distinguishes "there was a registration" from "there was not" — which the read-back cannot reliably do,
     * being grant-dependent.
     */
    override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
        if (!supported) return RegistrationAnswer.Unsupported
        val answer = api.setEnabled(enabled)
        log.d { "setUploadJobExtensionEnabled($enabled) -> ok=${answer.ok} ${answer.errorDomain}:${answer.errorCode}" }
        return RegistrationAnswer.Answered(answer.ok, answer.errorDomain, answer.errorCode)
    }

    /**
     * The OS's own view.
     *
     * Measured (SE2, iOS 26.6): it answered `false` under `NOT_DETERMINED` for a live record that had survived a
     * reinstall, and `true` once access was granted — the record is keyed by bundle id and persists across
     * delete/reinstall and reboot, which is why a bare enable over a stale one fails `3202`. The contract asserts
     * the read-back only under a full grant; the other cells (`NOT_DETERMINED`, `LIMITED`, a differently-signed
     * build's record) are unasserted. See changes/archive/2026-09-22-both-uploaders-active.
     */
    override fun isEnabled(): RegistrationState = when {
        !supported -> RegistrationState.UNSUPPORTED
        api.isEnabled() -> RegistrationState.REGISTERED
        else -> RegistrationState.NOT_REGISTERED
    }
}

/** Whether this OS carries the iOS 26.1 upload-extension registration selector. */
@OptIn(ExperimentalForeignApi::class)
internal fun osCarriesUploadExtension(): Boolean =
    NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
        cValue<NSOperatingSystemVersion> {
            majorVersion = UPLOAD_EXTENSION_MAJOR
            minorVersion = UPLOAD_EXTENSION_MINOR
            patchVersion = 0
        },
    )

private const val UPLOAD_EXTENSION_MAJOR = 26L
private const val UPLOAD_EXTENSION_MINOR = 1L

/** The three facts a registration write returns: whether it took, and if not, which error. */
internal class PlatformWriteAnswer(val ok: Boolean, val errorDomain: String?, val errorCode: Long?)

/**
 * The two `PHPhotoLibrary` calls [PhotoKitExtensionRegistry] makes, as a seam in this module (capability
 * `docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary and replayed on every
 * build"): the device run records every call and iOS's answer through it, and every CI build replays that
 * recording against the current adapter. Production binds [SystemExtensionRegistrationApi]; nothing else does.
 */
internal interface ExtensionRegistrationApi {
    fun setEnabled(enabled: Boolean): PlatformWriteAnswer
    fun isEnabled(): Boolean
}

/** The real calls. */
internal object SystemExtensionRegistrationApi : ExtensionRegistrationApi {
    override fun setEnabled(enabled: Boolean): PlatformWriteAnswer {
        val write = checkedObjC("setUploadJobExtensionEnabled") {
            PHPhotoLibrary.sharedPhotoLibrary().setUploadJobExtensionEnabled(enabled, error = it)
        }
        val error = write.exceptionOrNull() as ObjCFailure?
        return PlatformWriteAnswer(write.isSuccess, error?.domain, error?.code)
    }

    override fun isEnabled(): Boolean = PHPhotoLibrary.sharedPhotoLibrary().isUploadJobExtensionEnabled()
}
