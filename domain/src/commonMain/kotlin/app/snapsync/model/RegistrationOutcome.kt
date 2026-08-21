package app.snapsync.model

import co.touchlab.kermit.Severity

/**
 * What a change to the OS-driven upload-job registration actually did (capability `ios-photokit-upload`).
 *
 * This is a **decision**, so it lives here and is tested on JVM and the simulator, rather than in the
 * adapter that makes the call — `:app:ios` is wiring-only and the shell gate enforces it. The adapter
 * reports the platform's three raw facts (did it return true, and if not, which error) and renders whatever
 * comes back; every branch is here.
 */
sealed interface RegistrationOutcome {

    /**
     * The severity to log at — Kermit's own type, not a private enum the adapter would have to translate.
     *
     * That choice is what keeps the adapter branch-free: a private enum would need a `when` at the call
     * site to map it, and the call site is in `:app:ios`, where the shell gate counts that as a decision.
     * `Logger.log(severity, …)` takes this directly, so the shell renders without deciding anything.
     */
    val severity: Severity

    /** The line to log, already carrying its own explanation. */
    val message: String

    /**
     * The change took effect.
     *
     * A **disable** landing here is evidence and not just an absence of failure: it means a configuration
     * record existed to be removed. That is the one reliable way to learn whether this device was
     * registered — the read-back (`isUploadJobExtensionEnabled`) is grant-dependent and answers `false` for
     * a live record when photo access is not granted.
     */
    data class Applied(val enabling: Boolean) : RegistrationOutcome {
        override val severity = Severity.Info
        override val message =
            if (enabling) {
                "extension enable succeeded"
            } else {
                "extension disable succeeded — a configuration record existed and was removed"
            }
    }

    /**
     * A **disable** that found nothing to disable, which is the expected state of any clean device.
     *
     * `start()` is a disable→enable ritual, so its leading disable runs against no record on a fresh
     * install and fails with `PHPhotosErrorIdentifierNotFound` (3201) — measured twice on an SE2 (iOS
     * 26.6). Reporting this as an error would put an event on the first join of **every** fresh install,
     * which would bury the failures this reporting exists to surface under noise it created itself.
     */
    data object NothingToDisable : RegistrationOutcome {
        override val severity = Severity.Debug
        override val message = "extension disable found no configuration record (3201) — expected on a clean device"
    }

    /**
     * The change did not take effect, and the consequence is invisible without this line.
     *
     * A failed **enable** means the extension is never registered, so the OS never launches it, no upload
     * cycle ever runs, and the screen sits at "Synchronization pending…" indefinitely with nothing
     * anywhere to say why. That is why this is `ERROR`: `crash-reporting` routes `Error`-severity lines
     * onward, making a failure knowable without attaching to the device.
     */
    data class Failed(val enabling: Boolean, val domain: String?, val code: Long?) : RegistrationOutcome {
        override val severity = Severity.Error
        override val message =
            "extension ${if (enabling) "enable" else "disable"} FAILED: " +
                "${domain ?: "no domain"}:${code?.toString() ?: "no code"} — the extension is not in the " +
                "state the app believes; uploads will not run and nothing else will report it"
    }
}

/**
 * The `PHPhotosError` code for "no such configuration record" (`PHPhotosErrorIdentifierNotFound`).
 *
 * Named from the Kotlin/Native platform klib rather than from the error's rendered description, which reads
 * "Unable to find the configuration" for this API's use of it.
 */
const val PHOTOS_ERROR_IDENTIFIER_NOT_FOUND: Long = 3201

/**
 * Classify a registration change from the three facts the platform returns.
 *
 * [errorCode] is consulted **only** for a failing disable, deliberately: the same code arriving from an
 * *enable* is not "nothing to do", it is an enable that did not happen, and collapsing the two would hide
 * exactly the failure this classification exists to surface.
 */
fun registrationOutcome(
    enabling: Boolean,
    ok: Boolean,
    errorDomain: String?,
    errorCode: Long?,
): RegistrationOutcome = when {
    ok -> RegistrationOutcome.Applied(enabling)
    !enabling && errorCode == PHOTOS_ERROR_IDENTIFIER_NOT_FOUND -> RegistrationOutcome.NothingToDisable
    else -> RegistrationOutcome.Failed(enabling, errorDomain, errorCode)
}
