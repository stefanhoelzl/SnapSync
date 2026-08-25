package app.snapsync.model

/**
 * Which upload mechanism a process runs (capability `upload-lifecycle`, "The upload mechanism is
 * resolved, never selected").
 *
 * - [PHOTOKIT] — the OS-driven `PHBackgroundResourceUploadExtension` registration (iOS ≥26.1).
 * - [URL_SESSION] — the in-app background `URLSession` pump.
 * - [IDLE] — no mechanism runs. **This is a member, not an absence**: an OS trigger carries a
 *   completion handler the system waits on, and routing one to "nothing" strands it, which costs the
 *   app its future background wakes (`ios-app-shell`). [IDLE] declines every trigger *and still
 *   answers the platform*, which is the deliberate collapse "Absence is never silent" requires.
 */
enum class UploadMechanism {
    PHOTOKIT,
    URL_SESSION,
    IDLE,
    ;

    /** How a diagnostic dump names this mechanism (capability `diagnostic-logging`) — lowercase, stable. */
    val diagnosticName: String get() = name.lowercase()
}

/**
 * The pure upload-mechanism resolver (spec `module-architecture`, "One shared composition"; decision
 * record `establish-target-architecture` D5, superseding the `CompositionMode` resolver it absorbed).
 *
 * **Total, and re-evaluated rather than resolved once.** It replaces `resolveComposition`, which took one
 * OS fact and ran once per process. That was correct while the mechanism was a function of the device
 * alone; it is not, because the OS never invokes the upload extension under a partial photo grant
 * (measured — `ios-photokit-upload`), so the mechanism genuinely changes when permission does. A
 * once-per-process decision cannot express that, and the previous design's answer — compose both
 * mechanisms and pick between them at each transition — is what let a process hold a mechanism it must
 * not run with no route to that mechanism's own teardown. Re-resolving expresses the runtime input
 * *and* keeps exactly one mechanism nameable at a time.
 *
 * **On the [override].** `resolveComposition` documented the absence of any developer input as a virtue:
 * *"no launch-environment variable, no build property, no runtime override"*. That is no longer true, and
 * the reason it was written is worth preserving rather than deleting. What a reader should be able to see
 * at a glance is now this: on a production build [override] is **always** `null`, because nothing in a
 * production build can supply one — its source exists only in a build made with the rig, and its absence
 * has exactly one meaning. The tier a shipped process runs is still a function of the device it runs on.
 * What changed is that a *test* build can pin a mechanism, which the deleted `SNAPSYNC_FORCE_URLSESSION_UPLOAD`
 * flag used to do (decision record `changes/archive/2026-08-24-retire-launch-env-triggers`, D14: deleted
 * there, restored here).
 *
 * **An [override] naming a mechanism this OS cannot run is ignored, not obeyed.** `setUploadJobExtensionEnabled`
 * does not exist below iOS 26.1, so honouring such an override would not select a mechanism — it would
 * trap and abort the process. The clamp lives here, in the one tested place, rather than at each caller;
 * `:test:architecture` asserts that no combination of inputs yields a mechanism its OS lacks.
 */
fun resolveUploadMechanism(
    backgroundUploadSupported: Boolean,
    permission: PermissionStatus,
    override: UploadMechanism? = null,
): UploadMechanism {
    // No usable access: nothing may read the library or upload, on any OS and under any override. An
    // override does not buy access it does not have, and IDLE still answers every OS trigger.
    if (!permission.grantsPhotoAccess) return UploadMechanism.IDLE

    val byDevice = when {
        // The OS-driven mechanism exists but the OS never invokes it under a partial grant, so a full
        // grant is what selects it; a partial one runs the app-driven mechanism on the very same OS.
        backgroundUploadSupported && permission == PermissionStatus.GRANTED -> UploadMechanism.PHOTOKIT
        else -> UploadMechanism.URL_SESSION
    }
    val wanted = override ?: byDevice
    return when (wanted) {
        // Requesting the OS-driven mechanism where its API does not exist is unrunnable, not merely
        // unusual — fall back to what the device resolves rather than hand back a trapping selector.
        UploadMechanism.PHOTOKIT -> if (backgroundUploadSupported) UploadMechanism.PHOTOKIT else byDevice
        UploadMechanism.URL_SESSION -> UploadMechanism.URL_SESSION
        // Pinning IDLE is a legal way to say "run no mechanism" without withdrawing photo access.
        UploadMechanism.IDLE -> UploadMechanism.IDLE
    }
}
