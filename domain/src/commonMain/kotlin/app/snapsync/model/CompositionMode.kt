package app.snapsync.model

/**
 * The upload tier a live composition runs (capability `upload-lifecycle`, "Exactly one producer per
 * process"). Selection is a **fact about the OS**, not a scattered runtime guard: exactly one tier is
 * chosen once per process, and the non-selected tier's mechanism is never constructed.
 *
 * - [PHOTOKIT] — the OS-driven `PHBackgroundResourceUploadExtension` registration (iOS ≥26.1).
 * - [URL_SESSION] — the in-app background `URLSession` pump (iOS 18–26.0).
 */
enum class UploadTier {
    PHOTOKIT,
    URL_SESSION,
    ;

    /** How a diagnostic dump names this tier (capability `diagnostic-logging`) — lowercase, stable. */
    val diagnosticName: String get() = name.lowercase()
}

/**
 * The pure composition resolver (spec `module-architecture`, "One shared composition"; decision record
 * `establish-target-architecture` D5).
 *
 * It has exactly **one** input, and that is the whole point of it. One binary ships to iOS 18 and iOS 26
 * devices alike, so the tier genuinely cannot be known before launch — but nothing else may influence it.
 * There is no developer input here: no launch-environment variable, no build property, no runtime
 * override. The tier a process runs is a function of the device it runs on, and a reader of this function
 * can see that at a glance rather than having to prove it.
 *
 * It used to return a sealed `CompositionMode` with a second `Forge` case, so a marketing-screenshot run
 * could render the shared `StatusScreen` over forged sources without booting the live stack. That case is
 * gone, and with it the shell's outer mode switch and the ~15 no-op `Shell` members that had to keep it
 * inert. Forge is now its own binary target, linking neither `:app:ios` nor the live graph, so its
 * inertness is a thing the binary cannot express rather than a thing a delegate must perform correctly
 * (decision record: `changes/archive/…-retire-launch-env-triggers` D11). What remains is one fact in, one
 * tier out — which is why this returns [UploadTier] directly and the sealed wrapper is deleted rather
 * than kept at one case.
 *
 * It also used to take the force flag (`SNAPSYNC_FORCE_URLSESSION_UPLOAD`), which could select
 * [UploadTier.URL_SESSION] on a device whose OS supports the OS-driven tier. Note that its deletion does
 * **not** delete that arm: iOS 18–26.0 devices resolve to it by version, and a partial photo grant selects
 * the app-driven producer within the OS-driven tier (`ios-photokit-upload`). What is gone is any way to
 * *force* it — see `ios-url-session-upload`, whose removed requirement names where that returns.
 */
fun resolveComposition(backgroundUploadSupported: Boolean): UploadTier =
    if (backgroundUploadSupported) UploadTier.PHOTOKIT else UploadTier.URL_SESSION
