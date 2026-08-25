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
     * A **disable** the platform refused because this process holds a partial (`.limited`) photo grant.
     *
     * Under a partial grant iOS refuses `setUploadJobExtensionEnabled` outright, in both directions, with
     * `PHPhotosErrorAccessUserDenied` (3311) — measured on an SE2 (iOS 26.6). Switching Photos to Limited
     * Access is a supported user action in a capability built on partial grants being first-class, and the
     * arm attempts this disable on every membership-lifecycle action taken while that grant is held. At
     * `Error` it would put a reporting event on each of them, which is the self-inflicted noise the 3201
     * carve-out above already exists to prevent.
     *
     * `Warn` rather than `Debug`, and the difference is measured rather than stylistic: a real diagnostic
     * dump carries thousands of `Info` and `Debug` lines and roughly two dozen `Warn`, so `Warn` is the one
     * band a reader can scan when asking why a limited-grant device is not uploading. It stays a
     * breadcrumb, never an event.
     *
     * The record **survives** the refusal — proven by the write's own return on the next full grant, since
     * the read-back is grant-dependent — and that is safe: the OS does not invoke the extension under a
     * partial grant, and a return to full re-registers through the ritual anyway. So the app's model of the
     * registration is knowingly wrong here, and harmlessly so; the line says both halves.
     */
    data object DisableRefusedByGrant : RegistrationOutcome {
        override val severity = Severity.Warn
        override val message =
            "extension disable refused under a partial photo grant (3311) — the configuration record " +
                "survives and is inert: the OS does not invoke the extension under this grant, and a " +
                "return to full access re-registers it"
    }

    /**
     * An **enable** the platform refused for the same reason — and the opposite consequence.
     *
     * A refused disable leaves an inert record and costs nothing. A refused enable means no record is
     * created, so the OS never launches the extension, no cycle ever runs, and the screen sits at
     * "Synchronization pending…" with nothing anywhere to say why. Reporting the two identically would
     * hide the terminal case behind the routine one, so this stays at `Error`.
     *
     * It is unreachable in a shipped build: resolution never yields the OS-driven mechanism under a partial
     * grant, so no enable is attempted there. It becomes reachable only when a development override pins
     * that mechanism — which is exactly the situation in which someone needs to be told the pin cannot
     * work, which is why this names the cause instead of falling into [Failed]'s generic wording.
     */
    data object EnableRefusedByGrant : RegistrationOutcome {
        override val severity = Severity.Error
        override val message =
            "extension enable REFUSED under a partial photo grant (3311) — the registration cannot be " +
                "created without full photo access, so the OS will never launch the extension and no " +
                "upload cycle will run"
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
 * The `PHPhotosError` code the platform returns when a **partial** (`.limited`) photo grant forbids the
 * operation outright (`PHPhotosErrorAccessUserDenied`).
 *
 * Named from the Kotlin/Native platform klib, like the constant above: iOS classifies a partial grant as
 * *access denied* for this API, which is Apple's own word for it and not an interpretation added here.
 */
const val PHOTOS_ERROR_ACCESS_USER_DENIED: Long = 3311

/**
 * Classify a registration change from the three facts the platform returns.
 *
 * **Both expected codes are split by direction, and for the same reason.** 3201 from an *enable* is not
 * "nothing to do", it is an enable that did not happen; 3311 from an *enable* is not the routine refusal a
 * limited member's disable produces, it is a registration that will never exist. In both cases the two
 * directions carry opposite consequences — one costs nothing, the other is invisible and terminal — so
 * collapsing either pair would hide precisely the failure this classification exists to surface.
 *
 * The expected set is **closed and measured**: a code earns a quiet arm only once a device measurement
 * shows it arising on an ordinary path (3201 on every clean device's first join; 3311 on every disable
 * attempted under a partial grant). Everything else lands on [RegistrationOutcome.Failed], loudly — which
 * is why no guard pins these constants: a code Apple re-values stops matching and reverts to the loud
 * answer, never to a quiet wrong one.
 */
fun registrationOutcome(
    enabling: Boolean,
    ok: Boolean,
    errorDomain: String?,
    errorCode: Long?,
): RegistrationOutcome = when {
    ok -> RegistrationOutcome.Applied(enabling)
    !enabling && errorCode == PHOTOS_ERROR_IDENTIFIER_NOT_FOUND -> RegistrationOutcome.NothingToDisable
    !enabling && errorCode == PHOTOS_ERROR_ACCESS_USER_DENIED -> RegistrationOutcome.DisableRefusedByGrant
    enabling && errorCode == PHOTOS_ERROR_ACCESS_USER_DENIED -> RegistrationOutcome.EnableRefusedByGrant
    else -> RegistrationOutcome.Failed(enabling, errorDomain, errorCode)
}
