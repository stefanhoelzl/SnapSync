package app.snapsync.model

/**
 * The upload tier a live composition runs (capability `upload-lifecycle`, "Exactly one producer per
 * process"). Selection is a **fact about the OS and the launch**, not a scattered runtime guard:
 * exactly one tier is chosen once per process, and the non-selected tier's mechanism is never
 * constructed.
 *
 * - [PHOTOKIT] — the OS-driven `PHBackgroundResourceUploadExtension` registration (iOS ≥26.1).
 * - [URL_SESSION] — the in-app background `URLSession` pump (iOS 18–26.0, or the dev force flag).
 */
enum class UploadTier {
    PHOTOKIT,
    URL_SESSION,
    ;

    /** How a diagnostic dump names this tier (capability `diagnostic-logging`) — lowercase, stable. */
    val diagnosticName: String get() = name.lowercase()
}

/**
 * The resolved composition mode (spec `module-architecture`, "One shared composition"): a **sealed**
 * type so `composeRoot` switches once on it and the compiler fails closed when a new mode is added —
 * a data-class field would not. There are exactly two:
 *
 * - [Forge] — render the shared `StatusScreen` over forged sources for a marketing screenshot; the
 *   live stack is **not** assembled (no ledger, App Attest, PhotoKit, or backend). Carries the
 *   recognized state name.
 * - [Live] — the real stack, on the resolved [UploadTier].
 */
sealed interface CompositionMode {

    /**
     * How a diagnostic dump names the resolved composition (capability `diagnostic-logging`). Kept
     * here so the shell transcribes one resolved fact instead of branching on the mode a second time
     * (`module-architecture`, "Shells are wiring only").
     */
    val diagnosticTierName: String

    data class Forge(val state: String) : CompositionMode {
        override val diagnosticTierName: String get() = "forge"
    }

    data class Live(val tier: UploadTier) : CompositionMode {
        override val diagnosticTierName: String get() = tier.diagnosticName
    }
}

/**
 * The pure composition-mode resolver (spec `module-architecture`, "One shared composition"; decision
 * record `establish-target-architecture` D5). Its precedence is unit-tested (the shipped forge×link
 * interaction bug becomes a resolver test): **forge excludes the live-stack boot**.
 *
 * The forge decision comes first and wins **unconditionally over an event link** — a screenshot run
 * that also carries `SNAPSYNC_EVENT_LINK` must render the forged frame and provision **nothing**, or
 * a process rendering a forged frame would boot the live stack on an unsigned simulator with no
 * App-Group container, no App Attest, and no photo grant (incoherent before it is a crash). [forgeState]
 * is only honoured when [isForgeState] recognizes it (an unrecognized name falls through to [Live],
 * exactly as `SNAPSYNC_FORGE_STATE=nonsense` renders the live stack today).
 *
 * [isForgeState] is passed in — not imported — because recognition (mapping a name to forged sources)
 * is presentation's, unreachable from `model/`; the shell passes `presentation::isForgeState`, and the
 * precedence test passes a stub. Tier selection folds in here per the one-composition law: the
 * app-driven tier is chosen when the OS lacks the ≥26.1 API **or** the force flag is set.
 *
 * [backgroundUploadSupported] is the **only** OS input, and it is genuinely a runtime one: one binary
 * ships to iOS 18 and iOS 26 devices alike, so the tier cannot be known before launch. It arrives as a
 * bare `Boolean` rather than wrapped, an `OsFacts` value having been deleted once its second field went.
 * That second field was `isSimulator`, read from `SIMULATOR_DEVICE_NAME` to downgrade the URLSession
 * transport — a fact the **compilation target already fixes**, re-derived at runtime to serve a platform
 * claim that turned out to be false (`ios-url-session-upload`; decision record
 * `changes/archive/…-delete-simulator-session-downgrade`). A target-fixed fact does not belong here.
 */
fun resolveComposition(
    directives: LaunchDirectives,
    backgroundUploadSupported: Boolean,
    isForgeState: (String) -> Boolean,
): CompositionMode {
    // Forge excludes the live-stack boot — and it excludes an event link too (the forge×link bug).
    if (directives.forgeState != null && isForgeState(directives.forgeState)) {
        return CompositionMode.Forge(directives.forgeState)
    }
    val tier = if (!backgroundUploadSupported || directives.forceUrlSessionUpload) {
        UploadTier.URL_SESSION
    } else {
        UploadTier.PHOTOKIT
    }
    return CompositionMode.Live(tier = tier)
}
